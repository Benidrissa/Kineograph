package org.kineograph.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.kineograph.media.video.videoFormat
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoFormatTest {
    private val baseline =
        MediaCodecInfo.CodecProfileLevel().apply {
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            level = MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }

    @Test
    fun `asking for Baseline comes with a level, because older Android refuses one without the other`() {
        val format = videoFormat(1280, 720, 12, 4_000_000, baseline)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, format.getInteger(MediaFormat.KEY_PROFILE))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, format.getInteger(MediaFormat.KEY_LEVEL))
    }

    @Test
    fun `the second try leaves the choice to an encoder that refused Baseline`() {
        val format = videoFormat(1280, 720, 12, 4_000_000, null)
        assertFalse(format.containsKey(MediaFormat.KEY_PROFILE))
        assertFalse(format.containsKey(MediaFormat.KEY_LEVEL))
        assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(4_000_000, format.getInteger(MediaFormat.KEY_BIT_RATE))
        assertEquals(12, format.getInteger(MediaFormat.KEY_FRAME_RATE))
    }
}
