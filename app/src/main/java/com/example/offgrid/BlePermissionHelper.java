package com.example.offgrid;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles runtime permissions and radio-state checks for BLE mesh networking.
 *
 * USAGE (in your Activity, create it in onCreate BEFORE the Activity is STARTED):
 *
 * <pre>
 * private BlePermissionHelper permHelper;
 *
 * protected void onCreate(Bundle b) {
 *     super.onCreate(b);
 *     permHelper = new BlePermissionHelper(this, new BlePermissionHelper.Callback() {
 *         public void onAllGranted() { startMeshService(); }
 *         public void onDenied(List<String> denied, boolean permanentlyDenied) {
 *             if (permanentlyDenied) showSettingsDialog();   // then BlePermissionHelper.openAppSettings(this)
 *             else showRationale();
 *         }
 *         public void onBluetoothEnabled(boolean enabled) { if (enabled) startMeshService(); }
 *     });
 *     permHelper.requestPermissions();
 * }
 * </pre>
 */
public class BlePermissionHelper {

    public interface Callback {
        /** All required runtime permissions are granted. */
        void onAllGranted();

        /**
         * @param denied              permissions that were denied
         * @param permanentlyDenied   true if at least one was denied with "Don't ask again"
         *                            (send the user to App Settings)
         */
        void onDenied(@NonNull List<String> denied, boolean permanentlyDenied);

        /** Result of the "turn on Bluetooth" system dialog. */
        void onBluetoothEnabled(boolean enabled);
    }

    private final ComponentActivity activity;
    private final Callback callback;
    private final ActivityResultLauncher<String[]> permissionLauncher;
    private final ActivityResultLauncher<Intent> enableBtLauncher;

    /** Must be constructed during Activity initialisation (e.g. onCreate). */
    public BlePermissionHelper(@NonNull ComponentActivity activity, @NonNull Callback callback) {
        this.activity = activity;
        this.callback = callback;

        permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::handlePermissionResult);

        enableBtLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> callback.onBluetoothEnabled(isBluetoothEnabled(activity)));
    }

    // ------------------------------------------------------------------
    // Permission lists
    // ------------------------------------------------------------------

    /** Permissions required for the current Android version. */
    @NonNull
    public static String[] getRequiredPermissions() {
        List<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31)
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            // Android 6-11: BLE scanning requires location permission
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            perms.add(Manifest.permission.POST_NOTIFICATIONS); // foreground-service notification
        }

        return perms.toArray(new String[0]);
    }

    /** @return true if every required permission is granted. */
    public static boolean hasAllPermissions(@NonNull Context ctx) {
        return getMissingPermissions(ctx).isEmpty();
    }

    /** @return the required permissions that are not yet granted. */
    @NonNull
    public static List<String> getMissingPermissions(@NonNull Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String p : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing;
    }

    // ------------------------------------------------------------------
    // Requesting
    // ------------------------------------------------------------------

    /** Requests any missing permissions, or calls onAllGranted() if none are missing. */
    public void requestPermissions() {
        List<String> missing = getMissingPermissions(activity);
        if (missing.isEmpty()) {
            callback.onAllGranted();
        } else {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void handlePermissionResult(Map<String, Boolean> results) {
        List<String> denied = new ArrayList<>();
        boolean permanentlyDenied = false;

        for (Map.Entry<String, Boolean> e : results.entrySet()) {
            if (!e.getValue()) {
                denied.add(e.getKey());
                // After a denial, no rationale == user chose "Don't ask again"
                // (or the system will no longer show the prompt).
                if (!activity.shouldShowRequestPermissionRationale(e.getKey())) {
                    permanentlyDenied = true;
                }
            }
        }

        // POST_NOTIFICATIONS is optional for the mesh itself; don't block on it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            denied.remove(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (denied.isEmpty()) {
            callback.onAllGranted();
        } else {
            callback.onDenied(denied, permanentlyDenied);
        }
    }

    /** Sends the user to this app's system settings page (for permanently denied permissions). */
    public static void openAppSettings(@NonNull Context ctx) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", ctx.getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    // ------------------------------------------------------------------
    // Bluetooth / Location state
    // ------------------------------------------------------------------

    @androidx.annotation.Nullable
    public static BluetoothAdapter getAdapter(@NonNull Context ctx) {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    public static boolean isBluetoothEnabled(@NonNull Context ctx) {
        BluetoothAdapter adapter = getAdapter(ctx);
        return adapter != null && adapter.isEnabled();
    }

    /** Device has BLE hardware. */
    public static boolean hasBleHardware(@NonNull Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /**
     * Whether this device can act as a BLE peripheral (advertise).
     * Essential for a mesh: some older/cheaper phones can scan but not advertise.
     */
    public static boolean supportsAdvertising(@NonNull Context ctx) {
        BluetoothAdapter adapter = getAdapter(ctx);
        return adapter != null
                && adapter.isEnabled()
                && adapter.isMultipleAdvertisementSupported()
                && adapter.getBluetoothLeAdvertiser() != null;
    }

    /**
     * Location services (the system toggle, not the permission) must be ON for BLE
     * scans to return results on Android 6-11. Not required on 12+ with neverForLocation.
     */
    public static boolean isLocationServiceRequiredAndOff(@NonNull Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        return lm == null || !LocationManagerCompat.isLocationEnabled(lm);
    }

    public static void openLocationSettings(@NonNull Context ctx) {
        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    /**
     * Shows the system "Turn on Bluetooth?" dialog. Requires BLUETOOTH_CONNECT on Android 12+,
     * so call this only after permissions are granted.
     */
    @SuppressWarnings("MissingPermission")
    public void requestEnableBluetooth() {
        if (isBluetoothEnabled(activity)) {
            callback.onBluetoothEnabled(true);
        } else {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }
}