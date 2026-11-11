# Build environment for Wholphin without installing anything on the host.
#
# Close to .github/actions/setup, with two differences: the JDK is Temurin 21
# (CI uses Zulu 21) and there is no NDK, so the prebuilt native libraries are
# packaged unstripped ("Unable to strip the following libraries" in the Gradle
# output). build-tools 36.0.0 is the CI version, the platform matches
# compileSdk. The Android command line tools come from dl.google.com and are
# verified against the SHA-256 published on developer.android.com/studio.
# ktlint (the version pinned in .pre-commit-config.yaml) comes from Maven Central
# and is verified against its published SHA-256. The base image is pinned by
# digest; apt packages and the platform package carry no version pin, so a
# rebuilt image may differ in those.
#
# Gradle's cache lives outside the image (GRADLE_USER_HOME is a bind mount, see
# build.sh), so the image only has to be rebuilt when a tool version changes.
FROM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a

ARG CMDLINE_TOOLS_VERSION=15859902
ARG CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
# compileSdk 37 in gradle/libs.versions.toml; the SDK repository names it android-37.0
ARG PLATFORM_PACKAGE=android-37.0
ARG BUILD_TOOLS_VERSION=36.0.0
ARG KTLINT_VERSION=1.8.0
ARG KTLINT_SHA256=369ad2b789f95a011f807e1fcb690ccef80bd7cd014fd139e73ae82dcc0baeab
# The build runs as this user so files written into the mounted checkout belong
# to the host user. build.sh passes the host's ids.
ARG UID=1000
ARG GID=1000

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/cache/gradle \
    KTLINT_JAR=/opt/ktlint/ktlint-cli.jar
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git unzip \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    curl -fsSL -o /tmp/cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"; \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdline-tools.zip" | sha256sum -c -; \
    mkdir -p "${ANDROID_HOME}/cmdline-tools"; \
    unzip -q /tmp/cmdline-tools.zip -d "${ANDROID_HOME}/cmdline-tools"; \
    mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"; \
    rm /tmp/cmdline-tools.zip; \
    yes | sdkmanager --licenses > /dev/null; \
    sdkmanager "platforms;${PLATFORM_PACKAGE}" "build-tools;${BUILD_TOOLS_VERSION}"

RUN set -eux; \
    mkdir -p /opt/ktlint; \
    curl -fsSL -o "${KTLINT_JAR}" \
        "https://repo1.maven.org/maven2/com/pinterest/ktlint/ktlint-cli/${KTLINT_VERSION}/ktlint-cli-${KTLINT_VERSION}-all.jar"; \
    echo "${KTLINT_SHA256}  ${KTLINT_JAR}" | sha256sum -c -

# The base image already ships a user with uid 1000; replace whatever holds the
# requested ids so the build user ends up with exactly the host's uid and gid.
RUN set -eux; \
    existing="$(getent passwd "${UID}" | cut -d: -f1 || true)"; \
    if [ -n "${existing}" ]; then userdel -r "${existing}" || true; fi; \
    getent group "${GID}" > /dev/null || groupadd -g "${GID}" builder; \
    useradd -m -u "${UID}" -g "${GID}" -s /bin/bash builder; \
    mkdir -p "${GRADLE_USER_HOME}" /work; \
    chown -R "${UID}:${GID}" "${ANDROID_HOME}" "${GRADLE_USER_HOME}" /work

USER builder
WORKDIR /work
