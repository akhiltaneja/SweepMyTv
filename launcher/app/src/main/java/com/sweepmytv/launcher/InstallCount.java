package com.sweepmytv.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.net.ssl.HttpsURLConnection;

/** Counts a launcher installation once after it is opened. */
final class InstallCount {
    private static final long RETRY_DELAY_MS = 5 * 60_000L;
    private static volatile boolean counted;
    private static volatile long retryAfter;

    private InstallCount() { }

    static void onLaunch(Context context, Executor executor) {
        if (BuildConfig.CATALOG_COMPARISON || !BuildConfig.INSTALL_COUNT_URL.startsWith("https://")
                || counted || SystemClock.elapsedRealtime() < retryAfter) return;
        Context app = context.getApplicationContext();
        executor.execute(() -> reportIfNeeded(app));
    }

    private static synchronized void reportIfNeeded(Context context) {
        if (counted || SystemClock.elapsedRealtime() < retryAfter) return;
        retryAfter = SystemClock.elapsedRealtime() + RETRY_DELAY_MS;
        File receipt = new File(context.getNoBackupFilesDir(), "install-count-shared");
        if (receipt.isFile()) {
            counted = true;
            return;
        }

        HttpsURLConnection connection = null;
        try {
            byte[] body = new JSONObject()
                    .put("id", installId(context))
                    .put("version", BuildConfig.VERSION_NAME)
                    .toString().getBytes(StandardCharsets.UTF_8);
            connection = (HttpsURLConnection) new URL(BuildConfig.INSTALL_COUNT_URL).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            if (connection.getResponseCode() == 204) {
                write(new AtomicFile(receipt), "shared");
                counted = receipt.isFile();
            }
        } catch (IOException | JSONException ignored) {
            // A later launch can retry with the same install ID.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String installId(Context context) throws IOException {
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "install-count-id"));
        try {
            return new String(file.readFully(), StandardCharsets.US_ASCII);
        } catch (FileNotFoundException missing) {
            String id = UUID.randomUUID().toString();
            write(file, id);
            return new String(file.readFully(), StandardCharsets.US_ASCII);
        }
    }

    private static void write(AtomicFile file, String value) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
            file.finishWrite(output);
        } catch (IOException failed) {
            file.failWrite(output);
            throw failed;
        }
    }
}
