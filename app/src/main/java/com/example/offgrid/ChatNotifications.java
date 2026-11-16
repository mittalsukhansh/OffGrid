package com.example.offgrid;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Generic lock-screen content: never expose private text, media or contact nicknames. */
final class ChatNotifications {
    private static final String CHANNEL = "offgrid_messages";
    private static final int ID = 2001;

    static void received(Context context, MeshMessage message) {
        String conversation = message.isPrivate() ? message.getSenderId() : "public";
        if (MessageStore.get(context).isConversationVisible(conversation)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL, "New messages", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open =
                new Intent(context, MainActivity.class)
                        .setAction("offgrid.chat." + conversation)
                        .putExtra("conversation", conversation)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending =
                PendingIntent.getActivity(
                        context,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            NotificationManagerCompat.from(context)
                    .notify(
                            conversation,
                            ID,
                            new NotificationCompat.Builder(context, CHANNEL)
                                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                                    .setContentTitle("OffGrid")
                                    .setContentText(
                                            message.isPrivate()
                                                    ? "New private message"
                                                    : "New public message")
                                    .setContentIntent(pending)
                                    .setAutoCancel(true)
                                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                                    .setOnlyAlertOnce(true)
                                    .build());
        } catch (SecurityException ignored) {
            /* Notification permission is optional. */
        }
    }

    static void cancel(Context context, String conversation) {
        NotificationManagerCompat.from(context).cancel(conversation, ID);
    }
}
