package com.sweepmytv.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** A short-lived encrypted import through the private comparison build's ADB app directory. */
final class ComparisonKeyImport {
    static final String ACTION = "com.sweepmytv.launcher.COMPARE_KEY_IMPORT";
    private static KeyPair pending;
    private static long expires;

    private ComparisonKeyImport() { }

    static synchronized void handle(Context context) {
        if (!BuildConfig.CATALOG_COMPARISON) return;
        File folder = context.getExternalFilesDir(null);
        if (folder == null) return;
        File request = new File(folder, "tmdb-import.request");
        File pub = new File(folder, "tmdb-import.der");
        File encrypted = new File(folder, "tmdb-import.enc");
        try {
            if (request.delete()) {
                encrypted.delete();
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                pending = generator.generateKeyPair();
                expires = SystemClock.elapsedRealtime() + 120_000L;
                Files.write(pub.toPath(), pending.getPublic().getEncoded());
            } else if (encrypted.isFile()) {
                if (pending == null || SystemClock.elapsedRealtime() > expires || encrypted.length() != 256)
                    return;
                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
                cipher.init(Cipher.DECRYPT_MODE, pending.getPrivate(),
                        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                byte[] raw = cipher.doFinal(Files.readAllBytes(encrypted.toPath()));
                try {
                    String key = new String(raw, StandardCharsets.US_ASCII);
                    if (key.matches("[a-fA-F0-9]{32}") && Keys.write(context, Keys.TMDB, key))
                        Log.i("SweepCatalogQA", "TMDB credential saved to app-private storage");
                } finally {
                    Arrays.fill(raw, (byte) 0);
                }
            }
        } catch (Exception failure) {
            Log.w("SweepCatalogQA", "Encrypted credential import failed");
        } finally {
            if (encrypted.isFile() || SystemClock.elapsedRealtime() > expires) {
                encrypted.delete();
                pub.delete();
                pending = null;
            }
        }
    }
}
