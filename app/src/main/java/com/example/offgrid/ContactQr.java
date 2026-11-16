package com.example.offgrid;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

public final class ContactQr {
    public static Bitmap bitmap(String contact, int size) throws WriterException {
        if (contact == null
                || !(contact.startsWith(CryptoManager.CONTACT_PREFIX)
                        || contact.startsWith("offgrid5:"))
                || contact.length() > 400
                || size < 64
                || size > 1024) {
            throw new IllegalArgumentException("Invalid contact QR request");
        }
        return new BarcodeEncoder().encodeBitmap(contact, BarcodeFormat.QR_CODE, size, size);
    }
}
