package com.example.offgrid;
import com.example.offgrid.mesh.MeshService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat screen for the OffGrid mesh.
 *
 * <p>Flow when the user taps "Start Mesh":
 * <ol>
 *   <li>{@link BlePermissionHelper#requestPermissions()} - runtime permissions</li>
 *   <li>{@link BlePermissionHelper#requestEnableBluetooth()} - Bluetooth on</li>
 *   <li>{@link MeshService#start(android.content.Context)} - foreground service starts and the
 *       already-registered binding connects to it</li>
 * </ol>
 *
 * <p>The Activity binds <em>without</em> BIND_AUTO_CREATE, so the binding never keeps the
 * service alive and the UI always mirrors the real service state - including when the user
 * taps "Stop" in the notification. Leaving the app does not stop the mesh.
 */
public class MainActivity extends AppCompatActivity {

    private static final int MAX_HISTORY = 500;

    /** Keeps the chat history across configuration changes (e.g. rotation). */
    public static class ChatViewModel extends ViewModel {
        final List<ChatAdapter.ChatEntry> entries = new ArrayList<>();
    }

    // Views
    private TextView statusText;
    private Button toggleButton;
    private Button sendButton;
    private EditText messageInput;
    private RecyclerView chatRecycler;
    private LinearLayoutManager layoutManager;

    private ChatAdapter adapter;
    private ChatViewModel chatViewModel;
    private BlePermissionHelper permissionHelper;

    // Service connection state (main thread only)
    private MeshService mesh;      // non-null == service is running and connected
    private boolean bound;

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);
        sendButton = findViewById(R.id.sendButton);
        messageInput = findViewById(R.id.messageInput);
        chatRecycler = findViewById(R.id.chatRecycler);

        applyWindowInsets();

        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        adapter = new ChatAdapter(chatViewModel.entries);
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        chatRecycler.setLayoutManager(layoutManager);
        chatRecycler.setAdapter(adapter);

        messageInput.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(MeshMessage.MAX_TEXT_LENGTH)});
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
        sendButton.setOnClickListener(v -> sendCurrentMessage());
        toggleButton.setOnClickListener(v -> onToggleClicked());

        // Must be created during onCreate (registers Activity Result launchers).
        permissionHelper = new BlePermissionHelper(this, permissionCallback);

        updateUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // flags = 0: connect if/when the service is running, never create or keep it alive.
        bound = bindService(new Intent(this, MeshService.class), serviceConnection, 0);
    }

    @Override
    protected void onStop() {
        if (mesh != null) {
            mesh.removeListener(meshListener);
            mesh = null;
        }
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
        updateUi();
        super.onStop();
    }

    // ------------------------------------------------------------------
    // Service binding
    // ------------------------------------------------------------------

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mesh = ((MeshService.LocalBinder) binder).getService();
            mesh.addListener(meshListener);
            updateUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Service stopped (Stop button, notification action, or process death).
            mesh = null;
            updateUi();
        }
    };

    private final MeshService.MeshListener meshListener = new MeshService.MeshListener() {
        @Override
        public void onMessageReceived(@NonNull MeshMessage message) {
            addEntry(new ChatAdapter.ChatEntry(message, false, System.currentTimeMillis()));
        }

        @Override
        public void onPeerCountChanged(int peerCount) {
            updateUi();
        }

        @Override
        public void onMeshError(@NonNull String description) {
            Toast.makeText(MainActivity.this, description, Toast.LENGTH_LONG).show();
            updateUi();
        }
    };

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    private void onToggleClicked() {
        if (mesh != null) {
            stopMeshService();
        } else {
            permissionHelper.requestPermissions(); // -> permissionCallback
        }
    }

    private final BlePermissionHelper.Callback permissionCallback =
            new BlePermissionHelper.Callback() {
                @Override
                public void onAllGranted() {
                    permissionHelper.requestEnableBluetooth(); // -> onBluetoothEnabled
                }

                @Override
                public void onDenied(@NonNull List<String> denied, boolean permanentlyDenied) {
                    showPermissionDeniedDialog(permanentlyDenied);
                }

                @Override
                public void onBluetoothEnabled(boolean enabled) {
                    if (enabled) {
                        startMeshService();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Bluetooth must be on to join the mesh", Toast.LENGTH_LONG).show();
                    }
                }
            };

    private void startMeshService() {
        MeshService.start(this);
        if (!bound) { // safety net if the binding from onStart() was refused
            bound = bindService(new Intent(this, MeshService.class), serviceConnection, 0);
        }
        statusText.setText("Starting…");
        promptLocationIfNeeded();
    }

    private void stopMeshService() {
        MeshService.stop(this);
        if (mesh != null) { // reflect immediately; onServiceDisconnected() may lag
            mesh.removeListener(meshListener);
            mesh = null;
        }
        updateUi();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private void sendCurrentMessage() {
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (mesh == null) {
            Toast.makeText(this, "Start the mesh first", Toast.LENGTH_SHORT).show();
            return;
        }
        MeshMessage sent = mesh.sendMessage(text);
        if (sent == null) {
            Toast.makeText(this, "Couldn't send that message", Toast.LENGTH_SHORT).show();
            return;
        }
        addEntry(new ChatAdapter.ChatEntry(sent, true, sent.getTimestamp()));
        messageInput.setText("");
    }

    // ------------------------------------------------------------------
    // Chat list
    // ------------------------------------------------------------------

    private void addEntry(ChatAdapter.ChatEntry entry) {
        List<ChatAdapter.ChatEntry> list = chatViewModel.entries;
        boolean wasNearBottom = isNearBottom();

        list.add(entry);
        adapter.notifyItemInserted(list.size() - 1);
        if (list.size() > MAX_HISTORY) {
            list.remove(0);
            adapter.notifyItemRemoved(0);
        }
        if (entry.outgoing || wasNearBottom) {
            chatRecycler.scrollToPosition(list.size() - 1);
        }
    }

    private boolean isNearBottom() {
        int count = adapter.getItemCount();
        return count == 0 || layoutManager.findLastVisibleItemPosition() >= count - 2;
    }

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    private void updateUi() {
        boolean running = mesh != null;
        toggleButton.setText(running ? "Stop Mesh" : "Start Mesh");
        sendButton.setEnabled(running);

        if (!running) {
            statusText.setText("Mesh stopped");
        } else {
            String state = mesh.isMeshActive() ? "Mesh active" : "Waiting for Bluetooth";
            statusText.setText(state + " · " + mesh.getPeerCount() + " nearby");
        }
    }

    // ------------------------------------------------------------------
    // Dialogs
    // ------------------------------------------------------------------

    private void showPermissionDeniedDialog(boolean permanentlyDenied) {
        String permissionName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? "Nearby devices" : "Location";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Permission needed")
                .setMessage("OffGrid needs the \"" + permissionName + "\" permission to find and "
                        + "talk to people around you without internet or cellular service.")
                .setNegativeButton(android.R.string.cancel, null);
        if (permanentlyDenied) {
            builder.setPositiveButton("Open settings",
                    (d, w) -> BlePermissionHelper.openAppSettings(this));
        } else {
            builder.setPositiveButton("Try again",
                    (d, w) -> permissionHelper.requestPermissions());
        }
        builder.show();
    }

    /** Android 6-11 returns no BLE scan results while the Location toggle is off. */
    private void promptLocationIfNeeded() {
        if (!BlePermissionHelper.isLocationServiceRequiredAndOff(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Turn on Location")
                .setMessage("On this Android version, Bluetooth scanning only works while "
                        + "Location services are on. OffGrid never reads or stores your location.")
                .setPositiveButton("Open settings",
                        (d, w) -> BlePermissionHelper.openLocationSettings(this))
                .setNegativeButton("Not now", null)
                .show();
    }

    // ------------------------------------------------------------------
    // Edge-to-edge insets (status bar, nav bar, keyboard)
    // ------------------------------------------------------------------

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }
}