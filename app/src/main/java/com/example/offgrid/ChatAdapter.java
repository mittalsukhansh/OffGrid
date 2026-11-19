package com.example.offgrid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    public static final class ChatEntry {
        public final MeshMessage message;
        public final boolean outgoing;

        public final long displayTimeMs;

        public final String status;
        public final String kind;
        public final int progress;
        public android.graphics.Bitmap thumbnail;

        public ChatEntry(@NonNull MeshMessage message, boolean outgoing, long displayTimeMs) {
            this(message, outgoing, displayTimeMs, outgoing ? "QUEUED" : "RECEIVED");
        }

        public ChatEntry(
                @NonNull MeshMessage message,
                boolean outgoing,
                long displayTimeMs,
                @NonNull String status) {
            this(message, outgoing, displayTimeMs, status, "text", 0);
        }

        public ChatEntry(
                @NonNull MeshMessage message,
                boolean outgoing,
                long displayTimeMs,
                @NonNull String status,
                String kind,
                int progress) {
            this.message = message;
            this.outgoing = outgoing;
            this.displayTimeMs = displayTimeMs;
            this.status = status;
            this.kind = kind;
            this.progress = progress;
        }
    }

    private static final int OUT_TEXT = 0xFFFFFFFF;
    private static final int OUT_META = 0xCCFFFFFF;
    private static final int OUT_SENDER = 0xE6FFFFFF;
    private static final int OUT_BADGE = 0xFFE0F7FA;
    private static final int IN_TEXT = 0xFFF3F4F6;
    private static final int IN_META = 0xFF9CA3AF;
    private static final int PUBLIC_ACCENT = 0xFF34D399;
    private static final int PRIVATE_ACCENT = 0xFF22D3EE;
    private static final int WARN = 0xFFF59E0B;
    private static final int DANGER = 0xFFF87171;

    private final List<ChatEntry> entries;
    private final Set<String> animated = new HashSet<>();

    public interface Interaction {
        void open(ChatEntry entry);

        void details(ChatEntry entry);

        boolean longPress(ChatEntry entry);
    }

    private Interaction interaction;

    public void interaction(Interaction interaction) {
        this.interaction = interaction;
    }

    public ChatAdapter(@NonNull List<ChatEntry> entries) {
        this.entries = entries;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatEntry entry = entries.get(position);
        Context context = holder.itemView.getContext();
        MeshMessage message = entry.message;
        boolean outgoing = entry.outgoing;
        boolean secure = message.isPrivate();
        float density = context.getResources().getDisplayMetrics().density;

        boolean media = !entry.kind.equals("text");
        holder.progress.setVisibility(media ? View.VISIBLE : View.GONE);
        holder.progress.setProgress(Math.max(0, Math.min(100, entry.progress)));
        holder.details.setVisibility(media || outgoing ? View.VISIBLE : View.GONE);
        holder.details.setText(media && outgoing ? "Transfer controls" : "Delivery details");
        holder.details.setOnClickListener(
                interaction == null ? null : v -> interaction.details(entry));
        holder.senderText.setText(senderLabel(entry, context));
        holder.messageText.setText(message.getText());
        holder.preview.setClipToOutline(true);
        holder.preview.setVisibility(entry.thumbnail == null ? View.GONE : View.VISIBLE);
        holder.preview.setImageBitmap(entry.thumbnail);
        View.OnClickListener open =
                entry.kind.equals("text") || interaction == null
                        ? null
                        : view -> interaction.open(entry);
        holder.bubble.setOnClickListener(open);
        holder.messageText.setOnClickListener(open);
        holder.preview.setOnClickListener(open);
        View.OnLongClickListener unsend =
                interaction == null ? null : view -> interaction.longPress(entry);
        holder.bubble.setOnLongClickListener(unsend);
        holder.messageText.setOnLongClickListener(unsend);
        holder.preview.setOnLongClickListener(unsend);
        holder.timeText.setText(
                DateFormat.getTimeFormat(context).format(new Date(entry.displayTimeMs))
                        + (entry.outgoing || !entry.kind.equals("text")
                                ? " · "
                                        + statusLabel(entry.status)
                                        + (!entry.kind.equals("text") && entry.progress < 100
                                                ? " " + entry.progress + "%"
                                                : "")
                                : ""));

        int background;
        if (outgoing) {
            background =
                    secure ? R.drawable.bg_bubble_out_private : R.drawable.bg_bubble_out_public;
        } else {
            background = secure ? R.drawable.bg_bubble_in_private : R.drawable.bg_bubble_in_public;
        }
        holder.bubble.setBackgroundResource(background);

        int metaColor = outgoing ? OUT_META : IN_META;
        holder.messageText.setTextColor(outgoing ? OUT_TEXT : IN_TEXT);
        holder.senderText.setTextColor(
                outgoing ? OUT_SENDER : (secure ? PRIVATE_ACCENT : PUBLIC_ACCENT));
        holder.hopText.setTextColor(metaColor);
        boolean failed =
                entry.status.equals("FAILED")
                        || entry.status.equals("EVICTED")
                        || entry.status.equals("EXPIRED")
                        || entry.status.equals("UNSEND_EXPIRED");
        holder.timeText.setTextColor(failed ? DANGER : metaColor);

        if (outgoing) {
            holder.trustText.setVisibility(View.GONE);
            holder.hopText.setText("TTL " + message.getTtl());
        } else {
            CryptoManager crypto = CryptoManager.getInstance(context);
            boolean legacy = message.isPublic() && message.getSignature() == null;
            boolean verified = crypto.isVerified(message.getSenderId());
            holder.trustText.setVisibility(View.VISIBLE);
            holder.trustText.setText(
                    legacy ? "Legacy unsigned" : verified ? "✓ Verified" : "Unverified");
            holder.trustText.setTextColor(legacy || !verified ? WARN : PUBLIC_ACCENT);
            // Relay TTL is mutable and cannot prove route length or physical proximity.
            holder.hopText.setText("TTL " + message.getTtl());
        }

        holder.e2eeBadge.setVisibility(secure ? View.VISIBLE : View.GONE);
        int badgeColor = outgoing ? OUT_BADGE : PRIVATE_ACCENT;
        holder.e2eeBadge.setTextColor(badgeColor);
        TextViewCompat.setCompoundDrawableTintList(
                holder.e2eeBadge, ColorStateList.valueOf(badgeColor));

        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) holder.bubble.getLayoutParams();
        params.gravity = outgoing ? Gravity.END : Gravity.START;
        holder.bubble.setLayoutParams(params);

        holder.itemView.animate().cancel();
        holder.itemView.setAlpha(1f);
        holder.itemView.setTranslationY(0f);
        if (position == entries.size() - 1 && animated.add(message.getMessageId())) {
            if (animated.size() > 1000) {
                animated.clear();
                animated.add(message.getMessageId());
            }
            holder.itemView.setAlpha(0f);
            holder.itemView.setTranslationY(14f * density);
            holder.itemView
                    .animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static String senderLabel(ChatEntry entry, Context context) {
        MeshMessage m = entry.message;
        CryptoManager crypto = CryptoManager.getInstance(context);
        if (entry.outgoing) {
            return m.isPrivate() ? "You \u2192 " + crypto.contactLabel(m.getTargetId()) : "You";
        }
        return crypto.contactLabel(m.getSenderId());
    }

    private static String statusLabel(String status) {
        switch (status) {
            case "UNSEND_PENDING":
                return "Unsend requested";
            case "UNSEND_EXPIRED":
                return "Unsend unconfirmed";
            case "UNSENT":
                return "Unsent";
            case "PAUSED":
                return "Paused";
            case "CANCELLED":
                return "Sending cancelled";
            case "RECEIVING":
                return "Receiving";
            case "TRANSFERRING":
                return "Transferring";
            case "READY":
                return "Ready";
            case "FORWARDED":
                return "Forwarded";
            case "DELIVERED":
                return "Delivered";
            case "EXPIRED":
                return "Expired";
            case "FAILED":
                return "Failed";
            case "EVICTED":
                return "Failed · queue full";
            default:
                return "Queued";
        }
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.substring(0, Math.min(8, id.length())).toUpperCase(Locale.ROOT);
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final View bubble;
        final TextView senderText;
        final TextView trustText;
        final TextView e2eeBadge;
        final TextView messageText;
        final TextView hopText;
        final TextView timeText;
        final ImageView preview;
        final android.widget.ProgressBar progress;
        final android.widget.Button details;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.bubble);
            senderText = itemView.findViewById(R.id.senderText);
            trustText = itemView.findViewById(R.id.trustText);
            e2eeBadge = itemView.findViewById(R.id.e2eeBadge);
            messageText = itemView.findViewById(R.id.messageText);
            hopText = itemView.findViewById(R.id.hopText);
            timeText = itemView.findViewById(R.id.timeText);
            preview = itemView.findViewById(R.id.mediaPreview);
            progress = itemView.findViewById(R.id.transferProgress);
            details = itemView.findViewById(R.id.messageDetails);
        }
    }
}
