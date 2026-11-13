package com.yourcompany.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackNotificationTimeTest {
    @Test
    fun formatsCurrentPositionAndDurationAsHoursMinutesAndSeconds() {
        assertEquals(
            "01:02:03 / 02:03:04",
            PlaybackNotificationTime.format(3_723_000L, 7_384_000L)
        )
    }

    @Test
    fun showsUnknownDurationAndClampsNegativePosition() {
        assertEquals(
            "00:00:00 / --:--:--",
            PlaybackNotificationTime.format(-1L, -1L)
        )
    }
}
