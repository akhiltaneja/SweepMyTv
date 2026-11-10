package com.sweepmytv.launcher;

import android.media.tv.TvInputInfo;

import java.util.Locale;

/**
 * Inputs: what's plugged into an HDMI port, by the name it should go by. Devices announce themselves over HDMI-CEC
 * with a short on-screen name ("PS5", "NintendoSwitch", "AFTMM"), a vendor ID and a device type; this turns that into
 * "PlayStation 5", "Nintendo Switch" or "Fire TV", with the brand's colour for the row's dot. Adapted from Luma's
 * device list (luma-tv, MIT).
 */
final class Devices {
    /** HDMI-CEC device types. */
    static final int RECORDER = 1, TUNER = 3, PLAYBACK = 4, AUDIO = 5;
    /** HDMI-CEC vendor IDs (IEEE OUIs), as libCEC lists them. */
    private static final int SONY = 0x080046, APPLE = 0x0010FA;
    /** Green, the dot's colour for anything not recognised. */
    static final int PLAIN = 0xFF30D158;

    final String name;
    final int color;

    private Devices(String name, int color) {
        this.name = name;
        this.color = color;
    }

    /** The device behind a port, from what it announced; vendor and type are -1 when unknown. Never null. */
    static Devices identify(String osd, int vendor, int type) {
        String raw = osd == null ? "" : osd.trim(), n = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        // Consoles first: their names are unambiguous.
        if (n.matches("ps5|playstation5.*")) return new Devices("PlayStation 5", 0xFF0070D1);
        if (n.matches("ps4|playstation4.*")) return new Devices("PlayStation 4", 0xFF0070D1);
        if (n.matches("ps3|playstation3.*")) return new Devices("PlayStation 3", 0xFF0070D1);
        if (n.startsWith("playstation") || n.equals("ps") || vendor == SONY && type == PLAYBACK && n.startsWith("ps"))
            return new Devices("PlayStation", 0xFF0070D1);
        if (n.startsWith("xbox")) {
            String model = n.contains("seriesx") ? "Xbox Series X" : n.contains("seriess") ? "Xbox Series S"
                    : n.contains("series") ? "Xbox Series X|S" : n.contains("one") ? "Xbox One" : "Xbox";
            return new Devices(model, 0xFF107C10);
        }
        if (n.contains("nintendo") || n.equals("switch") || n.equals("switch2"))
            return new Devices(n.contains("2") ? "Nintendo Switch 2" : "Nintendo Switch", 0xFFE60012);
        if (n.contains("steamdeck")) return new Devices("Steam Deck", 0xFF1A9FFF);
        if (n.contains("shield")) return new Devices("NVIDIA SHIELD", 0xFF76B900);
        // Streaming players.
        if (n.contains("appletv") || vendor == APPLE && type == PLAYBACK) return new Devices("Apple TV", 0xFFAEAEB2);
        if (n.contains("chromecast")) return new Devices("Chromecast", 0xFF4285F4);
        if (n.equals("googletv") || n.contains("googletvstreamer")) return new Devices("Google TV Streamer", 0xFF4285F4);
        if (n.startsWith("firetv") || n.startsWith("aft") && n.length() <= 8 || n.contains("amazonfire"))
            return new Devices("Fire TV", 0xFFFF9900);
        if (n.startsWith("roku")) return new Devices(pretty(raw), 0xFF6F1AB1);
        if (n.contains("mitvstick") || n.contains("mibox") || n.contains("xiaomitv")) return new Devices(pretty(raw), 0xFFFF6900);
        // Sound: a soundbar or an AV receiver announces itself as an audio system.
        if (type == AUDIO || n.contains("soundbar") || n.matches(".*(receiver|avr|sonos|bose|harman).*"))
            return new Devices(raw.isEmpty() ? "Sound system" : pretty(raw), PLAIN);
        if (n.matches(".*(bluray|bdplayer|bdp\\d*|dvd).*")) return new Devices(raw.isEmpty() ? "Blu-ray player" : pretty(raw), PLAIN);
        if (type == TUNER || type == RECORDER) return new Devices(raw.isEmpty() ? "Set-top box" : pretty(raw), PLAIN);
        return new Devices(raw.isEmpty() ? "HDMI device" : pretty(raw), PLAIN);
    }

    /**
     * {vendor ID, device type} of the device input behind a port, or -1s. Android keeps them in HdmiDeviceInfo, which
     * apps can't name, so it's read by reflection.
     */
    static int[] cec(TvInputInfo device) {
        try {
            Object d = TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(device);
            if (d != null) return new int[]{(int) d.getClass().getMethod("getVendorId").invoke(d),
                    (int) d.getClass().getMethod("getDeviceType").invoke(d)};
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return new int[]{-1, -1};
    }

    /** "NintendoSwitch" → "Nintendo Switch", "DIGITAL_RECEIVER" → "Digital Receiver"; names with spaces stay. */
    static String pretty(String raw) {
        String s = raw.replace('_', ' ').trim();
        if (!s.contains(" ")) s = s.replaceAll("([a-z])([A-Z])", "$1 $2");
        // All capitals become title case, but model numbers (HT-A5000) stay as they are.
        if (s.equals(s.toUpperCase(Locale.ROOT)) && s.length() > 4 && !s.matches(".*\\d.*")) {
            StringBuilder b = new StringBuilder();
            for (String w : s.toLowerCase(Locale.ROOT).split(" ")) {
                if (b.length() > 0) b.append(' ');
                b.append(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
            }
            s = b.toString();
        }
        return s;
    }
}
