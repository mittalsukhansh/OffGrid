package com.example.offgrid;

import com.example.offgrid.mesh.protocol.MeshProtocol;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, private, resumable file transfer over signed encrypted control packets. */
final class MediaTransfers {
    static final int CHUNK = 640;
    static final int IMAGE_MAX = 48 * 1024;
    static final int VIDEO_MAX = 256 * 1024;
    static final long LIFETIME = 60 * 60_000L;
    private final PrivateActions actions;
    private final MessageStore store;
    private final CryptoManager crypto;
    java.util.function.Function<String, com.example.offgrid.mesh.transport.TransferProfile>
            profiles = id -> com.example.offgrid.mesh.transport.TransferProfile.GATT;
    private final Map<String, Long> nextPump = new HashMap<>();
    private long pumpDelay = 5000;
    private int cursor;
    private final java.util.EnumMap<com.example.offgrid.mesh.transport.TransferProfile, Long>
            profileDue =
                    new java.util.EnumMap<>(
                            com.example.offgrid.mesh.transport.TransferProfile.class);

    long pumpDelay() {
        return pumpDelay;
    }

    MediaTransfers(PrivateActions actions) {
        this.actions = actions;
        store = actions.store;
        crypto = actions.crypto;
    }

    private MessageDao dao() {
        return store.dao();
    }

    static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    static String kind(String mime) {
        return mime.equals("image/jpeg") ? "image" : "video";
    }

    static String label(String mime, int length) {
        return (mime.equals("image/jpeg") ? "Photo" : "Video")
                + " · "
                + ((length + 1023) / 1024)
                + " KB";
    }

    private JSONObject metadata(MediaRecord record) throws Exception {
        return new JSONObject(
                new String(
                        actions.open("media-meta:" + record.id, record.metadata),
                        StandardCharsets.UTF_8));
    }

    private MeshMessage original(MediaRecord record) throws Exception {
        return MeshMessage.fromJson(metadata(record).getJSONObject("wire"));
    }

    private BitSet bits(MediaRecord record) {
        return BitSet.valueOf(record.received);
    }

    private void ensureSpace(int additionalBytes) {
        if (dao().activeMedia().size() >= 4
                || dao().mediaCount() >= 64
                || dao().mediaStorageBytes() + additionalBytes > 16L * 1024 * 1024) {
            throw new IllegalStateException(
                    "Attachment storage is full or too many transfers are active");
        }
    }

    private void validateManifest(JSONObject manifest) {
        String mime = manifest.optString("mime");
        int size = manifest.optInt("size", 0);
        int max = mime.equals("image/jpeg") ? IMAGE_MAX : mime.equals("video/mp4") ? VIDEO_MAX : 0;
        if (size <= 0
                || size > max
                || manifest.optInt("parts") != (size + CHUNK - 1) / CHUNK
                || !manifest.optString("sha256").matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid media manifest");
    }

    private MediaRecord record(MeshMessage wire, JSONObject manifest, boolean outgoing, long now)
            throws Exception {
        MediaRecord record = new MediaRecord();
        record.id = wire.getMessageId();
        record.senderId = wire.getSenderId();
        record.targetId = wire.getTargetId();
        record.outgoing = outgoing;
        record.mime = manifest.getString("mime");
        record.byteCount = manifest.getInt("size");
        record.chunkCount = manifest.getInt("parts");
        record.createdAt = now;
        record.state = outgoing ? "OFFERED" : "RECEIVING";
        record.metadata =
                actions.seal(
                        "media-meta:" + record.id,
                        new JSONObject()
                                .put("manifest", manifest)
                                .put("wire", wire.toJson())
                                .toString()
                                .getBytes(StandardCharsets.UTF_8));
        return record;
    }

    public void offer(String target, byte[] bytes, String mime, long now) throws Exception {
        if (target == null || target.equals(crypto.getLocalId()) || !crypto.hasKeyFor(target))
            throw new IllegalArgumentException("Choose a private contact first");
        if (dao().activeMedia().stream().filter(row -> row.outgoing).count() >= 2)
            throw new IllegalStateException("Wait for an outgoing attachment to finish");
        AttachmentPreparer.validate(bytes, mime);
        JSONObject manifest =
                new JSONObject()
                        .put("mime", mime)
                        .put("size", bytes.length)
                        .put("parts", (bytes.length + CHUNK - 1) / CHUNK)
                        .put("sha256", hash(bytes));
        String id = MeshMessage.newId(crypto.getLocalId());
        MeshMessage wire =
                crypto.signPacket(
                        MeshMessage.createMedia(
                                id,
                                crypto.getLocalId(),
                                target,
                                crypto.encrypt(id, target, now, manifest.toString()),
                                crypto.getPublicKeyBase64(),
                                now));
        MediaRecord record = record(wire, manifest, true, now);
        record.file = actions.seal("media-file:" + id, bytes);
        actions.transaction(() -> {
            ensureSpace(bytes.length + 29);
            store.save(
                    wire,
                    wire.withPlaintext(label(mime, bytes.length)),
                    true,
                    now,
                    wire,
                    now,
                    now + 600_000);
            dao().putMedia(record);
            dao().linkTransfer(id, id);
            dao().mediaStatus(id, kind(mime), 0, "QUEUED");
        });
    }

    private void queue(MeshMessage packet, String id, long now) throws Exception {
        actions.transaction(() -> {
            actions.queue(packet, now);
            dao().linkTransfer(packet.getMessageId(), id);
        });
    }

    private void accept(MeshMessage packet, MeshMessage response, String id, long now)
            throws Exception {
        actions.transaction(() -> {
            actions.accept(packet, response, now);
            if (response != null) dao().linkTransfer(response.getMessageId(), id);
        });
    }

    private static int progress(MediaRecord row) {
        return BitSet.valueOf(row.received).cardinality() * 100 / row.chunkCount;
    }

    public void command(String id, PrivateActions.TransferCommand command, long now)
            throws Exception {
        actions.transaction(() -> {
            MediaRecord row = dao().media(id);
            ChatRecord chat = dao().chat(id);
            if (row == null
                    || !row.outgoing
                    || !row.senderId.equals(crypto.getLocalId())
                    || chat == null
                    || chat.status.equals("DELIVERED")
                    || dao().withdrawal(id) != null
                    || row.state.equals("COMPLETE"))
                throw new IllegalArgumentException("This attachment cannot be changed");
            boolean terminal = row.state.equals("FAILED") || row.state.equals("CANCELLED");
            if (command == PrivateActions.TransferCommand.SEND_AGAIN) {
                if (!terminal || row.file.length == 0)
                    throw new IllegalArgumentException(
                            "Only a failed or cancelled attachment can be sent again");
                byte[] file = actions.open("media-file:" + row.id, row.file);
                cancel(row);
                // A fresh signed message/sequence is required after expiry or receiver failure.
                offer(row.targetId, file, row.mime, now);
            } else if (command == PrivateActions.TransferCommand.CANCEL) {
                if (row.state.equals("CANCELLED")) return;
                cancel(row);
            } else {
                if (terminal)
                    throw new IllegalArgumentException("Start a new transfer with Send again");
                if (command == PrivateActions.TransferCommand.RESUME
                        && (now - row.createdAt > LIFETIME
                                || (row.state.equals("OFFERED") && now - row.createdAt > 600_000)))
                    throw new IllegalArgumentException(
                            "Transfer expired. Cancel it, then use Send again");
                row.paused = command == PrivateActions.TransferCommand.PAUSE;
                dao().putMedia(row);
                dao().mediaStatus(
                                id,
                                kind(row.mime),
                                progress(row),
                                row.paused
                                        ? "PAUSED"
                                        : row.state.equals("OFFERED") ? "QUEUED" : row.state);
                nextPump.remove(id);
            }
        });
    }

    private void cancel(MediaRecord row) {
        dao().deleteTransferPackets(row.id);
        dao().deleteParts(row.id);
        row.paused = false;
        row.state = "CANCELLED";
        dao().putMedia(row);
        dao().mediaStatus(row.id, kind(row.mime), progress(row), "CANCELLED");
        nextPump.remove(row.id);
    }

    public String details(String id) {
        MediaRecord row = dao().media(id);
        if (row == null) return "Attachment is unavailable.";
        BitSet bitmap = bits(row);
        long bytes = (long) bitmap.cardinality() * CHUNK;
        if (bitmap.get(row.chunkCount - 1)) bytes -= (long) row.chunkCount * CHUNK - row.byteCount;
        return (row.outgoing ? "Recipient-confirmed blocks: " : "Validated local blocks: ")
                + bytes
                + " / "
                + row.byteCount
                + " bytes ("
                + progress(row)
                + "%).\n"
                + (row.paused
                        ? "Paused on this phone. Already relayed bytes may still arrive.\n"
                        : "")
                + (row.state.equals("CANCELLED")
                        ? "Local sending cancelled. This is not unsend.\n"
                        : "")
                + (row.state.equals("OFFERED")
                        ? "Waiting for the recipient to accept the offer.\n"
                        : "")
                + (row.state.equals("FAILED")
                        ? "Transfer failed or expired; Send again starts a new attachment.\n"
                        : "")
                + "Block progress is not proof of Delivered. Only an authenticated completion"
                + " receipt confirms delivery. Pausing does not extend expiry.";
    }

    private MeshMessage status(MediaRecord record) throws Exception {
        return crypto.control(
                record.outgoing ? record.targetId : record.senderId,
                new JSONObject()
                        .put("kind", "media_status")
                        .put("ranges", true)
                        .put("mid", record.id)
                        .put(
                                "bits",
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(record.received))
                        .put("failed", record.state.equals("FAILED"))
                        .toString());
    }

    MeshProtocol.Incoming acceptOffer(MeshMessage wire, String plaintext, long now)
            throws Exception {
        JSONObject manifest;
        try {
            manifest = JsonBounds.object(plaintext, MeshMessage.MAX_PRIVATE_PLAINTEXT_BYTES);
            validateManifest(manifest);
        } catch (Exception e) {
            return MeshProtocol.Incoming.IGNORED;
        }
        if (dao().withdrawal(wire.getMessageId()) != null) return MeshProtocol.Incoming.IGNORED;
        MediaRecord record = record(wire, manifest, false, now);
        MeshMessage response = status(record);
        try {
            actions.transaction(() -> {
                ensureSpace(record.byteCount + 29);
                store.save(
                        wire,
                        wire.withPlaintext(label(record.mime, record.byteCount)),
                        false,
                        now,
                        response,
                        now,
                        now + 600_000,
                        true);
                dao().putMedia(record);
                dao().linkTransfer(response.getMessageId(), record.id);
                dao().mediaStatus(record.id, kind(record.mime), 0, "RECEIVING");
            });
        } catch (IllegalStateException e) {
            return MeshProtocol.Incoming.IGNORED;
        }
        return new MeshProtocol.Incoming(
                wire,
                wire.withPlaintext(label(record.mime, record.byteCount)),
                response,
                null,
                null);
    }

    MeshProtocol.Incoming duplicateOffer(MeshMessage wire, MediaRecord record, long now)
            throws Exception {
        if (!record.senderId.equals(wire.getSenderId())
                || !record.targetId.equals(wire.getTargetId())
                || !DeliveryReceipt.fingerprint(wire)
                        .equals(DeliveryReceipt.fingerprint(original(record))))
            return MeshProtocol.Incoming.IGNORED;
        MeshMessage response =
                record.state.equals("COMPLETE") ? completion(record) : status(record);
        queue(response, record.id, now);
        return PrivateActions.result(wire, response);
    }

    private MeshMessage completion(MediaRecord record) throws Exception {
        return crypto.createAcknowledgement(original(record));
    }

    MeshProtocol.Incoming control(MeshMessage packet, JSONObject body, long now) throws Exception {
        String id = body.optString("mid");
        if (!id.matches("[0-9a-f]{64}") || dao().withdrawal(id) != null)
            return MeshProtocol.Incoming.IGNORED;
        String kind = body.optString("kind");
        MediaRecord record = dao().media(id);
        if (kind.equals("media_offer")) {
            MeshMessage wire;
            try {
                wire = MeshMessage.fromJson(body.getJSONObject("wire"));
                if (!wire.isMedia()
                        || !wire.getMessageId().equals(id)
                        || !wire.getSenderId().equals(packet.getSenderId())
                        || !wire.getTargetId().equals(packet.getTargetId())
                        || !PacketSecurity.fresh(wire, now)
                        || !PacketSecurity.namespaced(wire)
                        || !crypto.verifyPacket(wire)) return MeshProtocol.Incoming.IGNORED;
            } catch (Exception e) {
                return MeshProtocol.Incoming.IGNORED;
            }
            if (record != null) return duplicateOffer(wire, record, now);
            final MeshProtocol.Incoming[] response = {MeshProtocol.Incoming.IGNORED};
            actions.transaction(() -> {
                actions.accept(packet, null, now);
                response[0] = acceptOffer(wire, crypto.decrypt(wire), now);
            });
            return response[0];
        }
        if (kind.equals("media_query") && record == null && id.startsWith(packet.getSenderId())) {
            MeshMessage response =
                    crypto.control(
                            packet.getSenderId(),
                            new JSONObject()
                                    .put("kind", "media_missing")
                                    .put("mid", id)
                                    .toString());
            actions.accept(packet, response, now);
            return PrivateActions.result(packet, response);
        }
        if (record == null || record.state.equals("CANCELLED"))
            return MeshProtocol.Incoming.IGNORED;
        String peer = record.outgoing ? record.targetId : record.senderId;
        if (!peer.equals(packet.getSenderId()) || !crypto.getLocalId().equals(packet.getTargetId()))
            return MeshProtocol.Incoming.IGNORED;
        if (kind.equals("media_missing")
                && record.outgoing
                && !record.paused
                && !record.state.equals("FAILED")
                && now - record.createdAt < 600_000) {
            MeshMessage response =
                    crypto.control(
                            peer,
                            new JSONObject()
                                    .put("kind", "media_offer")
                                    .put("mid", id)
                                    .put("wire", original(record).toJson())
                                    .toString());
            accept(packet, response, record.id, now);
            return PrivateActions.result(packet, response);
        }
        if (kind.equals("media_query") && !record.outgoing) {
            MeshMessage response =
                    record.state.equals("COMPLETE") ? completion(record) : status(record);
            accept(packet, response, record.id, now);
            return PrivateActions.result(packet, response);
        }
        if (kind.equals("media_status") && record.outgoing) {
            if (record.state.equals("FAILED")) return MeshProtocol.Incoming.IGNORED;
            ChatRecord chat = dao().chat(id);
            if (chat == null || chat.status.equals("DELIVERED") || chat.status.startsWith("UNSEN"))
                return MeshProtocol.Incoming.IGNORED;
            byte[] bitmap;
            try {
                String encoded = body.optString("bits");
                if (encoded.length() > 120) return MeshProtocol.Incoming.IGNORED;
                bitmap = Base64.getUrlDecoder().decode(encoded);
                if (BitSet.valueOf(bitmap).length() > record.chunkCount)
                    return MeshProtocol.Incoming.IGNORED;
            } catch (IllegalArgumentException e) {
                return MeshProtocol.Incoming.IGNORED;
            }
            BitSet received = bits(record);
            received.or(BitSet.valueOf(bitmap));
            record.received = received.toByteArray();
            JSONObject meta = metadata(record).put("ranges", body.optBoolean("ranges", false));
            record.metadata =
                    actions.seal(
                            "media-meta:" + record.id,
                            meta.toString().getBytes(StandardCharsets.UTF_8));
            record.state = body.optBoolean("failed") ? "FAILED" : "TRANSFERRING";
            if (record.state.equals("FAILED")) record.paused = false;
            actions.transaction(() -> {
                accept(packet, null, record.id, now);
                dao().deletePending(id);
                for (MediaPart part : dao().parts(id))
                    if (received.get(part.partIndex)) dao().deletePending(part.packetId);
                dao().putMedia(record);
                dao().mediaStatus(
                                id,
                                kind(record.mime),
                                received.cardinality() * 100 / record.chunkCount,
                                record.paused ? "PAUSED" : record.state);
                if (record.state.equals("FAILED")) {
                    dao().deleteTransferPackets(id);
                    dao().deleteParts(id);
                }
            });
            return PrivateActions.result(packet, null);
        }
        if ((kind.equals("media_chunk") || kind.equals("media_range")) && !record.outgoing)
            return chunk(packet, body, record, now);
        return MeshProtocol.Incoming.IGNORED;
    }

    private MeshProtocol.Incoming chunk(
            MeshMessage packet, JSONObject body, MediaRecord record, long now) throws Exception {
        if (record.state.equals("FAILED")) {
            MeshMessage response = status(record);
            accept(packet, response, record.id, now);
            return PrivateActions.result(packet, response);
        }
        if (record.state.equals("COMPLETE")) {
            MeshMessage response = completion(record);
            accept(packet, response, record.id, now);
            return PrivateActions.result(packet, response);
        }
        int index = body.optInt("index", -1);
        boolean range = body.optString("kind").equals("media_range");
        String encoded = body.optString("data");
        if (index < 0 || index >= record.chunkCount || encoded.length() > (range ? 10240 : 856))
            return MeshProtocol.Incoming.IGNORED;
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return MeshProtocol.Incoming.IGNORED;
        }
        int spans = (bytes.length + CHUNK - 1) / CHUNK;
        if (spans < 1
                || spans > (range ? 12 : 1)
                || index + spans > record.chunkCount
                || bytes.length != Math.min(spans * CHUNK, record.byteCount - index * CHUNK))
            return MeshProtocol.Incoming.IGNORED;
        BitSet received = bits(record);
        List<MediaPart> added = new ArrayList<>();
        for (int offset = 0; offset < spans; offset++) {
            int partIndex = index + offset;
            if (received.get(partIndex)) continue;
            MediaPart part = new MediaPart();
            part.mediaId = record.id;
            part.partIndex = partIndex;
            byte[] data =
                    Arrays.copyOfRange(
                            bytes, offset * CHUNK, Math.min(bytes.length, (offset + 1) * CHUNK));
            part.data = actions.seal("media-part:" + record.id + ":" + partIndex, data);
            added.add(part);
            received.set(partIndex);
        }
        if (added.isEmpty()) {
            MeshMessage response = status(record);
            accept(packet, response, record.id, now);
            return PrivateActions.result(packet, response);
        }
        record.received = received.toByteArray();
        if (received.cardinality() == record.chunkCount) {
            byte[] file = new byte[record.byteCount];
            for (MediaPart old : dao().parts(record.id)) {
                byte[] chunk =
                        actions.open("media-part:" + record.id + ":" + old.partIndex, old.data);
                System.arraycopy(chunk, 0, file, old.partIndex * CHUNK, chunk.length);
            }
            for (MediaPart part : added) {
                byte[] data =
                        actions.open("media-part:" + record.id + ":" + part.partIndex, part.data);
                System.arraycopy(data, 0, file, part.partIndex * CHUNK, data.length);
            }
            try {
                if (!hash(file)
                        .equals(metadata(record).getJSONObject("manifest").getString("sha256")))
                    throw new IllegalArgumentException("File integrity check failed");
                AttachmentPreparer.validate(file, record.mime);
            } catch (Exception e) {
                record.state = "FAILED";
                MeshMessage response = status(record);
                actions.transaction(() -> {
                    accept(packet, response, record.id, now);
                    dao().putMedia(record);
                    dao().deleteParts(record.id);
                    dao().mediaStatus(record.id, kind(record.mime), 0, "FAILED");
                });
                return PrivateActions.result(packet, response);
            }
            if (dao().mediaStorageBytes() + file.length + 29 > 16L * 1024 * 1024)
                throw new IllegalStateException("Attachment storage is full");
            record.file = actions.seal("media-file:" + record.id, file);
            record.state = "COMPLETE";
            MeshMessage wire = original(record), ack = completion(record);
            byte[] content =
                    actions.seal(
                            "history:" + record.id,
                            new JSONObject()
                                    .put("wire", wire.toJson())
                                    .put("text", label(record.mime, record.byteCount))
                                    .put("receipt", ack.toJson())
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8));
            actions.transaction(() -> {
                accept(packet, ack, record.id, now);
                dao().putMedia(record);
                dao().deleteParts(record.id);
                dao().updateMediaContent(record.id, content);
                dao().mediaStatus(record.id, kind(record.mime), 100, "READY");
            });
            return new MeshProtocol.Incoming(
                    packet,
                    wire.withPlaintext(label(record.mime, record.byteCount)),
                    ack,
                    null,
                    null);
        }
        MeshMessage response = range || received.cardinality() % 4 == 0 ? status(record) : null;
        actions.transaction(() -> {
            accept(packet, response, record.id, now);
            for (MediaPart part : added) dao().putPart(part);
            dao().putMedia(record);
            dao().mediaStatus(
                            record.id,
                            kind(record.mime),
                            received.cardinality() * 100 / record.chunkCount,
                            "RECEIVING");
        });
        return PrivateActions.result(packet, response);
    }

    public void pump(long now, long elapsed) throws Exception {
        List<MediaRecord> active = dao().activeMedia();
        if (!active.isEmpty()) Collections.rotate(active, -(cursor++ % active.size()));
        Set<String> ids = new HashSet<>();
        for (MediaRecord record : active) ids.add(record.id);
        nextPump.keySet().retainAll(ids);
        pumpDelay = 5000;
        for (MediaRecord record : active) {
            ChatRecord chat = dao().chat(record.id);
            if (record.outgoing && chat != null && chat.status.equals("DELIVERED")) {
                record.state = "COMPLETE";
                actions.transaction(() -> {
                    dao().deleteTransferPackets(record.id);
                    dao().deleteParts(record.id);
                    dao().putMedia(record);
                });
                continue;
            }
            if (now - record.createdAt > LIFETIME
                    || (record.outgoing
                            && record.state.equals("OFFERED")
                            && now - record.createdAt > 600_000)) {
                record.state = "FAILED";
                record.paused = false;
                actions.transaction(() -> {
                    dao().deleteTransferPackets(record.id);
                    dao().deleteParts(record.id);
                    dao().putMedia(record);
                    dao().mediaStatus(record.id, kind(record.mime), 0, "FAILED");
                });
                continue;
            }
            if (!record.outgoing || record.paused) continue;
            boolean ranges = metadata(record).optBoolean("ranges", false);
            com.example.offgrid.mesh.transport.TransferProfile profile =
                    ranges
                            ? profiles.apply(record.targetId)
                            : com.example.offgrid.mesh.transport.TransferProfile.GATT;
            pumpDelay = Math.min(pumpDelay, profile.intervalMs);
            if (elapsed < nextPump.getOrDefault(record.id, 0L)
                    || elapsed < profileDue.getOrDefault(profile, 0L)) continue;
            nextPump.put(record.id, elapsed + profile.intervalMs);
            if (now - record.lastQuery >= 45_000) {
                MeshMessage query =
                        crypto.control(
                                record.targetId,
                                new JSONObject()
                                        .put("kind", "media_query")
                                        .put("mid", record.id)
                                        .toString());
                record.lastQuery = now;
                actions.transaction(() -> {
                    queue(query, record.id, now);
                    dao().putMedia(record);
                });
                // Leave the legacy quota for the query; fast streams have a separate bounded
                // budget.
                if (profile == com.example.offgrid.mesh.transport.TransferProfile.GATT) {
                    profileDue.put(profile, elapsed + profile.intervalMs);
                    continue;
                }
            }
            if (!record.state.equals("TRANSFERRING")) continue;
            BitSet received = bits(record);
            Map<Integer, MediaPart> sent = new HashMap<>();
            List<MediaPart> parts = dao().parts(record.id);
            for (MediaPart part : parts) sent.put(part.partIndex, part);
            long inFlight =
                    parts.stream()
                            .filter(part ->
                                    !received.get(part.partIndex) && now - part.sentAt < 45_000)
                            .count();
            if (inFlight >= profile.windowBlocks) continue;
            int index = -1;
            for (int i = 0; i < record.chunkCount; i++)
                if (!received.get(i)
                        && (!sent.containsKey(i) || now - sent.get(i).sentAt >= 45_000)) {
                    index = i;
                    break;
                }
            if (index < 0) continue;
            int count = 0;
            int cap = Math.min(profile.chunkBytes / CHUNK, (int) (profile.windowBlocks - inFlight));
            while (count < cap && index + count < record.chunkCount) {
                int i = index + count;
                if (received.get(i) || (sent.containsKey(i) && now - sent.get(i).sentAt < 45_000))
                    break;
                count++;
            }
            if (count == 0) continue;
            byte[] file = actions.open("media-file:" + record.id, record.file);
            String data =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    Arrays.copyOfRange(
                                            file,
                                            index * CHUNK,
                                            Math.min(file.length, (index + count) * CHUNK)));
            MeshMessage chunk =
                    crypto.control(
                            record.targetId,
                            new JSONObject()
                                    .put("kind", count > 1 ? "media_range" : "media_chunk")
                                    .put("mid", record.id)
                                    .put("index", index)
                                    .put("data", data)
                                    .toString());
            profileDue.put(profile, elapsed + profile.intervalMs);
            List<MediaPart> replacements = new ArrayList<>();
            Set<String> obsolete = new HashSet<>();
            for (int i = index; i < index + count; i++) {
                if (sent.containsKey(i)) obsolete.add(sent.get(i).packetId);
                MediaPart part = new MediaPart();
                part.mediaId = record.id;
                part.partIndex = i;
                part.sentAt = now;
                part.packetId = chunk.getMessageId();
                replacements.add(part);
            }
            actions.transaction(() -> {
                for (String old : obsolete) dao().deletePending(old);
                queue(chunk, record.id, now);
                for (MediaPart part : replacements) dao().putPart(part);
            });
        }
    }

    byte[] file(String id) throws Exception {
        MediaRecord record = dao().media(id);
        if (record == null || record.file.length == 0 || dao().withdrawal(id) != null)
            throw new IllegalArgumentException("Attachment is not available");
        return actions.open("media-file:" + id, record.file);
    }
}
