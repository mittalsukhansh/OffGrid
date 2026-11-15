package com.example.offgrid;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.nio.file.Files;

/** Plaintext appears in private cache only when the user explicitly opens an attachment. */
public final class AttachmentFiles {
    private static File path(Context context, String id, String suffix) {
        if (!id.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid attachment ID");
        File dir = new File(context.getCacheDir(), "shared-media");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IllegalStateException("Could not prepare attachment cache");
        return new File(dir, id + suffix);
    }

    public static Uri prepare(Context context, String id, byte[] bytes, String mime)
            throws Exception {
        File file = path(context, id, mime.equals("image/jpeg") ? ".jpg" : ".mp4");
        Files.write(file.toPath(), bytes);
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
    }

    public static void remove(Context context, String id) {
        MediaThumbnails.remove(id);
        for (String suffix : new String[] {"", ".jpg", ".mp4"}) {
            File file = path(context, id, suffix);
            if (file.exists()) {
                Uri uri =
                        FileProvider.getUriForFile(
                                context, context.getPackageName() + ".files", file);
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                file.delete();
            }
        }
    }
}
