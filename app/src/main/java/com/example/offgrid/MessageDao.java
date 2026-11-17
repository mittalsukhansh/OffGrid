package com.example.offgrid;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putWithdrawal(WithdrawalRecord row);

    @Query(
            "DELETE FROM replay_ledger WHERE id IN (SELECT id FROM replay_ledger ORDER BY"
                    + " expiresAtMs DESC LIMIT -1 OFFSET 10000)")
    void trimReplayHints();

    @Query("SELECT COUNT(*) FROM sequence_windows WHERE priority = :priority")
    int sequenceTierCount(int priority);

    @Query("SELECT COUNT(*) FROM sequence_windows WHERE senderId = :sender")
    int sequenceSenderCount(String sender);

    @Query("SELECT COALESCE(MAX(priority),0) FROM sequence_windows WHERE senderId = :sender")
    int sequenceSenderPriority(String sender);

    @Query("SELECT COALESCE(MAX(lastUsed),0) FROM sequence_windows")
    long sequenceOrdinal();

    @Query("SELECT * FROM sequence_windows WHERE priority = 0 ORDER BY lastUsed,id LIMIT 1")
    SequenceRecord sequenceVictim();

    @Query(
            "SELECT * FROM sequence_windows WHERE priority = 0 AND senderId = :sender ORDER BY"
                + " lastUsed,id LIMIT 1")
    SequenceRecord sequenceVictimForSender(String sender);

    @Query(
            "UPDATE sequence_windows SET priority = :priority WHERE senderId = :sender AND priority"
                    + " < :priority")
    void promoteSequences(String sender, int priority);

    @Query("DELETE FROM sequence_windows WHERE id = :id AND priority = 0")
    int deleteUnknownSequence(String id);

    @Query("SELECT * FROM sequence_windows WHERE id = :id")
    SequenceRecord sequence(String id);

    @Query("SELECT COUNT(*) FROM sequence_windows")
    int sequenceCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveSequence(SequenceRecord row);

    @Query("SELECT * FROM withdrawals WHERE id = :id")
    WithdrawalRecord withdrawal(String id);

    @Query("SELECT COUNT(*) FROM withdrawals")
    int withdrawalCount();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMedia(MediaRecord row);

    @Query("SELECT * FROM media WHERE id = :id")
    MediaRecord media(String id);

    @Query(
            "SELECT * FROM media WHERE state NOT IN ('COMPLETE','FAILED','CANCELLED') ORDER BY"
                    + " createdAt")
    List<MediaRecord> activeMedia();

    @Query("SELECT COUNT(*) FROM media WHERE state != 'CANCELLED'")
    int mediaCount();

    @Query("SELECT COALESCE(SUM(length(file)),0) FROM media")
    long mediaStorageBytes();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putPart(MediaPart row);

    @Query("SELECT * FROM media_parts WHERE mediaId = :id ORDER BY partIndex")
    List<MediaPart> parts(String id);

    @Query("DELETE FROM media_parts WHERE mediaId = :id")
    void deleteParts(String id);

    @Query(
            "DELETE FROM pending_messages WHERE id IN (SELECT packetId FROM media_parts WHERE"
                    + " mediaId = :id)")
    void cancelMediaPackets(String id);

    @Query(
            "UPDATE messages SET content = :content, status = :status, kind = 'text', progress = 0"
                    + " WHERE id = :id")
    void replaceWithdrawn(String id, byte[] content, String status);

    @Query(
            "UPDATE messages SET kind = :kind, progress = :progress, status = :status WHERE id ="
                    + " :id")
    void mediaStatus(String id, String kind, int progress, String status);

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    void updateMediaContent(String id, byte[] content);

    @Query("SELECT id FROM pending_messages")
    List<String> pendingIds();

    @Insert
    void insertReplay(ReplayRecord row);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveClock(SecurityClock clock);

    @Query("SELECT * FROM security_clock WHERE id = 1")
    SecurityClock clock();

    @Query("SELECT COUNT(*) FROM replay_ledger WHERE id = :id")
    int replayed(String id);

    @Query("SELECT COUNT(*) FROM replay_ledger")
    int replayCount();

    @Query("DELETE FROM replay_ledger WHERE expiresAtMs <= :now")
    void pruneReplay(long now);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertChat(ChatRecord row);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertPending(PendingRecord row);

    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    LiveData<List<ChatRecord>> observeHistory();

    @Query(
            "SELECT * FROM messages WHERE conversationId = :conversation ORDER BY displayTimeMs"
                    + " DESC, id DESC LIMIT 500")
    LiveData<List<ChatRecord>> observeConversation(String conversation);

    @Query(
            "SELECT conversationId, MAX(displayTimeMs) AS lastTime, SUM(CASE WHEN outgoing = 0 AND"
                    + " `read` = 0 AND status NOT IN ('UNSENT', 'RECEIVING') THEN 1 ELSE 0 END) AS"
                    + " unread FROM messages GROUP BY conversationId ORDER BY lastTime DESC,"
                    + " conversationId")
    LiveData<List<ConversationSummary>> observeConversations();

    @Query(
            "SELECT conversationId, MAX(displayTimeMs) AS lastTime, SUM(CASE WHEN outgoing = 0 AND"
                    + " `read` = 0 AND status NOT IN ('UNSENT', 'RECEIVING') THEN 1 ELSE 0 END) AS"
                    + " unread FROM messages GROUP BY conversationId ORDER BY lastTime DESC,"
                    + " conversationId")
    List<ConversationSummary> conversations();

    @Query(
            "UPDATE messages SET `read` = 1 WHERE conversationId = :conversation AND id IN (:ids)"
                    + " AND `read` = 0 AND status != 'RECEIVING'")
    void markRead(String conversation, List<String> ids);

    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    List<ChatRecord> history();

    @Query("SELECT * FROM pending_messages ORDER BY storedAtMs, id")
    List<PendingRecord> pending();

    @Query("SELECT * FROM messages WHERE id = :id")
    ChatRecord chat(String id);

    @Query("SELECT * FROM pending_messages WHERE id = :id")
    PendingRecord pendingById(String id);

    @Query("SELECT COUNT(*) FROM messages WHERE id = :id")
    int containsChat(String id);

    @Query("SELECT COUNT(*) FROM pending_messages WHERE id = :id")
    int containsPending(String id);

    @Query(
            "UPDATE messages SET status = 'FORWARDED' WHERE id = :id AND outgoing = 1 AND status ="
                    + " 'QUEUED'")
    void markForwarded(String id);

    @Query(
            "UPDATE messages SET status = 'DELIVERED', progress = CASE WHEN kind != 'text' THEN 100"
                    + " ELSE progress END WHERE id = :id AND outgoing = 1")
    void markDelivered(String id);

    @Query(
            "UPDATE messages SET status = 'UNSEND_EXPIRED' WHERE status = 'UNSEND_PENDING' AND id"
                    + " IN (SELECT id FROM withdrawals WHERE outgoing = 1 AND confirmed = 0 AND"
                    + " createdAt <= :cutoff)")
    void expireWithdrawals(long cutoff);

    @Query(
            "UPDATE messages SET status = :status WHERE id = :id AND outgoing = 1 AND (status ="
                    + " 'QUEUED' OR (status = 'FORWARDED' AND conversationId != 'public'))")
    void finishQueued(String id, String status);

    @Query("UPDATE pending_messages SET forwardCount = :count WHERE id = :id")
    void setForwardCount(String id, int count);

    @Query("DELETE FROM pending_messages WHERE id = :id")
    void deletePending(String id);

    @Query(
            "UPDATE messages SET status = 'EXPIRED' WHERE outgoing = 1 AND status IN ('QUEUED',"
                    + " 'FORWARDED') AND (conversationId != 'public' OR status = 'QUEUED') AND"
                    + " displayTimeMs <= :cutoff")
    void expireStatuses(long cutoff);

    @Query("DELETE FROM pending_messages WHERE expiresAtMs <= :now")
    void deleteExpired(long now);
}
