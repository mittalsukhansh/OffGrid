package com.example.offgrid;

import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** SAF backup flow. Private plaintext never touches disk; KDF and IO run off the main thread. */
final class IdentityBackupUi implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
            new java.util.concurrent.atomic.AtomicBoolean();

    static boolean busy() {
        return BUSY.get();
    }

    private final AppCompatActivity activity;
    private final BooleanSupplier stopped;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> create;
    private final ActivityResultLauncher<String[]> open;

    IdentityBackupUi(AppCompatActivity activity, BooleanSupplier stopped) {
        this.activity = activity;
        this.stopped = stopped;
        create =
                activity.registerForActivityResult(
                        new ActivityResultContracts.CreateDocument("application/octet-stream"),
                        uri -> {
                            if (uri != null) password(uri, false);
                        });
        open =
                activity.registerForActivityResult(
                        new ActivityResultContracts.OpenDocument(),
                        uri -> {
                            if (uri != null) password(uri, true);
                        });
    }

    void show() {
        new AlertDialog.Builder(activity)
                .setTitle("Identity backup")
                .setMessage(
                        "Save identity keys, contacts, nicknames and verification status with a"
                            + " strong passphrase. Chat history and files are not included. Anyone"
                            + " with the backup and passphrase can impersonate you. Restore on an"
                            + " empty installation with mesh stopped, then stop using the old"
                            + " installation; concurrent copies are unsupported.")
                .setPositiveButton(
                        "Export encrypted backup", (d, w) -> create.launch("offgrid-identity.ogb"))
                .setNeutralButton(
                        "Restore backup",
                        (d, w) -> {
                            if (!stopped.getAsBoolean()) {
                                message("Stop mesh before restoring identity");
                                return;
                            }
                            open.launch(new String[] {"application/octet-stream", "*/*"});
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void password(Uri uri, boolean restoring) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setFilters(
                new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(1024)});
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(restoring ? "Restore identity" : "Encrypt identity backup")
                        .setMessage(
                                "Use a passphrase of at least 12 characters. Keep it separately; it"
                                    + " cannot be recovered. Restoring replaces this installation's"
                                    + " identity and contacts. History must be empty.")
                        .setView(input)
                        .setPositiveButton(restoring ? "Restore" : "Export", null)
                        .setNegativeButton("Cancel", null)
                        .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            if (input.length() < 12) {
                                input.setError("At least 12 characters");
                                return;
                            }
                            if (!BUSY.compareAndSet(false, true)) {
                                message("A backup operation is already running");
                                return;
                            }
                            char[] passphrase = new char[input.length()];
                            input.getText().getChars(0, input.length(), passphrase, 0);
                            input.getText().clear();
                            dialog.dismiss();
                            message("Working on encrypted backup…");
                            worker.execute(() -> {
                                byte[] data = null;
                                try {
                                    if (restoring) {
                                        if (!stopped.getAsBoolean())
                                            throw new IllegalStateException("Stop mesh first");
                                        MessageStore store = MessageStore.get(activity);
                                        if (!store.dao().history().isEmpty()
                                                || !store.dao().pending().isEmpty())
                                            throw new IllegalStateException(
                                                    "Restore requires an empty installation;"
                                                            + " existing history was preserved");
                                        try (InputStream in =
                                                activity.getContentResolver()
                                                        .openInputStream(uri)) {
                                            if (in == null)
                                                throw new java.io.IOException("No source");
                                            java.io.ByteArrayOutputStream bytes =
                                                    new java.io.ByteArrayOutputStream();
                                            byte[] block = new byte[4096];
                                            int count;
                                            while ((count = in.read(block)) != -1) {
                                                if (bytes.size() + count > IdentityBackup.MAX_BYTES)
                                                    throw new java.io.IOException(
                                                            "Backup too large");
                                                bytes.write(block, 0, count);
                                            }
                                            data = bytes.toByteArray();
                                        }
                                        CryptoManager.getInstance(activity)
                                                .restoreIdentity(data, passphrase);
                                        activity.runOnUiThread(() -> {
                                            if (!activity.isDestroyed()) activity.recreate();
                                        });
                                    } else {
                                        data =
                                                CryptoManager.getInstance(activity)
                                                        .exportIdentity(passphrase);
                                        try (OutputStream out =
                                                activity.getContentResolver()
                                                        .openOutputStream(uri, "wt")) {
                                            if (out == null)
                                                throw new java.io.IOException("No destination");
                                            out.write(data);
                                        }
                                    }
                                    message(
                                            restoring
                                                    ? "Identity restored. Stop using the old"
                                                            + " installation."
                                                    : "Encrypted identity backup saved");
                                } catch (Exception failure) {
                                    message(
                                            restoring
                                                    ? "Restore failed: check passphrase, backup and"
                                                            + " empty-history requirement"
                                                    : "Backup failed; check destination and try"
                                                            + " again");
                                } finally {
                                    BUSY.set(false);
                                    Arrays.fill(passphrase, '\0');
                                    if (data != null) Arrays.fill(data, (byte) 0);
                                }
                            });
                        }));
        dialog.show();
    }

    private void message(String text) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
        });
    }

    public void close() {
        worker.shutdown();
    }
}
