package com.sweepmytv.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AerialQualityTest {
    @Test public void selectsResolutionAndCodecForTheDisplay() {
        assertEquals(AerialQuality.UHD_DOLBY, AerialQuality.choose(true, true, true, true));
        assertEquals(AerialQuality.UHD_SDR, AerialQuality.choose(true, true, true, false));
        assertEquals(AerialQuality.HD_HEVC, AerialQuality.choose(false, true, true, true));
        assertEquals(AerialQuality.HD_H264, AerialQuality.choose(true, false, false, false));
    }

    @Test public void dolbyPresetUsesSdrForSourcesWithoutDolbyVision() {
        java.util.Properties settings = AerialQuality.UHD_DOLBY.settings();
        assertEquals(4, settings.size());
        assertEquals("VIDEO_4K_HDR", settings.getProperty("string_apple_videos_quality"));
        assertEquals("VIDEO_4K_SDR", settings.getProperty("string_comm1_videos_quality"));
        assertEquals("VIDEO_4K_SDR", settings.getProperty("string_comm2_videos_quality"));
        assertEquals("VIDEO_4K_SDR", settings.getProperty("string_amazon_videos_quality"));
    }
}
