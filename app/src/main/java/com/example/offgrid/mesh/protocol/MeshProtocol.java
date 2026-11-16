package com.example.offgrid.mesh.protocol;

import com.example.offgrid.CryptoManager;
import com.example.offgrid.FloodGuard;
import com.example.offgrid.MeshMessage;
import com.example.offgrid.MessageCache;
import com.example.offgrid.PacketSecurity;
import com.example.offgrid.mesh.MeshConfig;
import com.example.offgrid.mesh.storage.MeshStorage;

import java.security.GeneralSecurityException;

/** Packet policy and atomic acceptance. All receive/persist calls run on the mesh worker. */
public final class MeshProtocol {
    public enum SendStatus {
        ACCEPTED,
        INVALID_MESSAGE,
        RECIPIENT_KEY_UNKNOWN,
        ENCRYPTION_FAILED
    }

    public static final class Outgoing {
        public final SendStatus status;
        public final MeshMessage wire;
        public final MeshMessage display;

        private Outgoing(SendStatus status, MeshMessage wire, MeshMessage display) {
            this.status = status;
            this.wire = wire;
            this.display = display;
        }
    }

    public static final class Incoming {
        public static final Incoming IGNORED = new Incoming(null, null, null, null, null);
        public final MeshMessage wire;
        public final MeshMessage display;
        public final MeshMessage relay;
        public final String deliveredId;
        public final String error;

        public Incoming(
                MeshMessage wire,
                MeshMessage display,
                MeshMessage relay,
                String deliveredId,
                String error) {
            this.wire = wire;
            this.display = display;
            this.relay = relay;
            this.deliveredId = deliveredId;
            this.error = error;
        }
    }

    public interface Extensions {
        Incoming beforePrivate(MeshMessage packet, long now) throws Exception;

        Incoming media(MeshMessage packet, String plaintext, long now) throws Exception;

        Incoming control(MeshMessage packet, long now) throws Exception;
    }

    private Extensions extensions;

    public void extensions(Extensions extensions) {
        this.extensions = extensions;
    }

    private final CryptoManager crypto;
    private final MeshStorage storage;
    private final FloodGuard flood;
    private final MessageCache seen = new MessageCache();
    private final String localId;
    private boolean clockWarningShown;

    public MeshProtocol(CryptoManager crypto, MeshStorage storage, FloodGuard flood) {
        this.crypto = crypto;
        this.storage = storage;
        this.flood = flood;
        localId = crypto.getLocalId();
    }

    public String localId() {
        return localId;
    }

    public Outgoing publicMessage(String text) {
        try {
            MeshMessage wire = crypto.signPacket(MeshMessage.create(localId, text));
            return new Outgoing(SendStatus.ACCEPTED, wire, wire);
        } catch (IllegalArgumentException e) {
            return rejected(SendStatus.INVALID_MESSAGE);
        } catch (GeneralSecurityException e) {
            return rejected(SendStatus.ENCRYPTION_FAILED);
        }
    }

    public Outgoing privateMessage(String target, String text, long now) {
        if (!MeshMessage.isValidTargetId(target)
                || localId.equals(target)
                || text.trim().isEmpty()
                || text.length() > MeshMessage.MAX_TEXT_LENGTH) {
            return rejected(SendStatus.INVALID_MESSAGE);
        }
        if (!crypto.hasKeyFor(target)) return rejected(SendStatus.RECIPIENT_KEY_UNKNOWN);
        try {
            String id = MeshMessage.newId(localId);
            MeshMessage wire =
                    crypto.signPacket(
                            MeshMessage.createPrivate(
                                    id,
                                    localId,
                                    target,
                                    crypto.encrypt(id, target, now, text),
                                    crypto.getPublicKeyBase64(),
                                    MeshMessage.DEFAULT_TTL,
                                    now));
            return new Outgoing(SendStatus.ACCEPTED, wire, wire.withPlaintext(text));
        } catch (IllegalArgumentException e) {
            return rejected(SendStatus.INVALID_MESSAGE);
        } catch (GeneralSecurityException e) {
            return rejected(SendStatus.ENCRYPTION_FAILED);
        }
    }

    private static Outgoing rejected(SendStatus status) {
        return new Outgoing(status, null, null);
    }

    public MeshMessage announcement() throws GeneralSecurityException {
        return crypto.signPacket(
                MeshMessage.createKeyAnnounce(
                        localId, crypto.getPublicKeyBase64(), MeshConfig.KEY_ANNOUNCE_TTL));
    }

    public MeshMessage inventory(String target, String body) throws GeneralSecurityException {
        return crypto.signPacket(
                MeshMessage.createInventory(localId, target, body, crypto.getPublicKeyBase64()));
    }

    public boolean validPending(MeshMessage packet, long now) {
        return crypto.verifyPacket(packet)
                && PacketSecurity.namespaced(packet)
                && PacketSecurity.fresh(packet, now);
    }

    public void remember(String id) {
        seen.markSeen(id);
    }

    public void maintenance() {
        seen.purgeExpired();
    }

    public void clear() {
        seen.clear();
    }

    public Incoming receive(String peer, byte[] bytes, long now) throws Exception {
        boolean stream =
                peer.startsWith("wifi:")
                        || peer.startsWith("direct:")
                        || peer.startsWith("ble:coc:");
        if (!flood.allowPacket(peer, stream)) return Incoming.IGNORED;
        final MeshMessage packet;
        try {
            packet = MeshMessage.fromBytes(bytes);
        } catch (IllegalArgumentException e) {
            return Incoming.IGNORED;
        }
        if (packet.getSenderId().equals(localId)
                || !PacketSecurity.namespaced(packet)
                || !PacketSecurity.fresh(packet, now)
                || !crypto.verifyPacket(packet)
                || !flood.allowSender(packet.getSenderId(), stream && packet.isControl()))
            return Incoming.IGNORED;
        if (packet.isInventory()) return new Incoming(packet, null, null, null, null);
        boolean sequenceNew = storage.canAcceptSequence(packet);
        if (sequenceNew && !crypto.registerPacketKeys(packet)) return Incoming.IGNORED;
        if (extensions != null && localId.equals(packet.getTargetId())) {
            if (packet.isControl()) return extensions.control(packet, now);
            if (packet.isPrivate()) {
                Incoming early = extensions.beforePrivate(packet, now);
                if (early != null) return early;
            }
        }
        if (packet.isPrivate()
                && localId.equals(packet.getTargetId())
                && storage.hasHistory(packet.getMessageId())) {
            MeshMessage ack = storage.cachedReceipt(packet, now);
            if (ack == null) return Incoming.IGNORED;
            storage.save(ack, null, false, ack, now);
            return new Incoming(packet, null, ack, null, null);
        }
        if (!sequenceNew || storage.replayed(packet.getMessageId())) return Incoming.IGNORED;
        if (packet.isAcknowledgement() && localId.equals(packet.getTargetId())) {
            try {
                String id =
                        storage.confirmReceipt(packet, crypto.decryptAcknowledgement(packet), now);
                if (id == null) return Incoming.IGNORED;
                remember(packet.getMessageId());
                return new Incoming(packet, null, null, id, null);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                return Incoming.IGNORED;
            }
        }
        if (seen.hasSeen(packet.getMessageId()) || storage.known(packet.getMessageId()))
            return Incoming.IGNORED;
        MeshMessage display = null;
        MeshMessage relay = packet.withDecrementedTtl();
        if (!relay.isRelayable()) relay = null;
        try {
            switch (packet.getType()) {
                case MeshMessage.TYPE_PUBLIC:
                    display = packet;
                    break;
                case MeshMessage.TYPE_KEY_ANNOUNCE:
                    if (!crypto.registerAnnouncedKey(packet.getSenderId(), packet.getText()))
                        return Incoming.IGNORED;
                    break;
                case MeshMessage.TYPE_PRIVATE:
                case MeshMessage.TYPE_MEDIA:
                    if (localId.equals(packet.getTargetId())) {
                        String plaintext = crypto.decrypt(packet);
                        if (packet.isMedia()) {
                            if (extensions == null) return Incoming.IGNORED;
                            return extensions.media(packet, plaintext, now);
                        }
                        display = packet.withPlaintext(plaintext);
                        relay = crypto.createAcknowledgement(packet);
                    }
                    break;
                case MeshMessage.TYPE_ACK:
                case MeshMessage.TYPE_CONTROL:
                    break;
                default:
                    return Incoming.IGNORED;
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Incoming.IGNORED;
        }
        storage.save(packet, display, false, relay, now);
        remember(packet.getMessageId());
        return new Incoming(packet, display, relay, null, null);
    }
}
