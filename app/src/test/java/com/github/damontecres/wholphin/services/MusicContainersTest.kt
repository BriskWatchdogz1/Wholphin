package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.util.profile.Codec
import com.github.damontecres.wholphin.util.profile.supportedAudioCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicContainersTest {
    @Test
    fun `ogg files are streamed as they are`() {
        assertTrue(Codec.Audio.OGG in musicContainers)
    }

    @Test
    fun `every supported codec name stays a container, without duplicates`() {
        assertTrue(musicContainers.containsAll(supportedAudioCodecs.toList()))
        assertEquals(musicContainers.size, musicContainers.toSet().size)
    }
}
