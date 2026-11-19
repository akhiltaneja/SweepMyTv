package com.sweepmytv.launcher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;

import java.util.Properties;

enum AerialQuality {
    HD_H264("1080p H.264", "VIDEO_1080_H264"),
    HD_HEVC("1080p SDR", "VIDEO_1080_SDR"),
    UHD_SDR("4K SDR", "VIDEO_4K_SDR"),
    UHD_DOLBY("4K Dolby Vision", "VIDEO_4K_HDR");

    final String label;
    final String setting;

    AerialQuality(String label, String setting) {
        this.label = label;
        this.setting = setting;
    }

    static AerialQuality detect(Context context) {
        Display display = defaultDisplay(context);
        boolean dolby = false;
        if (display != null) {
            Display.HdrCapabilities hdr = display.getHdrCapabilities();
            if (hdr != null) {
                for (int type : hdr.getSupportedHdrTypes()) dolby |= type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION;
            }
        }
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        return choose(hasUhdDisplay(context), supports(codecs, "video/hevc", 3840, 2160),
                supports(codecs, "video/hevc", 1920, 1080),
                dolby && supports(codecs, "video/dolby-vision", 3840, 2160));
    }

    static boolean hasUhdDisplay(Context context) {
        Display display = defaultDisplay(context);
        if (display != null) {
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() >= 3840 && mode.getPhysicalHeight() >= 2160) return true;
            }
        }
        // This Redmi exposes a 1080p app surface even though its panel is 4K.
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                && "MiTV-MOOQ1".equalsIgnoreCase(Build.MODEL.replace('_', '-'));
    }

    private static Display defaultDisplay(Context context) {
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        return displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private static boolean supports(MediaCodecInfo[] codecs, String mime, int width, int height) {
        for (MediaCodecInfo codec : codecs) {
            if (codec.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated()) continue;
            if (codec.getName().startsWith("OMX.google.") || codec.getName().startsWith("c2.android.")) continue;
            for (String type : codec.getSupportedTypes()) {
                if (!type.equalsIgnoreCase(mime)) continue;
                try {
                    MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(type).getVideoCapabilities();
                    if (video != null && video.areSizeAndRateSupported(width, height, 30)) return true;
                } catch (IllegalArgumentException ignored) {
                    // The codec can advertise a MIME type without usable video capabilities.
                }
            }
        }
        return false;
    }

    static AerialQuality choose(boolean uhdPanel, boolean uhdHevc, boolean hdHevc, boolean uhdDolby) {
        if (uhdPanel && uhdDolby) return UHD_DOLBY;
        if (uhdPanel && uhdHevc) return UHD_SDR;
        return hdHevc ? HD_HEVC : HD_H264;
    }

    Properties settings() {
        Properties values = new Properties();
        values.setProperty("string_apple_videos_quality", setting);
        AerialQuality sdr = this == UHD_DOLBY ? UHD_SDR : this;
        values.setProperty("string_comm1_videos_quality", sdr.setting);
        values.setProperty("string_comm2_videos_quality", sdr.setting);
        values.setProperty("string_amazon_videos_quality", sdr == HD_H264 ? HD_HEVC.setting : sdr.setting);
        return values;
    }
}
