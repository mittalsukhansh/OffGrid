package com.example.offgrid;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.offgrid.mesh.transport.WifiDirectTransport;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    public static class ChatViewModel extends ViewModel {
        final List<ChatAdapter.ChatEntry> entries = new ArrayList<>();

        @Nullable String recipientId;

        boolean listVisible = true;
        final java.util.Map<String, String> drafts = new java.util.HashMap<>();

        @Nullable String pendingMessageId;
        @Nullable String pendingText;
        @Nullable String mediaTarget;
        @Nullable String mediaMime;
    }

    private static final int COLOR_ACTIVE = 0xFF10B981;
    private static final int COLOR_WAITING = 0xFFF59E0B;
    private static final int COLOR_OFFLINE = 0xFF6B7280;
    private static final int COLOR_TEXT = 0xFFF3F4F6;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_STOP = 0xFFF87171;
    private static final int COLOR_ON_ACCENT = 0xFF04130E;
    private static final int COLOR_CHIP_SELECTED = 0xFF34D399;

    private TextView statusText;
    private Button toggleButton;
    private ImageButton sendButton;
    private Button recipientButton;
    private Button contactButton;
    private EditText messageInput;
    private RecyclerView chatRecycler;
    private LinearLayoutManager layoutManager;
    private View statusDot;
    private View statusGlow;
    private TextView nodeIdBadge;
    private TextView eventPill;
    private LinearLayout targetChips;
    private ObjectAnimator glowAnimator;
    private final Runnable hideEvent =
            () ->
                    eventPill
                            .animate()
                            .alpha(0f)
                            .setDuration(220)
                            .withEndAction(() -> eventPill.setVisibility(View.GONE))
                            .start();

    private ChatAdapter adapter;
    private ChatViewModel chatViewModel;
    private BlePermissionHelper permissionHelper;
    private int historyVersion;
    private boolean resumed;
    private ListView conversationList;
    private TextView conversationHeading;
    private Button conversationsButton;
    private final List<ConversationSummary> conversations = new ArrayList<>();
    private LiveData<List<ChatRecord>> conversationHistory;
    private ActivityResultLauncher<ScanOptions> contactScanner;
    private ActivityResultLauncher<String> mediaPicker;
    private ActivityResultLauncher<String> diagnosticsExport;
    private ActivityResultLauncher<String> localNetworkPermission;
    private ActivityResultLauncher<String> notificationPermission;
    private ActivityResultLauncher<String[]> directPermissions;
    private final java.util.concurrent.ExecutorService mediaWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private IdentityBackupUi identityBackup;
    private MeshService mesh;
    private boolean bound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_OffGrid);
        super.onCreate(savedInstanceState);
        identityBackup = new IdentityBackupUi(this, MeshService::identityRestoreSafe);
        diagnosticsExport =
                registerForActivityResult(
                        new ActivityResultContracts.CreateDocument("text/plain"),
                        uri -> {
                            if (uri == null) return;
                            MeshService current = mesh;
                            if (current == null) {
                                Toast.makeText(
                                                this,
                                                "Start mesh before exporting measurements",
                                                Toast.LENGTH_LONG)
                                        .show();
                                return;
                            }
                            current.exportMeasurements(report ->
                                    mediaWorker.execute(() -> {
                                        try (java.io.OutputStream out =
                                                getContentResolver().openOutputStream(uri, "wt")) {
                                            if (out == null)
                                                throw new java.io.IOException("No destination");
                                            out.write(
                                                    report.getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                                            runOnUiThread(() ->
                                                    Toast.makeText(
                                                                    this,
                                                                    "Measurements exported",
                                                                    Toast.LENGTH_SHORT)
                                                            .show());
                                        } catch (Exception failure) {
                                            runOnUiThread(() ->
                                                    Toast.makeText(
                                                                    this,
                                                                    "Export failed; choose another"
                                                                            + " destination",
                                                                    Toast.LENGTH_LONG)
                                                            .show());
                                        }
                                    }));
                        });
        directPermissions =
                registerForActivityResult(
                        new ActivityResultContracts.RequestMultiplePermissions(),
                        results -> {
                            boolean allowed = true;
                            for (String permission : WifiDirectTransport.permissions())
                                allowed &=
                                        androidx.core.content.ContextCompat.checkSelfPermission(
                                                        this, permission)
                                                == android.content.pm.PackageManager
                                                        .PERMISSION_GRANTED;
                            if (allowed) {
                                setDirectEnabled(true);
                                showWifiDirectDialog();
                            } else
                                new AlertDialog.Builder(this)
                                        .setTitle("Wi-Fi Direct access needed")
                                        .setMessage(
                                                "Allow Nearby Wi-Fi devices on Android 13+, or"
                                                        + " discovery Location permission on older"
                                                        + " Android. Android 17 also needs"
                                                        + " local-network access. Bluetooth and"
                                                        + " same-LAN sharing are separate options.")
                                        .setPositiveButton(
                                                "App settings",
                                                (d, w) -> BlePermissionHelper.openAppSettings(this))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                        });
        notificationPermission =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        allowed -> startMeshService());
        localNetworkPermission =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        allowed -> {
                            if (allowed) setWifiEnabled(true);
                            else
                                new AlertDialog.Builder(this)
                                        .setTitle("Local-network access needed")
                                        .setMessage(
                                                "Allow local-network access in Android app settings"
                                                    + " to discover Wi-Fi peers. Bluetooth remains"
                                                    + " available.")
                                        .setPositiveButton(
                                                "App settings",
                                                (d, w) -> BlePermissionHelper.openAppSettings(this))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                        });
        mediaPicker =
                registerForActivityResult(
                        new ActivityResultContracts.GetContent(), this::prepareAttachment);
        contactScanner =
                registerForActivityResult(
                        new ScanContract(),
                        result -> {
                            if (result.getContents() == null) return;
                            String id = crypto().importContact(result.getContents());
                            if (id == null) {
                                Toast.makeText(
                                                this,
                                                "This QR is not a valid OffGrid contact",
                                                Toast.LENGTH_LONG)
                                        .show();
                                return;
                            }
                            setRecipient(id);
                            showVerificationDialog(id);
                        });
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        statusGlow = findViewById(R.id.statusGlow);
        nodeIdBadge = findViewById(R.id.nodeIdBadge);
        eventPill = findViewById(R.id.eventPill);
        targetChips = findViewById(R.id.targetChips);
        toggleButton = findViewById(R.id.toggleButton);
        sendButton = findViewById(R.id.sendButton);
        recipientButton = findViewById(R.id.recipientButton);
        contactButton = findViewById(R.id.contactButton);
        messageInput = findViewById(R.id.messageInput);
        chatRecycler = findViewById(R.id.chatRecycler);

        conversationList = findViewById(R.id.conversationList);
        conversationHeading = findViewById(R.id.conversationHeading);
        conversationsButton = findViewById(R.id.conversationsButton);
        conversationsButton.setOnClickListener(v -> showConversations());
        conversationList.setOnItemClickListener((parent, view, position, id) -> {
            String conversation = conversations.get(position).conversationId;
            setRecipient(conversation.equals("public") ? null : conversation);
        });
        findViewById(R.id.diagnosticsButton).setOnClickListener(v -> showDiagnostics());
        nodeIdBadge.setOnClickListener(v -> copyNodeId());
        getOnBackPressedDispatcher()
                .addCallback(
                        this,
                        new androidx.activity.OnBackPressedCallback(true) {
                            @Override
                            public void handleOnBackPressed() {
                                if (!chatViewModel.listVisible) showConversations();
                                else {
                                    setEnabled(false);
                                    getOnBackPressedDispatcher().onBackPressed();
                                }
                            }
                        });
        applyWindowInsets();

        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        adapter = new ChatAdapter(chatViewModel.entries);
        adapter.interaction(
                new ChatAdapter.Interaction() {
                    @Override
                    public void open(ChatAdapter.ChatEntry entry) {
                        openAttachment(entry);
                    }

                    @Override
                    public void details(ChatAdapter.ChatEntry entry) {
                        showMessageDetails(entry);
                    }

                    @Override
                    public boolean longPress(ChatAdapter.ChatEntry entry) {
                        if (!entry.outgoing
                                || !entry.message.isPrivate()
                                || entry.status.equals("UNSENT")) return false;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Unsend private message?")
                                .setMessage(
                                        "Removes your app copy and requests removal on the"
                                            + " contact's phone. They must reconnect for the"
                                            + " request to arrive. Screenshots and exported copies"
                                            + " cannot be recalled.")
                                .setPositiveButton(
                                        "Request unsend",
                                        (d, w) -> {
                                            if (mesh != null)
                                                mesh.unsend(entry.message.getMessageId());
                                            else
                                                mediaWorker.execute(() -> {
                                                    try {
                                                        new PrivateActions(
                                                                        MainActivity.this,
                                                                        MessageStore.get(
                                                                                MainActivity.this),
                                                                        crypto())
                                                                .unsend(
                                                                        entry.message
                                                                                .getMessageId(),
                                                                        System.currentTimeMillis());
                                                        runOnUiThread(() -> {
                                                            if (!isDestroyed())
                                                                Toast.makeText(
                                                                                MainActivity.this,
                                                                                "Unsend saved."
                                                                                    + " Start the"
                                                                                    + " mesh within"
                                                                                    + " 10 minutes"
                                                                                    + " to attempt"
                                                                                    + " delivery.",
                                                                                Toast.LENGTH_LONG)
                                                                        .show();
                                                        });
                                                    } catch (Exception e) {
                                                        runOnUiThread(() -> {
                                                            if (!isDestroyed())
                                                                Toast.makeText(
                                                                                MainActivity.this,
                                                                                e.getMessage(),
                                                                                Toast.LENGTH_LONG)
                                                                        .show();
                                                        });
                                                    }
                                                });
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return true;
                    }
                });
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        chatRecycler.setLayoutManager(layoutManager);
        chatRecycler.setAdapter(adapter);
        MessageStore store = MessageStore.get(this);
        store.outgoingSaved().observe(this, this::outgoingCommitted);
        store.outgoingFailures()
                .observe(
                        this,
                        messageId -> {
                            if (messageId.equals(chatViewModel.pendingMessageId)) {
                                chatViewModel.pendingMessageId = null;
                                chatViewModel.pendingText = null;
                                updateUi();
                            }
                        });
        store.dao()
                .observeConversations()
                .observe(
                        this,
                        rows -> {
                            conversations.clear();
                            conversations.addAll(rows);
                            updateConversationList();
                        });
        observeSelectedConversation();

        messageInput.setFilters(
                new InputFilter[] {new InputFilter.LengthFilter(MeshMessage.MAX_TEXT_LENGTH)});
        messageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
        sendButton.setOnClickListener(v -> {
            pulse(v);
            sendCurrentMessage();
        });
        findViewById(R.id.attachButton).setOnClickListener(v -> chooseAttachment());
        toggleButton.setOnClickListener(v -> {
            pulse(v);
            onToggleClicked();
        });
        recipientButton.setOnClickListener(v -> showRecipientDialog());
        contactButton.setOnClickListener(v -> showMyContactDialog());

        permissionHelper = new BlePermissionHelper(this, permissionCallback);

        if (savedInstanceState != null) {
            chatViewModel.recipientId = savedInstanceState.getString("conversation");
            chatViewModel.listVisible = savedInstanceState.getBoolean("listVisible", true);
            observeSelectedConversation();
        }
        openNotification(getIntent());
        bindNodeBadge();
        updateRecipientButton();
        updateConversationVisibility();
        updateUi();
        if (savedInstanceState == null && crypto().displayName().isEmpty())
            showDisplayNameDialog(null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("conversation", chatViewModel.recipientId);
        state.putBoolean("listVisible", chatViewModel.listVisible);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openNotification(intent);
    }

    private void openNotification(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra("conversation");
        if (id != null && (id.equals("public") || id.matches("[0-9a-f]{32}")))
            setRecipient(id.equals("public") ? null : id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        updateUi();
        refreshTargetChips();
        updateConversationVisibility();
        markDisplayedRead();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopGlow();
        MessageStore.get(this).visibleConversation(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopGlow();
        eventPill.removeCallbacks(hideEvent);
        identityBackup.close();
        mediaWorker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MessageStore.get(this)
                .refreshExpiration(() -> {
                    if (!isDestroyed())
                        Toast.makeText(
                                        this,
                                        "Could not refresh saved message status",
                                        Toast.LENGTH_LONG)
                                .show();
                });
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

    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    mesh = ((MeshService.LocalBinder) binder).getService();
                    mesh.addListener(meshListener);
                    updateUi();
                    showEvent("Mesh online · " + mesh.getPeerCount() + " in range");
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mesh = null;
                    updateUi();
                    showEvent("Mesh offline");
                }
            };

    private final MeshService.MeshListener meshListener =
            new MeshService.MeshListener() {
                @Override
                public void onMessageReceived(@NonNull MeshMessage message) {}

                @Override
                public void onPeerCountChanged(int peerCount) {
                    updateUi();
                    showEvent(peerCount == 1 ? "1 node in range" : peerCount + " nodes in range");
                }

                @Override
                public void onMeshError(@NonNull String description) {
                    Toast.makeText(MainActivity.this, description, Toast.LENGTH_LONG).show();
                    updateUi();
                    showEvent(description);
                }
            };

    private void onToggleClicked() {
        if (mesh != null) {
            stopMeshService();
        } else if (MeshService.hasWifiTransportEnabled(this)) {
            if (Build.VERSION.SDK_INT >= 33
                    && androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            else startMeshService();
        } else {
            permissionHelper.requestPermissions();
        }
    }

    private final BlePermissionHelper.Callback permissionCallback =
            new BlePermissionHelper.Callback() {
                @Override
                public void onAllGranted() {
                    if (MeshService.hasWifiTransportEnabled(MainActivity.this)) startMeshService();
                    else permissionHelper.requestEnableBluetooth();
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
                        Toast.makeText(
                                        MainActivity.this,
                                        "Bluetooth must be on to join the mesh",
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                }
            };

    private void startMeshService() {
        if (IdentityBackupUi.busy()) {
            Toast.makeText(this, "Wait for identity backup to finish", Toast.LENGTH_LONG).show();
            return;
        }
        MeshService.start(this);
        if (!bound) {
            bound = bindService(new Intent(this, MeshService.class), serviceConnection, 0);
        }
        statusText.setText("Starting…");
        applyStatus(COLOR_WAITING, COLOR_WAITING, false);
        promptLocationIfNeeded();
    }

    private void stopMeshService() {
        MeshService.stop(this);
        if (mesh != null) {
            mesh.removeListener(meshListener);
            mesh = null;
        }
        updateUi();
        showEvent("Mesh stopped");
    }

    private void sendCurrentMessage() {
        if (chatViewModel.pendingMessageId != null) return;
        String text = messageInput.getText().toString().trim();
        if (text.isEmpty()) return;
        if (mesh == null) {
            Toast.makeText(this, "Start the mesh first", Toast.LENGTH_SHORT).show();
            return;
        }

        String recipient = chatViewModel.recipientId;
        MeshMessage sent;
        if (recipient == null) {
            sent = mesh.sendMessage(text);
            if (sent == null) {
                Toast.makeText(this, "Couldn't send that message", Toast.LENGTH_SHORT).show();
                return;
            }
        } else {
            MeshService.SendResult result = mesh.sendPrivateMessage(recipient, text);
            if (result.status != MeshService.SendStatus.ACCEPTED || result.message == null) {
                Toast.makeText(this, describeFailure(result.status), Toast.LENGTH_LONG).show();
                return;
            }
            sent = result.message;
        }
        chatViewModel.pendingMessageId = sent.getMessageId();
        chatViewModel.pendingText = text;
        updateUi();
    }

    private void outgoingCommitted(String id) {
        if (!id.equals(chatViewModel.pendingMessageId)) return;
        if (messageInput.getText().toString().trim().equals(chatViewModel.pendingText))
            messageInput.setText("");
        chatViewModel.pendingMessageId = null;
        chatViewModel.pendingText = null;
        updateUi();
    }

    private static String describeFailure(MeshService.SendStatus status) {
        switch (status) {
            case RECIPIENT_KEY_UNKNOWN:
                return "No key for that contact yet. Import their contact, or wait until "
                        + "their phone is heard on the mesh.";
            case ENCRYPTION_FAILED:
                return "Encryption failed; message not sent";
            default:
                return "Couldn't send that message";
        }
    }

    private CryptoManager crypto() {
        return CryptoManager.getInstance(this);
    }

    private void setRecipient(@Nullable String id) {
        if (chatViewModel.pendingMessageId != null) {
            Toast.makeText(this, "Wait for the message to be saved", Toast.LENGTH_SHORT).show();
            return;
        }
        chatViewModel.drafts.put(conversationId(), messageInput.getText().toString());
        chatViewModel.recipientId = id;
        chatViewModel.listVisible = false;
        messageInput.setText(chatViewModel.drafts.getOrDefault(conversationId(), ""));
        updateRecipientButton();
        observeSelectedConversation();
        updateConversationVisibility();
    }

    private String conversationId() {
        return chatViewModel.recipientId == null ? "public" : chatViewModel.recipientId;
    }

    private void showConversations() {
        if (chatViewModel.pendingMessageId != null) return;
        chatViewModel.listVisible = true;
        updateConversationVisibility();
    }

    private void updateConversationVisibility() {
        boolean list = chatViewModel.listVisible;
        conversationList.setVisibility(list ? View.VISIBLE : View.GONE);
        chatRecycler.setVisibility(list ? View.GONE : View.VISIBLE);
        findViewById(R.id.composerRow).setVisibility(list ? View.GONE : View.VISIBLE);
        conversationHeading.setText(
                list
                        ? "Conversations · start a chat with the contact selector below"
                        : (chatViewModel.recipientId == null
                                ? "Everyone · public broadcast"
                                : crypto().contactLabel(chatViewModel.recipientId)));
        MessageStore.get(this).visibleConversation(resumed && !list ? conversationId() : null);
    }

    private void updateConversationList() {
        List<CharSequence> labels = new ArrayList<>();
        int unread = 0;
        for (ConversationSummary item : conversations) {
            String label =
                    item.conversationId.equals("public")
                            ? "Everyone · public"
                            : crypto().contactLabel(item.conversationId);
            String text =
                    label
                            + (item.unread > 0 ? "  ·  " + item.unread + " unread" : "")
                            + "\n"
                            + android.text.format.DateFormat.getMediumDateFormat(this)
                                    .format(new java.util.Date(item.lastTime))
                            + " "
                            + android.text.format.DateFormat.getTimeFormat(this)
                                    .format(new java.util.Date(item.lastTime));
            SpannableString styled = new SpannableString(text);
            int split = text.lastIndexOf('\n');
            if (split > 0) {
                styled.setSpan(
                        new StyleSpan(Typeface.BOLD), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(
                        new ForegroundColorSpan(COLOR_MUTED),
                        split + 1,
                        text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(
                        new RelativeSizeSpan(0.8f),
                        split + 1,
                        text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            labels.add(styled);
            unread += item.unread;
        }
        conversationList.setAdapter(
                new ArrayAdapter<>(
                        this, R.layout.item_conversation, R.id.conversationLabel, labels));
        conversationsButton.setText("Conversations" + (unread > 0 ? " (" + unread + ")" : ""));
    }

    private void observeSelectedConversation() {
        MessageStore store = MessageStore.get(this);
        if (conversationHistory != null) conversationHistory.removeObservers(this);
        ++historyVersion;
        chatViewModel.entries.clear();
        adapter.notifyDataSetChanged();
        conversationHistory = store.dao().observeConversation(conversationId());
        String selected = conversationId();
        conversationHistory.observe(
                this,
                rows -> {
                    int version = ++historyVersion;
                    store.loadHistory(
                            rows,
                            (entries, unreadable) -> {
                                if (isDestroyed()
                                        || version != historyVersion
                                        || !selected.equals(conversationId())) return;
                                showStoredHistory(entries, unreadable);
                            });
                });
    }

    private void markDisplayedRead() {
        if (!resumed || chatViewModel.listVisible) return;
        List<String> ids = new ArrayList<>();
        for (ChatAdapter.ChatEntry entry : chatViewModel.entries)
            if (!entry.outgoing) ids.add(entry.message.getMessageId());
        MessageStore.get(this).markRead(conversationId(), ids);
        ChatNotifications.cancel(this, conversationId());
    }

    private void updateRecipientButton() {
        String id = chatViewModel.recipientId;
        recipientButton.setText(
                id == null
                        ? "To: Everyone"
                        : "To: \uD83D\uDD12 "
                                + crypto().contactLabel(id)
                                + (crypto().isVerified(id) ? " · ✓ Verified" : " · Unverified"));
        refreshTargetChips();
    }

    private void refreshTargetChips() {
        if (targetChips == null || chatViewModel == null) return;
        targetChips.removeAllViews();
        String selected = chatViewModel.recipientId;
        targetChips.addView(buildChip("Broadcast", selected == null, () -> setRecipient(null)));
        int shown = 0;
        for (CryptoManager.Contact c : crypto().getContacts()) {
            if (shown >= 12 && !c.id.equals(selected)) continue;
            shown++;
            final String contactId = c.id;
            targetChips.addView(
                    buildChip(
                            (c.verified ? "✓ " : "") + crypto().contactLabel(contactId),
                            contactId.equals(selected),
                            () -> setRecipient(contactId)));
        }
        targetChips.addView(buildChip("+ Add", false, this::showAddContactDialog));
    }

    private TextView buildChip(String label, boolean selected, Runnable action) {
        float density = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth((int) (150 * density));
        chip.setTextSize(12);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setSelected(selected);
        chip.setTextColor(selected ? COLOR_CHIP_SELECTED : COLOR_MUTED);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, (int) (48 * density));
        params.setMarginEnd((int) (8 * density));
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private void showRecipientDialog() {
        final List<CryptoManager.Contact> contacts = crypto().getContacts();
        final List<CharSequence> labels = new ArrayList<>();
        labels.add("Everyone (public)");
        for (CryptoManager.Contact c : contacts) {
            labels.add(
                    "\uD83D\uDD12 "
                            + crypto().contactLabel(c.id)
                            + (c.verified ? "   ✓ Verified" : "   Unverified")
                            + (c.addedManually ? "" : "   (heard on mesh)"));
        }
        labels.add("+ Add contact…");

        new AlertDialog.Builder(this)
                .setTitle("Send to")
                .setItems(
                        labels.toArray(new CharSequence[0]),
                        (dialog, which) -> {
                            if (which == 0) {
                                setRecipient(null);
                            } else if (which == labels.size() - 1) {
                                showAddContactDialog();
                            } else {
                                setRecipient(contacts.get(which - 1).id);
                            }
                        })
                .setNeutralButton(
                        chatViewModel.recipientId == null ? "Scan QR" : "Manage selected",
                        (d, w) -> {
                            if (chatViewModel.recipientId == null) scanContact();
                            else showContactActions(chatViewModel.recipientId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAddContactDialog() {
        final EditText input = new EditText(this);
        input.setHint(CryptoManager.CONTACT_PREFIX + "…");
        input.setMaxLines(4);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(640)});

        String clip = readClipboardText();
        if (clip != null
                && clip.trim().toLowerCase(Locale.ROOT).startsWith(CryptoManager.CONTACT_PREFIX)) {
            input.setText(clip.trim());
        }

        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(
                input,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Add contact")
                .setMessage(
                        "Paste the contact string from the other person's \"My contact\" "
                                + "screen.")
                .setView(container)
                .setPositiveButton(
                        "Add",
                        (dialog, which) -> {
                            String id = crypto().importContact(input.getText().toString());
                            if (id == null) {
                                Toast.makeText(
                                                this,
                                                "That isn't a valid OffGrid contact",
                                                Toast.LENGTH_LONG)
                                        .show();
                            } else {
                                Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show();
                                setRecipient(id);
                            }
                        })
                .setNeutralButton("Scan QR", (d, w) -> scanContact())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showContactActions(String id) {
        new AlertDialog.Builder(this)
                .setTitle(crypto().contactLabel(id))
                .setItems(
                        new String[] {"Edit local nickname", "Verify fingerprint"},
                        (d, which) -> {
                            if (which == 0) showNicknameDialog(id);
                            else showVerificationDialog(id);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showNicknameDialog(String id) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(crypto().nickname(id));
        input.setHint("Local nickname (optional)");
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        new AlertDialog.Builder(this)
                .setTitle("Edit nickname")
                .setMessage(
                        "Only stored on this phone. The contact ID and verification stay the same.")
                .setView(input)
                .setPositiveButton(
                        "Save",
                        (d, w) -> {
                            String name = input.getText().toString();
                            Runnable save =
                                    () -> {
                                        crypto().setNickname(id, name);
                                        updateRecipientButton();
                                        updateConversationList();
                                        updateConversationVisibility();
                                        adapter.notifyDataSetChanged();
                                    };
                            if (crypto().nicknameInUse(id, name)) {
                                new AlertDialog.Builder(this)
                                        .setTitle("Nickname already used")
                                        .setMessage(
                                                "Another contact has this nickname. They remain"
                                                        + " separate contacts; check their IDs.")
                                        .setPositiveButton(
                                                "Use anyway", (dialog, which) -> save.run())
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            } else save.run();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseAttachment() {
        if (mesh == null || chatViewModel.recipientId == null) {
            Toast.makeText(
                            this,
                            "Start the mesh and choose a private contact first",
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Send attachment")
                .setItems(
                        new String[] {
                            "Photo (compressed, up to 48 KB)", "MP4 clip (up to 256 KB, 10 seconds)"
                        },
                        (d, which) -> {
                            chatViewModel.mediaTarget = chatViewModel.recipientId;
                            chatViewModel.mediaMime = which == 0 ? "image/jpeg" : "video/mp4";
                            mediaPicker.launch(which == 0 ? "image/*" : "video/mp4");
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void prepareAttachment(Uri uri) {
        if (uri == null) return;
        String target = chatViewModel.mediaTarget, mime = chatViewModel.mediaMime;
        if (target == null || mime == null) return;
        Toast.makeText(this, "Preparing attachment…", Toast.LENGTH_SHORT).show();
        mediaWorker.execute(() -> {
            try {
                byte[] bytes =
                        mime.equals("image/jpeg")
                                ? AttachmentPreparer.image(this, uri)
                                : AttachmentPreparer.video(this, uri);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (mesh == null)
                        Toast.makeText(
                                        this,
                                        "Mesh stopped. Start it and select the attachment again.",
                                        Toast.LENGTH_LONG)
                                .show();
                    else mesh.sendMedia(target, bytes, mime);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed())
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showMessageDetails(ChatAdapter.ChatEntry entry) {
        String id = entry.message.getMessageId();
        java.util.function.Consumer<String> show =
                text -> {
                    if (mediaWorker.isShutdown()) return;
                    try {
                        mediaWorker.execute(() -> {
                            MessageStore store = MessageStore.get(this);
                            PrivateActions actions = new PrivateActions(this, store, crypto());
                            java.util.List<PrivateActions.TransferCommand> commands =
                                    actions.transferCommands(id);
                            boolean media = store.dao().media(id) != null;
                            runOnUiThread(() -> {
                                if (!isDestroyed() && !isFinishing())
                                    MessageDetailsDialog.show(
                                            this,
                                            text,
                                            media,
                                            commands,
                                            command -> transferCommand(id, command));
                            });
                        });
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // Activity was destroyed while the worker snapshot was in flight.
                    }
                };
        if (mesh != null) mesh.messageDetails(id, show);
        else
            mediaWorker.execute(() ->
                    show.accept(
                            new PrivateActions(this, MessageStore.get(this), crypto())
                                            .deliveryDetails(id)
                                    + "\n\nMesh stopped. Start it to attempt delivery."));
    }

    private void transferCommand(String id, PrivateActions.TransferCommand command) {
        java.util.function.Consumer<String> result =
                text ->
                        runOnUiThread(() -> {
                            if (!isDestroyed())
                                Toast.makeText(this, text, Toast.LENGTH_LONG).show();
                        });
        if (mesh != null) mesh.transferCommand(id, command, result);
        else
            mediaWorker.execute(() -> {
                try {
                    if (!MeshService.identityRestoreSafe())
                        throw new IllegalStateException(
                                "Mesh worker is reconnecting. Try again after it stops or"
                                    + " reconnects.");
                    new PrivateActions(this, MessageStore.get(this), crypto())
                            .transferCommand(id, command, System.currentTimeMillis());
                    result.accept("Saved locally. Start mesh to continue sending.");
                } catch (Exception e) {
                    result.accept("Could not update transfer: " + e.getMessage());
                }
            });
    }

    private void openAttachment(ChatAdapter.ChatEntry entry) {
        if (entry.kind.equals("text")) return;
        mediaWorker.execute(() -> {
            try {
                MessageStore store = MessageStore.get(this);
                MediaRecord record = store.dao().media(entry.message.getMessageId());
                if (record == null)
                    throw new IllegalArgumentException("Attachment is not available");
                byte[] bytes = new PrivateActions(this, store, crypto()).attachment(record.id);
                Uri uri = AttachmentFiles.prepare(this, record.id, bytes, record.mime);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    Intent view =
                            new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, record.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    view.setClipData(ClipData.newRawUri("OffGrid attachment", uri));
                    try {
                        startActivity(Intent.createChooser(view, "Open attachment"));
                    } catch (android.content.ActivityNotFoundException e) {
                        Toast.makeText(this, "No app can open this attachment", Toast.LENGTH_LONG)
                                .show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!isDestroyed())
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void scanContact() {
        if (!getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(
                            this,
                            "No camera available. Paste a contact and compare fingerprints"
                                    + " instead.",
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }
        contactScanner.launch(
                new ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the contact QR on the other person's phone")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false));
    }

    private void showVerificationDialog(String id) {
        String fingerprint = crypto().contactFingerprint(id);
        if (fingerprint == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Verify contact")
                .setMessage(
                        crypto().contactLabel(id)
                                + "\n\n"
                                + "Compare this full fingerprint with the one shown in My contact"
                                + " on the other person's phone, in person or over a trusted"
                                + " channel. Scanning or importing alone does not verify who owns"
                                + " the contact.\n\n"
                                + CryptoManager.formatId(fingerprint, 64))
                .setPositiveButton(
                        "Fingerprints match",
                        (d, w) -> {
                            if (crypto().setVerified(id, fingerprint, true)) {
                                updateRecipientButton();
                                adapter.notifyDataSetChanged();
                            }
                        })
                .setNeutralButton(
                        "Remove verification",
                        (d, w) -> {
                            crypto().setVerified(id, fingerprint, false);
                            updateRecipientButton();
                            adapter.notifyDataSetChanged();
                        })
                .setNegativeButton("Not verified", null)
                .show();
    }

    private void showDisplayNameDialog(@Nullable Runnable afterSave) {
        EditText input = new EditText(this);
        input.setHint("Your name");
        input.setSingleLine(true);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        input.setText(crypto().displayName());
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("Choose your OffGrid name")
                        .setMessage(
                                "Shared in your contact QR. Names can repeat; your fingerprint"
                                        + " identifies you.")
                        .setView(input)
                        .setPositiveButton("Save name", null)
                        .setNegativeButton("Later", null)
                        .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(v -> {
                            try {
                                crypto().setDisplayName(input.getText().toString());
                                android.view.inputmethod.InputMethodManager keyboard =
                                        (android.view.inputmethod.InputMethodManager)
                                                getSystemService(INPUT_METHOD_SERVICE);
                                if (keyboard != null)
                                    keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
                                dialog.dismiss();
                                updateRecipientButton();
                                updateConversationList();
                                if (afterSave != null) afterSave.run();
                            } catch (IllegalArgumentException | IllegalStateException e) {
                                input.setError(e.getMessage());
                            }
                        }));
        dialog.show();
    }

    private void showMyContactDialog() {
        final CryptoManager cm = crypto();
        if (cm.displayName().isEmpty()) {
            showDisplayNameDialog(this::showMyContactDialog);
            return;
        }
        ImageView qr = new ImageView(this);
        int size = (int) (240 * getResources().getDisplayMetrics().density);
        qr.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        qr.setContentDescription("Your OffGrid contact QR code");
        try {
            qr.setImageBitmap(ContactQr.bitmap(cm.getContactString(), 512));
        } catch (com.google.zxing.WriterException e) {
            Toast.makeText(this, "Could not create contact QR", Toast.LENGTH_LONG).show();
        }
        LinearLayout qrContainer = new LinearLayout(this);
        qrContainer.setGravity(android.view.Gravity.CENTER);
        qrContainer.setOrientation(LinearLayout.VERTICAL);
        qrContainer.addView(qr);
        Button backup = new Button(this);
        backup.setText("Identity backup / restore");
        backup.setOnClickListener(v -> {
            identityBackup.show();
        });
        qrContainer.addView(backup);
        Button editName = new Button(this);
        editName.setText("Edit my name");
        qrContainer.addView(editName);
        TextView details = new TextView(this);
        details.setText(
                "Name: "
                        + cm.displayName()
                        + "\n\nYour ID:\n"
                        + CryptoManager.formatId(cm.getLocalId(), 32)
                        + "\n\nFull fingerprint:\n"
                        + CryptoManager.formatId(cm.getOwnFingerprint(), 64)
                        + "\n\n"
                        + "Share your contact with people who should be able to"
                        + " message you privately. Confirm the ID with them in"
                        + " person or on another channel so you know the contact is"
                        + " really theirs.");
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        details.setPadding(padding, padding, padding, padding);
        qrContainer.addView(details, 0);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(qrContainer);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("My contact")
                        .setView(scroll)
                        .setPositiveButton(
                                "Share",
                                (d, w) -> {
                                    Intent send =
                                            new Intent(Intent.ACTION_SEND)
                                                    .setType("text/plain")
                                                    .putExtra(
                                                            Intent.EXTRA_TEXT,
                                                            cm.getContactString());
                                    startActivity(Intent.createChooser(send, "Share contact"));
                                })
                        .setNeutralButton(
                                "Copy",
                                (d, w) -> {
                                    ClipboardManager clipboard =
                                            (ClipboardManager)
                                                    getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(
                                                ClipData.newPlainText(
                                                        "OffGrid contact", cm.getContactString()));
                                        Toast.makeText(this, "Contact copied", Toast.LENGTH_SHORT)
                                                .show();
                                    }
                                })
                        .setNegativeButton("Close", null)
                        .create();
        editName.setOnClickListener(v -> {
            dialog.dismiss();
            showDisplayNameDialog(this::showMyContactDialog);
        });
        dialog.show();
    }

    @Nullable
    private String readClipboardText() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return null;
            CharSequence text = data.getItemAt(0).coerceToText(this);
            return text == null ? null : text.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void showStoredHistory(List<ChatAdapter.ChatEntry> entries, boolean unreadable) {
        boolean nearBottom = isNearBottom();
        chatViewModel.entries.clear();
        chatViewModel.entries.addAll(entries);
        adapter.notifyDataSetChanged();
        markDisplayedRead();
        for (ChatAdapter.ChatEntry entry : entries) {
            if (entry.message.getMessageId().equals(chatViewModel.pendingMessageId)) {
                if (messageInput.getText().toString().trim().equals(chatViewModel.pendingText)) {
                    messageInput.setText("");
                }
                chatViewModel.pendingMessageId = null;
                chatViewModel.pendingText = null;
                updateUi();
            }
        }
        if (nearBottom && !entries.isEmpty()) chatRecycler.scrollToPosition(entries.size() - 1);
        if (unreadable)
            Toast.makeText(
                            this,
                            "Some saved messages could not be decrypted. Original data was kept.",
                            Toast.LENGTH_LONG)
                    .show();
    }

    private boolean isNearBottom() {
        int count = adapter.getItemCount();
        return count == 0 || layoutManager.findLastVisibleItemPosition() >= count - 2;
    }

    private void setWifiEnabled(boolean enabled) {
        getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .edit()
                .putBoolean("wifi", enabled)
                .apply();
        if (mesh != null) mesh.setWifiEnabled(enabled);
        else
            Toast.makeText(
                            this,
                            enabled
                                    ? "Wi-Fi enabled. Start the mesh to discover peers."
                                    : "Wi-Fi disabled",
                            Toast.LENGTH_LONG)
                    .show();
    }

    private void setDirectEnabled(boolean enabled) {
        getSharedPreferences("offgrid-network", MODE_PRIVATE)
                .edit()
                .putBoolean("direct", enabled)
                .apply();
        if (mesh != null) mesh.setDirectEnabled(enabled);
        else
            Toast.makeText(
                            this,
                            enabled
                                    ? "Wi-Fi Direct enabled. Start the mesh, then find nearby"
                                            + " phones."
                                    : "Wi-Fi Direct disabled",
                            Toast.LENGTH_LONG)
                    .show();
    }

    private void showWifiDirectDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad / 2, pad, 0);
        TextView state = new TextView(this);
        state.setText(
                "No router or internet required. Enable on both phones, start both meshes, find a"
                    + " phone and accept Android's pairing prompt. Device names are not verified"
                    + " contact identities.");
        androidx.appcompat.widget.SwitchCompat enable =
                new androidx.appcompat.widget.SwitchCompat(this);
        enable.setText("Enable Wi-Fi Direct");
        enable.setChecked(MeshService.directEnabled(this));
        enable.setEnabled(WifiDirectTransport.supported(this));
        Button find = new Button(this);
        find.setText("Find nearby OffGrid phones");
        Button leave = new Button(this);
        leave.setText("Disconnect OffGrid group");
        leave.setEnabled(false);
        ListView list = new ListView(this);
        List<WifiDirectTransport.Peer> peers = new ArrayList<>();
        panel.addView(state);
        panel.addView(enable);
        panel.addView(find);
        panel.addView(leave);
        panel.addView(
                list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("Wi-Fi Direct · no access point")
                        .setView(panel)
                        .setPositiveButton("Close", null)
                        .setNegativeButton(
                                "Location settings",
                                (d, w) -> BlePermissionHelper.openLocationSettings(this))
                        .setNeutralButton(
                                "Wi-Fi settings",
                                (d, w) ->
                                        startActivity(
                                                new Intent(
                                                        android.provider.Settings
                                                                .ACTION_WIFI_SETTINGS)))
                        .create();
        enable.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                List<String> missing = new ArrayList<>();
                for (String permission : WifiDirectTransport.permissions())
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        missing.add(permission);
                if (!missing.isEmpty()) {
                    dialog.dismiss();
                    directPermissions.launch(missing.toArray(new String[0]));
                    return;
                }
            }
            setDirectEnabled(checked);
        });
        find.setOnClickListener(v -> {
            if (!MeshService.directEnabled(this))
                Toast.makeText(this, "Enable Wi-Fi Direct first", Toast.LENGTH_SHORT).show();
            else {
                List<String> missing = new ArrayList<>();
                for (String permission : WifiDirectTransport.permissions())
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        missing.add(permission);
                if (!missing.isEmpty()) {
                    dialog.dismiss();
                    directPermissions.launch(missing.toArray(new String[0]));
                } else if (mesh == null) onToggleClicked();
                else mesh.discoverDirectPeers();
            }
        });
        leave.setOnClickListener(v -> {
            if (mesh != null) mesh.disconnectDirectGroup();
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= peers.size() || mesh == null) return;
            WifiDirectTransport.Peer selected = peers.get(position);
            new AlertDialog.Builder(this)
                    .setTitle("Connect over Wi-Fi Direct?")
                    .setMessage(
                            selected.label()
                                    + "\n\n"
                                    + "Android may request approval on the other phone. Device"
                                    + " labels are unverified; compare your contact fingerprints"
                                    + " for private messaging.")
                    .setPositiveButton(
                            "Connect",
                            (d, w) -> {
                                if (mesh != null) mesh.connectDirectPeer(selected.address);
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable update =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing() || isDestroyed()) return;
                        if (!resumed) {
                            refresh.postDelayed(this, 1000);
                            return;
                        }
                        MeshService current = mesh;
                        if (!WifiDirectTransport.supported(MainActivity.this))
                            state.setText(
                                    "Wi-Fi Direct is unsupported on this phone. Bluetooth and"
                                            + " same-LAN sharing remain available.");
                        else if (current == null)
                            state.setText(
                                    "No router or internet required. Enable on both phones and tap"
                                        + " Find nearby OffGrid phones to start the mesh. Device"
                                        + " labels do not verify contacts.");
                        else
                            current.directSnapshot(snapshot -> {
                                if (!dialog.isShowing() || mesh != current) return;
                                state.setText(
                                        snapshot.status
                                                + "\n"
                                                + "No access point/internet needed. Device names"
                                                + " are unverified; use contact fingerprints.");
                                peers.clear();
                                peers.addAll(snapshot.peers);
                                List<String> labels = new ArrayList<>();
                                for (WifiDirectTransport.Peer peer : peers)
                                    labels.add(peer.label());
                                list.setAdapter(
                                        new ArrayAdapter<>(
                                                MainActivity.this,
                                                android.R.layout.simple_list_item_1,
                                                labels));
                                leave.setEnabled(snapshot.connected);
                            });
                        refresh.postDelayed(this, 2000);
                    }
                };
        dialog.setOnDismissListener(d -> refresh.removeCallbacks(update));
        dialog.show();
        panel.getLayoutParams().height =
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
        panel.requestLayout();
        refresh.post(update);
    }

    private void showDiagnostics() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding / 2, padding, 0);
        androidx.appcompat.widget.SwitchCompat toggle =
                new androidx.appcompat.widget.SwitchCompat(this);
        toggle.setText("Same-LAN Wi-Fi (optional shared network)");
        toggle.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        toggle.setChecked(MeshService.wifiEnabled(this));
        androidx.appcompat.widget.SwitchCompat coc =
                new androidx.appcompat.widget.SwitchCompat(this);
        coc.setText("BLE bulk streams (L2CAP, Android 10+)");
        coc.setChecked(MeshService.cocEnabled(this));
        coc.setEnabled(Build.VERSION.SDK_INT >= 29);
        coc.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("offgrid-network", MODE_PRIVATE)
                    .edit()
                    .putBoolean("coc", checked)
                    .apply();
            if (mesh != null) mesh.setCocEnabled(checked);
        });
        Button reset = new Button(this);
        reset.setText("Reset measurements");
        reset.setOnClickListener(v -> {
            if (mesh != null) mesh.resetMeasurements();
        });
        Button export = new Button(this);
        export.setText("Export measurements");
        export.setOnClickListener(v -> diagnosticsExport.launch("offgrid-measurements.txt"));
        TextView details = new TextView(this);
        details.setTextIsSelectable(true);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(details);
        Button directButton = new Button(this);
        directButton.setText("Wi-Fi Direct · no access point");
        panel.addView(directButton);
        panel.addView(toggle);
        panel.addView(coc);
        panel.addView(reset);
        panel.addView(export);
        panel.addView(
                scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("Network diagnostics")
                        .setView(panel)
                        .setPositiveButton("Close", null)
                        .create();
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked
                    && Build.VERSION.SDK_INT >= 37
                    && androidx.core.content.ContextCompat.checkSelfPermission(
                                    this, android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                dialog.dismiss();
                localNetworkPermission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK);
            } else setWifiEnabled(checked);
        });
        android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable update =
                new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing() || isDestroyed()) return;
                        if (!resumed) {
                            refresh.postDelayed(this, 1000);
                            return;
                        }
                        MeshService current = mesh;
                        if (current == null)
                            details.setText(
                                    "Mesh stopped. Start the mesh to inspect live peers, queues and"
                                        + " errors.\n\n"
                                        + "Use Wi-Fi Direct pairing above without an access point."
                                        + " Same-LAN Wi-Fi is optional when a shared network"
                                        + " exists. Internet is not required.");
                        else
                            current.diagnostics(text -> {
                                if (dialog.isShowing() && mesh == current) details.setText(text);
                            });
                        refresh.postDelayed(this, 2_000);
                    }
                };
        directButton.setOnClickListener(v -> {
            dialog.dismiss();
            showWifiDirectDialog();
        });
        dialog.setOnDismissListener(d -> refresh.removeCallbacks(update));
        dialog.show();
        panel.getLayoutParams().height =
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
        panel.requestLayout();
        refresh.post(update);
    }

    private void updateUi() {
        boolean running = mesh != null;
        toggleButton.setText(running ? "Stop Mesh" : "Start Mesh");
        toggleButton.setBackgroundResource(
                running ? R.drawable.bg_button_stop : R.drawable.bg_button_start);
        toggleButton.setTextColor(running ? COLOR_STOP : COLOR_ON_ACCENT);
        sendButton.setEnabled(running && chatViewModel.pendingMessageId == null);

        if (!running) {
            statusText.setText("Mesh stopped");
            applyStatus(COLOR_OFFLINE, COLOR_MUTED, false);
        } else {
            boolean active = mesh.isMeshActive();
            String state = active ? "Mesh active" : "Waiting for Bluetooth or Wi-Fi";
            statusText.setText(state + " · " + mesh.getPeerCount() + " nearby");
            applyStatus(
                    active ? COLOR_ACTIVE : COLOR_WAITING,
                    active ? COLOR_TEXT : COLOR_WAITING,
                    active);
        }
    }

    private void applyStatus(int dotColor, int textColor, boolean pulsing) {
        ColorStateList tint = ColorStateList.valueOf(dotColor);
        statusDot.setBackgroundTintList(tint);
        statusGlow.setBackgroundTintList(tint);
        statusText.setTextColor(textColor);
        if (pulsing && resumed) startGlow();
        else stopGlow();
    }

    private void startGlow() {
        if (glowAnimator != null && glowAnimator.isRunning()) return;
        statusGlow.setVisibility(View.VISIBLE);
        glowAnimator =
                ObjectAnimator.ofPropertyValuesHolder(
                        statusGlow,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.6f, 1.5f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.6f, 1.5f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f));
        glowAnimator.setDuration(1400);
        glowAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        glowAnimator.start();
    }

    private void stopGlow() {
        if (glowAnimator != null) {
            glowAnimator.cancel();
            glowAnimator = null;
        }
        statusGlow.setAlpha(0f);
        statusGlow.setVisibility(View.INVISIBLE);
    }

    private void showEvent(String text) {
        if (eventPill == null || isDestroyed()) return;
        float density = getResources().getDisplayMetrics().density;
        eventPill.removeCallbacks(hideEvent);
        eventPill.animate().cancel();
        eventPill.setText(text);
        eventPill.setVisibility(View.VISIBLE);
        eventPill.setAlpha(0f);
        eventPill.setTranslationY(-8f * density);
        eventPill.animate().alpha(1f).translationY(0f).setDuration(200).start();
        eventPill.postDelayed(hideEvent, 3500);
    }

    private void pulse(View view) {
        view.animate().cancel();
        view.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(70)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
    }

    private void bindNodeBadge() {
        nodeIdBadge.setText("ID  " + CryptoManager.formatId(crypto().getLocalId(), 8));
    }

    private void copyNodeId() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("OffGrid node ID", crypto().getLocalId()));
        pulse(nodeIdBadge);
        Toast.makeText(this, "Node ID copied", Toast.LENGTH_SHORT).show();
    }

    private void showPermissionDeniedDialog(boolean permanentlyDenied) {
        String permissionName =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? "Nearby devices" : "Location";
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage(
                                "OffGrid needs the \""
                                        + permissionName
                                        + "\" permission to find and talk to people around you"
                                        + " without internet or cellular service.")
                        .setNegativeButton(android.R.string.cancel, null);
        if (permanentlyDenied) {
            builder.setPositiveButton(
                    "Open settings", (d, w) -> BlePermissionHelper.openAppSettings(this));
        } else {
            builder.setPositiveButton("Try again", (d, w) -> permissionHelper.requestPermissions());
        }
        builder.show();
    }

    private void promptLocationIfNeeded() {
        if (!BlePermissionHelper.isLocationServiceRequiredAndOff(this)) return;
        new AlertDialog.Builder(this)
                .setTitle("Turn on Location")
                .setMessage(
                        "On this Android version, Bluetooth scanning only works while Location"
                                + " services are on. OffGrid never reads or stores your location.")
                .setPositiveButton(
                        "Open settings", (d, w) -> BlePermissionHelper.openLocationSettings(this))
                .setNegativeButton("Not now", null)
                .show();
    }

    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.root),
                (view, insets) -> {
                    Insets bars =
                            insets.getInsets(
                                    WindowInsetsCompat.Type.systemBars()
                                            | WindowInsetsCompat.Type.displayCutout());
                    Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                    view.setPadding(
                            bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                    return WindowInsetsCompat.CONSUMED;
                });
    }
}
