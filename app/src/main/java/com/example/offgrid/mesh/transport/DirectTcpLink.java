package com.example.offgrid.mesh.transport;

import android.os.Handler;
import android.os.SystemClock;

import com.example.offgrid.FloodGuard;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * TCP limited to the current P2P interface. Group ownership/discovery lives in WifiDirectTransport.
 */
final class DirectTcpLink implements MeshTransport {
    private final Handler worker;
    private final FloodGuard flood;
    private final TransportMetrics metrics;
    private final int port;
    private final SubnetAdmission admission;

    interface SubnetAdmission {
        boolean contains(InetAddress local, int prefix, InetAddress remote);
    }

    private final Map<String, Long> peers = new LinkedHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Socket> outgoing = ConcurrentHashMap.newKeySet();
    private final Semaphore inbound = new Semaphore(3);
    private ThreadPoolExecutor io;
    private Listener listener;
    private ServerSocket server;
    private InetAddress local, owner;
    private int prefix;
    private volatile int generation;
    private long lastHello;
    private boolean probing;
    private String status = "No P2P link";

    DirectTcpLink(Handler worker, FloodGuard flood, TransportMetrics metrics) {
        this(worker, flood, metrics, DirectHello.PORT, LanSubnet::contains);
    }

    DirectTcpLink(
            Handler worker,
            FloodGuard flood,
            TransportMetrics metrics,
            int port,
            SubnetAdmission admission) {
        this.worker = worker;
        this.flood = flood;
        this.metrics = metrics;
        this.port = port;
        this.admission = admission;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
        listener.onRadioState(false);
    }

    String status() {
        return status;
    }

    int peerCount() {
        return peers.size();
    }

    boolean ready() {
        return server != null;
    }

    void bind(InetAddress address, int prefix, InetAddress ownerAddress) {
        if (server != null
                && address.equals(local)
                && ownerAddress.equals(owner)
                && this.prefix == prefix) return;
        closeLink();
        this.local = address;
        this.prefix = prefix;
        this.owner = ownerAddress;
        int epoch = ++generation;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(local, port), 3);
            // One accept + three inbound + three relay outbound + one hello probe, with no backlog.
            io = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
            ServerSocket accepting = server;
            io.execute(() -> accept(accepting, epoch));
            status = "P2P socket ready on " + local.getHostAddress();
            listener.onRadioState(true);
            maintain();
        } catch (IOException | RuntimeException e) {
            closeLink();
            status = "Cannot bind P2P socket; check permissions or port conflict";
        }
    }

    private boolean valid(InetAddress address) {
        return local != null
                && !local.equals(address)
                && admission.contains(local, prefix, address);
    }

    private boolean register(InetAddress address) {
        if (server == null || !valid(address)) return false;
        String peer = address.getHostAddress();
        if (!peers.containsKey(peer) && peers.size() >= 32) return false;
        peers.put(peer, SystemClock.elapsedRealtime());
        status = "P2P link ready on " + local.getHostAddress();
        listener.onPeerSeen(peer, 0);
        return true;
    }

    @Override
    public TransferProfile profile(String peer) {
        return server != null && peers.containsKey(peer)
                ? TransferProfile.WIFI_DIRECT
                : TransferProfile.GATT;
    }

    @Override
    public void maintain() {
        if (server == null) return;
        long now = SystemClock.elapsedRealtime();
        peers.entrySet().removeIf(e -> now - e.getValue() > 30_000);
        for (String peer : peers.keySet()) listener.onPeerSeen(peer, 0);
        if (!local.equals(owner) && !probing && (lastHello == 0 || now - lastHello >= 10_000))
            hello();
    }

    private void hello() {
        probing = true;
        lastHello = SystemClock.elapsedRealtime();
        int epoch = generation;
        Socket socket = new Socket();
        sockets.add(socket);
        Runnable deadline = () -> close(socket);
        worker.postDelayed(deadline, 12_000);
        InetAddress target = owner, source = local;
        try {
            io.execute(() -> {
                try (Socket ignored = socket) {
                    socket.bind(new InetSocketAddress(source, 0));
                    socket.connect(new InetSocketAddress(target, port), 5000);
                    socket.setSoTimeout(5000);
                    DirectHello.write(new DataOutputStream(socket.getOutputStream()));
                    DirectHello.read(new DataInputStream(socket.getInputStream()));
                    call(() -> epoch == generation && sockets.contains(socket) && register(target));
                } catch (Exception ignored) {
                    worker.post(() -> {
                        if (epoch == generation)
                            status = "Group formed; waiting for OffGrid on group owner";
                    });
                } finally {
                    worker.removeCallbacks(deadline);
                    close(socket);
                    worker.post(() -> {
                        if (epoch == generation) probing = false;
                    });
                }
            });
        } catch (RejectedExecutionException e) {
            worker.removeCallbacks(deadline);
            close(socket);
            probing = false;
        }
    }

    private void accept(ServerSocket accepting, int epoch) {
        try {
            while (epoch == generation) {
                Socket socket = accepting.accept();
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
                    closeLink();
                    status = "P2P listener stopped; retrying group link";
                }
            });
        }
    }

    private void receive(Socket socket, int epoch) {
        Runnable deadline = () -> close(socket);
        worker.postDelayed(deadline, 25_000);
        try (Socket ignored = socket) {
            socket.setSoTimeout(8000);
            InetAddress remote = socket.getInetAddress();
            if (!call(
                    () ->
                            epoch == generation
                                    && valid(remote)
                                    && flood.allowFrame("direct:" + remote.getHostAddress())))
                return;
            DataInputStream in =
                    new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            in.mark(8);
            int magic = in.readInt();
            in.reset();
            if (magic == DirectHello.MAGIC) {
                DirectHello.read(in);
                if (call(() -> epoch == generation && register(remote))) DirectHello.write(out);
                return;
            }
            int count = LanFrames.readHeader(in);
            for (int i = 0; i < count; i++) {
                byte[] packet = LanFrames.readPacket(in);
                if (!call(
                        () -> {
                            if (epoch != generation
                                    || !sockets.contains(socket)
                                    || !flood.allowFrame("direct:" + remote.getHostAddress())
                                    || !register(remote)) return false;
                            metrics.received(TransferProfile.WIFI_DIRECT, packet.length);
                            listener.onPacket(remote.getHostAddress(), packet);
                            return true;
                        })) return;
                out.writeByte(1);
                out.flush();
            }
        } catch (Exception ignored) {
            /* Reject malformed, unauthorized-subnet or stalled sockets. */
        } finally {
            worker.removeCallbacks(deadline);
            close(socket);
            inbound.release();
        }
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener callbacks) {
        if (server == null
                || !peers.containsKey(peer)
                || packets.isEmpty()
                || packets.size() > LanFrames.MAX_BATCH) {
            callbacks.onComplete("Wi-Fi Direct peer unavailable", false);
            return;
        }
        SendListener measured = metrics.sending(TransferProfile.WIFI_DIRECT, packets, callbacks);
        Socket socket = new Socket();
        sockets.add(socket);
        outgoing.add(socket);
        measured.onAttempt(packets.get(0).id, () -> close(socket));
        int epoch = generation;
        InetAddress source = local;
        try {
            io.execute(() -> sendBatch(socket, source, peer, packets, measured, epoch));
        } catch (RejectedExecutionException e) {
            close(socket);
            measured.onComplete("Wi-Fi Direct session limit", false);
        }
    }

    private void sendBatch(
            Socket socket,
            InetAddress source,
            String peer,
            List<Packet> packets,
            SendListener callbacks,
            int epoch) {
        Runnable deadline = () -> close(socket);
        worker.postDelayed(deadline, 25_000);
        String failure = null;
        try (Socket ignored = socket) {
            socket.bind(new InetSocketAddress(source, 0));
            socket.connect(new InetSocketAddress(InetAddress.getByName(peer), port), 5000);
            socket.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            LanFrames.writeHeader(out, packets.size());
            for (Packet packet : packets) {
                if (!call(
                        () ->
                                epoch == generation
                                        && outgoing.contains(socket)
                                        && callbacks.shouldSend(packet.id)))
                    throw new IOException("Cancelled");
                if (!call(
                        () -> {
                            if (callbacks.isCancelled()) return false;
                            callbacks.onAttempt(packet.id, () -> close(socket));
                            return !callbacks.isCancelled();
                        })) throw new IOException("Cancelled");
                LanFrames.writePacket(out, packet.bytes);
                if (in.readUnsignedByte() != 1) throw new IOException("Rejected");
                call(() -> {
                    if (epoch == generation && outgoing.contains(socket))
                        callbacks.onForwarded(packet.id);
                    return true;
                });
            }
        } catch (Exception e) {
            failure = "Wi-Fi Direct transfer failed or timed out";
        } finally {
            worker.removeCallbacks(deadline);
            close(socket);
            String result = failure;
            worker.post(() -> callbacks.onComplete(result, false));
        }
    }

    private boolean call(Callable<Boolean> action) throws Exception {
        FutureTask<Boolean> task = new FutureTask<>(action);
        worker.post(task);
        try {
            return task.get(8, TimeUnit.SECONDS);
        } finally {
            task.cancel(false);
        }
    }

    private void close(Socket socket) {
        sockets.remove(socket);
        outgoing.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    void closeLink() {
        generation++;
        status = "No P2P link";
        if (server != null)
            try {
                server.close();
            } catch (IOException ignored) {
            }
        server = null;
        for (Socket socket : new ArrayList<>(sockets)) close(socket);
        if (io != null) io.shutdownNow();
        peers.clear();
        probing = false;
        lastHello = 0;
        if (listener != null) listener.onRadioState(false);
    }

    @Override
    public void cancelSends() {
        for (Socket socket : new ArrayList<>(outgoing)) close(socket);
    }

    @Override
    public void stop() {
        closeLink();
        status = "No P2P link";
    }
}
