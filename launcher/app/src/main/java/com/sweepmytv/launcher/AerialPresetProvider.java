package com.sweepmytv.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Offers generated quality-only settings to Aerial Views via a one-URI read grant. */
public final class AerialPresetProvider extends ContentProvider {
    private static final String SUFFIX = ".avsettings";
    private static final String TYPE = "application/avsettings";

    static Intent importIntent(Context context, AerialQuality quality) {
        Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".aerialpreset")
                .appendPath(quality.name() + SUFFIX).build();
        return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, TYPE)
                .setClassName(ScreenSavers.AERIAL, ScreenSavers.AERIAL + ".ui.MainActivity")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return TYPE; }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"r".equals(mode) || uri.getPathSegments().size() != 1 || name == null || !name.endsWith(SUFFIX)) {
            throw new FileNotFoundException();
        }
        try {
            AerialQuality quality = AerialQuality.valueOf(name.substring(0, name.length() - SUFFIX.length()));
            File output = File.createTempFile("aerial-preset-", SUFFIX, getContext().getCacheDir());
            try {
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    quality.settings().store(stream, "Sweep TV video quality preset");
                }
                return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY);
            } finally {
                output.delete();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new FileNotFoundException("Aerial Views preset unavailable");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? uri.getLastPathSegment() : null);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
