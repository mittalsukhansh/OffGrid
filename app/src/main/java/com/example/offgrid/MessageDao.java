package com.example.offgrid;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MessageDao {
    @Insert void insertReplay(ReplayRecord row);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void saveClock(SecurityClock clock);
    @Query("SELECT * FROM security_clock WHERE id = 1") SecurityClock clock();
    @Query("SELECT COUNT(*) FROM replay_ledger WHERE id = :id") int replayed(String id);
    @Query("SELECT COUNT(*) FROM replay_ledger") int replayCount();
    @Query("DELETE FROM replay_ledger WHERE expiresAtMs <= :now") void pruneReplay(long now);
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertChat(ChatRecord row);
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertPending(PendingRecord row);
    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    LiveData<List<ChatRecord>> observeHistory();
    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    List<ChatRecord> history();
    @Query("SELECT * FROM pending_messages ORDER BY storedAtMs, id")
    List<PendingRecord> pending();
    @Query("SELECT * FROM messages WHERE id = :id") ChatRecord chat(String id);
    @Query("SELECT * FROM pending_messages WHERE id = :id") PendingRecord pendingById(String id);
    @Query("SELECT COUNT(*) FROM messages WHERE id = :id") int containsChat(String id);
    @Query("SELECT COUNT(*) FROM pending_messages WHERE id = :id") int containsPending(String id);
    @Query("UPDATE messages SET status = 'FORWARDED' WHERE id = :id AND outgoing = 1 AND status = 'QUEUED'")
    void markForwarded(String id);
    @Query("UPDATE messages SET status = 'DELIVERED' WHERE id = :id AND outgoing = 1")
    void markDelivered(String id);
    @Query("UPDATE messages SET status = :status WHERE id = :id AND outgoing = 1 "
            + "AND (status = 'QUEUED' OR (status = 'FORWARDED' AND conversationId != 'public'))")
    void finishQueued(String id, String status);
    @Query("UPDATE pending_messages SET forwardCount = :count WHERE id = :id")
    void setForwardCount(String id, int count);
    @Query("DELETE FROM pending_messages WHERE id = :id") void deletePending(String id);
    @Query("UPDATE messages SET status = 'EXPIRED' WHERE outgoing = 1 "
            + "AND status IN ('QUEUED', 'FORWARDED') AND (conversationId != 'public' OR status = 'QUEUED') "
            + "AND displayTimeMs <= :cutoff")
    void expireStatuses(long cutoff);
    @Query("DELETE FROM pending_messages WHERE expiresAtMs <= :now") void deleteExpired(long now);
}
