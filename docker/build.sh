#!/usr/bin/env bash
# Build Wholphin inside the Docker image described by docker/Dockerfile, so the host
# needs neither a JDK nor the Android SDK. Gradle's cache persists between runs.
#
# Usage:
#   docker/build.sh [gradle arguments...]   run Gradle; default: assembleDefaultRelease
#   docker/build.sh --image                 (re)build the Docker image
#   docker/build.sh --keystore              create the release keystore if it does not exist
#   docker/build.sh --ktlint [paths...]     run ktlint (default: app/src); add --format to fix
#   docker/build.sh --shell                 open a shell in the build container
#
# Configuration lives outside the checkout in
#   ${WHOLPHIN_BUILD_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/wholphin-build/build.env}
# and may set:
#   WHOLPHIN_CACHE_DIR              cache root for the container: Gradle home in gradle/, the
#                                   Maven repository that Robolectric fills with its Android
#                                   jars in m2/ (default: ~/.cache/wholphin-build)
#   WHOLPHIN_APPLICATION_ID_SUFFIX  appended to the application id, e.g. ".local", so the build
#                                   installs next to the upstream app (default: none)
#   WHOLPHIN_IMAGE                  Docker image tag (default: wholphin-build:latest)
#   WHOLPHIN_SIGNING_ENV            file with the signing secrets (default: signing.env next to
#                                   build.env). It holds WHOLPHIN_KEYSTORE_FILE (host path),
#                                   WHOLPHIN_KEYSTORE_PASSWORD, WHOLPHIN_KEY_ALIAS and
#                                   WHOLPHIN_KEY_PASSWORD. Without it release builds are unsigned.
#   WHOLPHIN_EXTENSIONS_PROPERTIES  Gradle properties file with WholphinExtensionsUsername and
#                                   WholphinExtensionsPassword (see DEVELOPMENT.md); default:
#                                   ~/.gradle/gradle.properties. Without them the app builds
#                                   without the native extensions.
#
# The signing values and the extension credentials reach Gradle only as environment variables
# inside the container; see the "local" signing config in app/build.gradle.kts.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
checkout="$(cd "${script_dir}/.." && pwd)"

config_dir="${XDG_CONFIG_HOME:-${HOME}/.config}/wholphin-build"
config_file="${WHOLPHIN_BUILD_CONFIG:-${config_dir}/build.env}"
if [ -f "${config_file}" ]; then
    # shellcheck disable=SC1090
    . "${config_file}"
fi

cache_dir="${WHOLPHIN_CACHE_DIR:-${XDG_CACHE_HOME:-${HOME}/.cache}/wholphin-build}"
image="${WHOLPHIN_IMAGE:-wholphin-build:latest}"
signing_env="${WHOLPHIN_SIGNING_ENV:-$(dirname "${config_file}")/signing.env}"
suffix="${WHOLPHIN_APPLICATION_ID_SUFFIX:-}"

uid="$(id -u)"
gid="$(id -g)"

build_image() {
    docker build --build-arg "UID=${uid}" --build-arg "GID=${gid}" -t "${image}" "${script_dir}"
}

ensure_image() {
    if ! docker image inspect "${image}" > /dev/null 2>&1; then
        echo "Image ${image} not found, building it" >&2
        build_image
    fi
}

ensure_cache() {
    # m2: Robolectric downloads an Android jar of 100 MB and more per SDK level into ~/.m2 of the
    # build user; without the mount every test run fetches them again, and Maven Central answers
    # repeated downloads with HTTP 429
    mkdir -p "${cache_dir}/gradle" "${cache_dir}/m2"
    # Gradle reads this file before the project's gradle.properties; the project caps the heap
    # at 2 GB, which is tight for R8 on a release build
    local props="${cache_dir}/gradle/gradle.properties"
    if [ ! -f "${props}" ]; then
        cat > "${props}" <<'EOF'
org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8
org.gradle.caching=true
kotlin.daemon.jvmargs=-Xmx3g
EOF
    fi
}

# Every mode mounts the caches, so they must exist before docker creates them as root
ensure_cache

# docker run arguments shared by every mode
run_args=(
    --rm
    --user "${uid}:${gid}"
    -v "${checkout}:/work"
    -v "${cache_dir}/gradle:/cache/gradle"
    -v "${cache_dir}/m2:/home/builder/.m2"
    -w /work
)
if [ -t 0 ]; then
    run_args+=(-it)
fi

# Signing: secrets travel in the env file, the keystore is mounted read-only at a fixed path
signing_args=()
if [ -f "${signing_env}" ]; then
    keystore_file="$(sed -n 's/^WHOLPHIN_KEYSTORE_FILE=//p' "${signing_env}" | tail -n 1)"
    if [ -z "${keystore_file}" ]; then
        echo "WHOLPHIN_KEYSTORE_FILE is not set in ${signing_env}" >&2
        exit 1
    fi
    signing_args+=(--env-file "${signing_env}")
    if [ -f "${keystore_file}" ]; then
        signing_args+=(-v "${keystore_file}:/keys/release.jks:ro" -e WHOLPHIN_KEYSTORE_FILE=/keys/release.jks)
    fi
fi

# Native extensions: the GitHub Packages credentials become ORG_GRADLE_PROJECT_* variables, which
# Gradle reads as properties; docker gets only the variable names, never the values
extension_args=()
extensions_props="${WHOLPHIN_EXTENSIONS_PROPERTIES:-${HOME}/.gradle/gradle.properties}"
if [ -f "${extensions_props}" ]; then
    for key in WholphinExtensionsUsername WholphinExtensionsPassword; do
        value="$(sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "${extensions_props}" |
            tail -n 1 | sed 's/[[:space:]]*$//')"
        if [ -n "${value}" ]; then
            export "ORG_GRADLE_PROJECT_${key}=${value}"
            extension_args+=(-e "ORG_GRADLE_PROJECT_${key}")
        fi
    done
    unset value
fi

case "${1:-}" in
    --image)
        build_image
        exit 0
        ;;
    --keystore)
        if [ -f "${signing_env}" ]; then
            keystore_file="$(sed -n 's/^WHOLPHIN_KEYSTORE_FILE=//p' "${signing_env}" | tail -n 1)"
            if [ -f "${keystore_file}" ]; then
                echo "Keystore ${keystore_file} exists, nothing to do"
                exit 0
            fi
        else
            keystore_file="$(dirname "${signing_env}")/release.jks"
            mkdir -p "$(dirname "${signing_env}")"
            password="$(openssl rand -base64 30 | tr -d '\n')"
            (
                umask 077
                cat > "${signing_env}" <<EOF
WHOLPHIN_KEYSTORE_FILE=${keystore_file}
WHOLPHIN_KEYSTORE_PASSWORD=${password}
WHOLPHIN_KEY_ALIAS=wholphin-local
WHOLPHIN_KEY_PASSWORD=${password}
EOF
            )
            unset password
            echo "Wrote ${signing_env}"
        fi
        ensure_image
        # keytool reads the passwords from the environment (":env"), nothing appears on a command
        # line. The directory is mounted, not the file: keytool refuses to write into an existing
        # empty file, and a bind mount needs an existing path.
        docker run "${run_args[@]}" --env-file "${signing_env}" \
            -v "$(dirname "${keystore_file}"):/keys" "${image}" \
            sh -c 'keytool -genkeypair -keystore "/keys/$0" -alias "$WHOLPHIN_KEY_ALIAS" \
                -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Wholphin local build" \
                -storepass:env WHOLPHIN_KEYSTORE_PASSWORD -keypass:env WHOLPHIN_KEY_PASSWORD' \
            "$(basename "${keystore_file}")"
        chmod 600 "${keystore_file}"
        echo "Created ${keystore_file}; back up the keystore and ${signing_env} together"
        exit 0
        ;;
    --ktlint)
        shift
        ensure_image
        paths=("$@")
        if [ ${#paths[@]} -eq 0 ]; then
            paths=(app/src)
        fi
        exec docker run "${run_args[@]}" "${image}" \
            sh -c 'java -jar "$KTLINT_JAR" "$@"' ktlint "${paths[@]}"
        ;;
    --shell)
        ensure_image
        exec docker run "${run_args[@]}" "${signing_args[@]}" "${image}" bash
        ;;
esac

ensure_image

gradle_args=(--no-daemon --console=plain)
if [ -n "${suffix}" ]; then
    gradle_args+=("-PapplicationIdSuffix=${suffix}")
fi
if [ $# -eq 0 ]; then
    set -- assembleDefaultRelease
fi

exec docker run "${run_args[@]}" "${signing_args[@]}" "${extension_args[@]}" "${image}" \
    ./gradlew "${gradle_args[@]}" "$@"
