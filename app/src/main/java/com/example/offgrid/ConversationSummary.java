package com.example.offgrid;

/** Query projection: no plaintext message body is needed to show the conversation list. */
public final class ConversationSummary {
    public String conversationId;
    public long lastTime;
    public int unread;
}
