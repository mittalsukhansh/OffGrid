package com.example.offgrid.mesh.transport;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import com.example.offgrid.FloodGuard;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Android 29+ LE credit-based streams. Unpaired link; signed/encrypted packets retain trust. */
@SuppressLint("MissingPermission")
final class CocTransport {
    private final Handler worker;
    private final FloodGuard flood;
    private final TransportMetrics metrics;
    private final MeshTransport.Listener listener;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Set<BluetoothSocket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<BluetoothSocket> outbound = ConcurrentHashMap.newKeySet();
    private final Semaphore inbound = new Semaphore(2);
    private ThreadPoolExecutor io;
    private BluetoothAdapter adapter;
    private BluetoothServerSocket server;
    private volatile int generation;
    private int psm = -1;
    private String status = "Stopped";
    private long retryAt;

    CocTransport(
            Handler worker,
            FloodGuard flood,
            TransportMetrics metrics,
            MeshTransport.Listener listener) {
        this.worker = worker;
        this.flood = flood;
        this.metrics = metrics;
        this.listener = listener;
    }

    int psm() {
        return psm;
    }

    String status() {
        return status;
    }

    void start(BluetoothAdapter adapter) {
        if (Build.VERSION.SDK_INT < 29) {
            status = "Unavailable below Android 10; using GATT";
            return;
        }
        if (server != null || SystemClock.elapsedRealtime() < retryAt) return;
        this.adapter = adapter;
        int epoch = ++generation;
        try {
            server = adapter.listenUsingInsecureL2capChannel();
            psm = server.getPsm();
            CocAdvertisement.encode(
                    psm); // Reject unexpected platform PSMs instead of advertising junk.
            io = new ThreadPoolExecutor(0, 6, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
            BluetoothServerSocket accepting = server;
            io.execute(() -> accept(accepting, epoch));
            status = "Listening on dynamic PSM " + psm;
        } catch (IOException | RuntimeException e) {
            stop();
            retryAt = SystemClock.elapsedRealtime() + 60_000;
            status = "L2CAP unavailable; GATT fallback (retry in 60s)";
        }
    }

    void discovered(String address, int remotePsm, int rssi) {
        if (server == null || remotePsm < 128 || remotePsm > 255) return;
        Peer peer = peers.get(address);
        if (peer == null) {
            if (peers.size() >= 128) peers.remove(peers.keySet().iterator().next());
            peer = new Peer();
            peers.put(address, peer);
        }
        peer.psm = remotePsm;
        peer.seenAt = SystemClock.elapsedRealtime();
        if (available(address)) listener.onPeerSeen("coc:" + address, rssi + 128);
    }

    boolean available(String address) {
        Peer peer = peers.get(address);
        long now = SystemClock.elapsedRealtime();
        return server != null
                && peer != null
                && now - peer.seenAt <= 30_000
                && now >= peer.blockedUntil;
    }

    boolean send(
            String address,
            List<MeshTransport.Packet> packets,
            MeshTransport.SendListener callbacks) {
        if (Build.VERSION.SDK_INT < 29 || !available(address)) return false;
        int epoch = generation;
        BluetoothSocket socket;
        try {
            socket =
                    adapter.getRemoteDevice(address)
                            .createInsecureL2capChannel(peers.get(address).psm);
        } catch (IOException | RuntimeException e) {
            block(address);
            return false;
        }
        sockets.add(socket);
        outbound.add(socket);
        MeshTransport.SendListener measured =
                metrics.sending(TransferProfile.COC, packets, callbacks);
        try {
            io.execute(() -> sendBatch(socket, address, packets, measured, epoch));
        } catch (RejectedExecutionException e) {
            close(socket);
            block(address);
            measured.onComplete("L2CAP connection limit", false);
        }
        return true;
    }

    private void block(String address) {
        Peer peer = peers.get(address);
        if (peer != null) peer.blockedUntil = SystemClock.elapsedRealtime() + 60_000;
        status = "L2CAP failure; GATT fallback for 60s";
    }

    private void accept(BluetoothServerSocket accepting, int epoch) {
        try {
            while (epoch == generation) {
                BluetoothSocket socket = accepting.accept();
                sockets.add(socket);
                if (epoch != generation || !inbound.tryAcquire()) {
                    close(socket);
                    continue;
                }
                try {
                    io.execute(() -> receive(socket, epoch));
                } catch (RejectedExecutionException e) {
                    inbound.release();
                    close(socket);
                }
            }
        } catch (IOException | RuntimeException e) {
            worker.post(() -> {
                if (epoch == generation) {
                    stop();
                    retryAt = SystemClock.elapsedRealtime() + 60_000;
                    status = "L2CAP listener failed; GATT fallback";
                }
            });
        }
    }

    private void receive(BluetoothSocket socket, int epoch) {
        Runnable timeout = () -> close(socket);
        worker.postDelayed(timeout, 25_000);
        try {
            DataInputStream in =
                    new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            int count = LanFrames.readHeader(in);
            String peer = "coc:" + socket.getRemoteDevice().getAddress();
            for (int i = 0; i < count; i++) {
                byte[] bytes = LanFrames.readPacket(in);
                if (!call(
                        () -> {
                            if (epoch != generation
                                    || !sockets.contains(socket)
                                    || !flood.allowFrame(peer)) return false;
                            metrics.received(TransferProfile.COC, bytes.length);
                            listener.onPacket(peer, bytes);
                            return true;
                        })) return;
                out.writeByte(1);
                out.flush();
            }
        } catch (Exception ignored) {
            /* No protocol reply to malformed/slow sockets. */
        } finally {
            worker.removeCallbacks(timeout);
            close(socket);
            inbound.release();
        }
    }

    private void sendBatch(
            BluetoothSocket socket,
            String address,
            List<MeshTransport.Packet> packets,
            MeshTransport.SendListener callbacks,
            int epoch) {
        Runnable timeout = () -> close(socket);
        worker.postDelayed(
                timeout,
                25_000); // Covers blocked connect/read/write: BluetoothSocket has no SO_TIMEOUT.
        String failure = null;
        try {
            socket.connect();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            LanFrames.writeHeader(out, packets.size());
            for (MeshTransport.Packet packet : packets) {
                if (!call(
                        () ->
                                epoch == generation
                                        && outbound.contains(socket)
                                        && callbacks.shouldSend(packet.id)))
                    throw new IOException("Cancelled");
                LanFrames.writePacket(out, packet.bytes);
                if (in.readUnsignedByte() != 1) throw new IOException("Stream rejected");
                call(() -> {
                    if (epoch == generation && outbound.contains(socket))
                        callbacks.onForwarded(packet.id);
                    return true;
                });
            }
        } catch (Exception e) {
            failure = "L2CAP transfer failed or timed out";
        } finally {
            worker.removeCallbacks(timeout);
            close(socket);
            String result = failure;
            worker.post(() -> {
                if (result != null && epoch == generation) block(address);
                // Completion must reach the relay even after Bluetooth/network generation changes.
                callbacks.onComplete(result, false);
            });
        }
    }

    private boolean call(Callable<Boolean> action) throws Exception {
        FutureTask<Boolean> future = new FutureTask<>(action);
        worker.post(future);
        try {
            return future.get(8, TimeUnit.SECONDS);
        } finally {
            future.cancel(false);
        }
    }

    private void close(BluetoothSocket socket) {
        sockets.remove(socket);
        outbound.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void cancelSends() {
        for (BluetoothSocket socket : new ArrayList<>(outbound)) close(socket);
    }

    void stop() {
        generation++;
        psm = -1;
        if (server != null)
            try {
                server.close();
            } catch (IOException ignored) {
            }
        server = null;
        for (BluetoothSocket socket : new ArrayList<>(sockets)) close(socket);
        if (io != null) io.shutdownNow();
        peers.clear();
        status = "Stopped";
    }

    private static final class Peer {
        int psm;
        long seenAt, blockedUntil;
    }
}
