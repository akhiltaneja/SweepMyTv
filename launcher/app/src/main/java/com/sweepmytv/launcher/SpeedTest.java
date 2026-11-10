package com.sweepmytv.launcher;

// Adapted from Matinee (app/src/main/java/com/tcltv/sahne/system/SpeedTest.kt), MIT License,
// Copyright (c) 2026 Yiğit Can Polat. See THIRD_PARTY_NOTICES.md.

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Download speed, read from Cloudflare's public speed-test endpoint for a few seconds. Nothing is uploaded and the
 * request carries no identifiers beyond any HTTPS request's. Blocking: call off the main thread.
 */
final class SpeedTest {
    // The endpoint refuses 100 MB or more; on lines fast enough to finish early, the elapsed time is used instead.
    private static final String URL_DOWNLOAD = "https://speed.cloudflare.com/__down?bytes=90000000";
    private static final long MEASURE_NANOS = 4_000_000_000L;

    private SpeedTest() { }

    /** Megabits per second. */
    static double downloadMbps() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_DOWNLOAD).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setUseCaches(false);
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[64 * 1024];
            long total = 0, start = System.nanoTime();
            try (InputStream in = c.getInputStream()) {
                while (System.nanoTime() - start < MEASURE_NANOS) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    total += n;
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            if (seconds <= 0 || total == 0) throw new IOException("No data received");
            return total * 8 / seconds / 1_000_000;
        } finally {
            c.disconnect();
        }
    }
}
