package com.example.offgrid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

/** Small memory-only image previews. No automatic video decoding or plaintext disk thumbnails. */
final class MediaThumbnails {
    private static final LruCache<String, Bitmap> cache =
            new LruCache<String, Bitmap>(1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };

    static Bitmap load(String id, byte[] encrypted, StorageCipher cipher) throws Exception {
        Bitmap existing = cache.get(id);
        if (existing != null) return existing;
        byte[] bytes = cipher.decrypt("media-file:" + id, encrypted);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap != null) cache.put(id, bitmap);
        return bitmap;
    }

    static void remove(String id) {
        cache.remove(id);
    }
}
