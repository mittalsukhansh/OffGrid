package com.example.offgrid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** User-selected media only; bounded decoding and no external paths or network access. */
public final class AttachmentPreparer {
    public static byte[] image(Context context, Uri uri) throws Exception {
        Bitmap bitmap;
        if (Build.VERSION.SDK_INT >= 28) {
            bitmap =
                    ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(context.getContentResolver(), uri),
                            (decoder, info, source) -> {
                                int width = info.getSize().getWidth(),
                                        height = info.getSize().getHeight();
                                if (width <= 0 || height <= 0 || (long) width * height > 40_000_000)
                                    throw new IllegalArgumentException(
                                            "Image dimensions are too large");
                                float scale = Math.min(1f, 768f / Math.max(width, height));
                                decoder.setTargetSize(
                                        Math.max(1, (int) (width * scale)),
                                        Math.max(1, (int) (height * scale)));
                                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            });
        } else {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0
                    || bounds.outHeight <= 0
                    || (long) bounds.outWidth * bounds.outHeight > 40_000_000)
                throw new IllegalArgumentException("Unsupported or oversized image");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 768)
                options.inSampleSize *= 2;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
        }
        if (bitmap == null) throw new IllegalArgumentException("Could not decode image");
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                for (int quality : new int[] {80, 60, 40, 25}) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output);
                    if (output.size() <= MediaTransfers.IMAGE_MAX) {
                        byte[] bytes = output.toByteArray();
                        validate(bytes, "image/jpeg");
                        return bytes;
                    }
                }
                Bitmap smaller =
                        Bitmap.createScaledBitmap(
                                bitmap,
                                Math.max(1, bitmap.getWidth() * 3 / 4),
                                Math.max(1, bitmap.getHeight() * 3 / 4),
                                true);
                if (smaller != bitmap) bitmap.recycle();
                bitmap = smaller;
            }
            throw new IllegalArgumentException("Image could not be compressed below 48 KB");
        } finally {
            bitmap.recycle();
        }
    }

    public static byte[] video(Context context, Uri uri) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Could not open clip");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MediaTransfers.VIDEO_MAX)
                    throw new IllegalArgumentException(
                            "Choose an MP4 clip no larger than 256 KB (10 seconds maximum)");
                output.write(buffer, 0, read);
            }
        }
        byte[] bytes = output.toByteArray();
        validate(bytes, "video/mp4");
        return bytes;
    }

    static void validate(byte[] bytes, String mime) throws Exception {
        if (bytes == null || bytes.length == 0)
            throw new IllegalArgumentException("Empty attachment");
        if (mime.equals("image/jpeg")) {
            if (bytes.length > MediaTransfers.IMAGE_MAX
                    || bytes.length < 3
                    || (bytes[0] & 255) != 255
                    || (bytes[1] & 255) != 216)
                throw new IllegalArgumentException("Invalid or oversized JPEG");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0
                    || bounds.outHeight <= 0
                    || bounds.outWidth > 1024
                    || bounds.outHeight > 1024)
                throw new IllegalArgumentException("Image dimensions exceed the transfer limit");
        } else if (mime.equals("video/mp4")) {
            if (bytes.length > MediaTransfers.VIDEO_MAX
                    || bytes.length < 12
                    || bytes[4] != 'f'
                    || bytes[5] != 't'
                    || bytes[6] != 'y'
                    || bytes[7] != 'p')
                throw new IllegalArgumentException("Choose an MP4 clip up to 256 KB");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(
                        new MediaDataSource() {
                            @Override
                            public int readAt(long position, byte[] buffer, int offset, int size) {
                                if (position < 0 || position >= bytes.length) return -1;
                                int count = Math.min(size, bytes.length - (int) position);
                                System.arraycopy(bytes, (int) position, buffer, offset, count);
                                return count;
                            }

                            @Override
                            public long getSize() {
                                return bytes.length;
                            }

                            @Override
                            public void close() {}
                        });
                String duration =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long millis = duration == null ? 0 : Long.parseLong(duration);
                if (millis <= 0 || millis > 10_000)
                    throw new IllegalArgumentException("Video must be 10 seconds or shorter");
            } finally {
                retriever.release();
            }
        } else throw new IllegalArgumentException("Only JPEG images and MP4 video are supported");
    }
}
