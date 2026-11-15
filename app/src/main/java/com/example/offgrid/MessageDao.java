package com.example.offgrid;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertChat(ChatRecord row);
    @Insert(onConflict = OnConflictStrategy.IGNORE) void insertPending(PendingRecord row);
    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    LiveData<List<ChatRecord>> observeHistory();
    @Query("SELECT * FROM messages ORDER BY displayTimeMs DESC, id DESC LIMIT 500")
    List<ChatRecord> history();
    @Query("SELECT * FROM pending_messages ORDER BY storedAtMs, id")
    List<PendingRecord> pending();
    @Query("SELECT COUNT(*) FROM messages WHERE id = :id") int containsChat(String id);
    @Query("SELECT COUNT(*) FROM pending_messages WHERE id = :id") int containsPending(String id);
    @Query("UPDATE messages SET status = :status WHERE id = :id AND outgoing = 1")
    void setStatus(String id, String status);
    @Query("UPDATE messages SET status = :status WHERE id = :id AND status = 'QUEUED'")
    void finishQueued(String id, String status);
    @Query("UPDATE pending_messages SET forwardCount = :count WHERE id = :id")
    void setForwardCount(String id, int count);
    @Query("DELETE FROM pending_messages WHERE id = :id") void deletePending(String id);
    @Query("UPDATE messages SET status = 'EXPIRED' WHERE status = 'QUEUED' AND id IN "
            + "(SELECT id FROM pending_messages WHERE expiresAtMs <= :now)")
    void expireStatuses(long now);
    @Query("DELETE FROM pending_messages WHERE expiresAtMs <= :now") void deleteExpired(long now);
}
