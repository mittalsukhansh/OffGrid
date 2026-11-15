package com.example.offgrid;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Renders chat bubbles: own messages on the right, messages from peers on the left. Private
 * (end-to-end encrypted) messages are marked with a lock and show who they are addressed to. The
 * adapter displays the list it is given; the owner mutates the list and calls the matching
 * notifyItem* method.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    /** One row in the chat history. */
    public static final class ChatEntry {
        public final MeshMessage message;
        public final boolean outgoing;

        /** Time shown in the bubble (send time for own messages, receive time for peers'). */
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

    private static final int OUT_BG = 0xFF1565C0;
    private static final int OUT_TEXT = 0xFFFFFFFF;
    private static final int OUT_META = 0xCCFFFFFF;
    private static final int IN_BG = 0xFFE3E3E8;
    private static final int IN_TEXT = 0xFF1B1B1F;
    private static final int IN_META = 0x991B1B1F;
    private static final float CORNER_RADIUS_DP = 16f;
    private static final String LOCK = " \uD83D\uDD12"; // lock emoji

    private final List<ChatEntry> entries;

    public interface Interaction {
        void open(ChatEntry entry);

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

        holder.senderText.setText(senderLabel(entry, context));
        holder.messageText.setText(entry.message.getText());
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

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(
                CORNER_RADIUS_DP * context.getResources().getDisplayMetrics().density);
        background.setColor(entry.outgoing ? OUT_BG : IN_BG);
        holder.bubble.setBackground(background);

        int textColor = entry.outgoing ? OUT_TEXT : IN_TEXT;
        int metaColor = entry.outgoing ? OUT_META : IN_META;
        holder.messageText.setTextColor(textColor);
        holder.senderText.setTextColor(metaColor);
        holder.timeText.setTextColor(metaColor);

        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) holder.bubble.getLayoutParams();
        params.gravity = entry.outgoing ? Gravity.END : Gravity.START;
        holder.bubble.setLayoutParams(params);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static String senderLabel(ChatEntry entry, Context context) {
        MeshMessage m = entry.message;
        if (entry.outgoing) {
            return m.isPrivate()
                    ? "You \u2192 "
                            + CryptoManager.getInstance(context).contactLabel(m.getTargetId())
                            + LOCK
                    : "You";
        }
        String trust =
                CryptoManager.getInstance(context).isVerified(m.getSenderId())
                        ? " · ✓ Verified contact"
                        : " · Unverified contact";
        if (m.isPublic() && m.getSignature() == null) trust = " · Legacy unsigned message";
        return CryptoManager.getInstance(context).contactLabel(m.getSenderId())
                + (m.isPrivate() ? " · private" + LOCK : "")
                + trust;
    }

    private static String statusLabel(String status) {
        switch (status) {
            case "UNSEND_PENDING":
                return "Unsend requested";
            case "UNSEND_EXPIRED":
                return "Unsend unconfirmed";
            case "UNSENT":
                return "Unsent";
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
        final TextView messageText;
        final TextView timeText;
        final ImageView preview;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            bubble = itemView.findViewById(R.id.bubble);
            senderText = itemView.findViewById(R.id.senderText);
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            preview = itemView.findViewById(R.id.mediaPreview);
        }
    }
}
