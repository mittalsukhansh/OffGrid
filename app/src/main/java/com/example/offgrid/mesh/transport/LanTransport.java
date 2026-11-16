package com.example.offgrid.mesh.transport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;

import com.example.offgrid.FloodGuard;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in Wi-Fi LAN discovery and bounded TCP sessions. Worker owns discovery and routing state. */
@SuppressWarnings("deprecation")
public final class LanTransport implements MeshTransport {
    public static final String TYPE = "_offgrid._tcp.";
    private final Context context;
    private final Handler worker;
    private final FloodGuard flood;
    private final TransportMetrics metrics;
    private final ConnectivityManager connectivity;
    private final NsdManager nsd;
    private final String instance = "OffGrid-" + UUID.randomUUID().toString().substring(0, 12);
    private final Map<String, Endpoint> endpoints = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolves = new ArrayDeque<>();
    private final Set<String> foundServices = new HashSet<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Socket> outbound = ConcurrentHashMap.newKeySet();
    private final Semaphore inboundSlots = new Semaphore(3);
    private ThreadPoolExecutor io;
    private Listener listener;
    private Network network;
    private LinkAddress address;
    private volatile int generation;
    private boolean started, enabled, resolving;
    private ServerSocket server;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private String registeredName;
    private String status = "Off";
    private long retryAfter, resolveDeadline;

    public LanTransport(
            Context context, Handler worker, FloodGuard flood, TransportMetrics metrics) {
        this.context = context.getApplicationContext();
        this.worker = worker;
        this.flood = flood;
        this.metrics = metrics;
        connectivity = context.getSystemService(ConnectivityManager.class);
        nsd = context.getSystemService(NsdManager.class);
    }

    @Override
    public TransferProfile profile(String peer) {
        boolean known =
                endpoints.values().stream()
                        .anyMatch(e ->
                                peer.equals(e.host.getHostAddress())
                                        || peer.equals(e.host.getHostAddress() + ":" + e.port));
        return enabled && server != null && known ? TransferProfile.WIFI : TransferProfile.GATT;
    }

    public String status() {
        return status;
    }

    public int peerCount() {
        return endpoints.size();
    }

    public void enabled(boolean value) {
        enabled = value;
        if (!started) return;
        if (!enabled) closeNetwork();
        maintain();
    }

    @Override
    public void start(Listener listener) {
        if (started) return;
        this.listener = listener;
        started = true;
        io = new ThreadPoolExecutor(0, 7, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
        try {
            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
            connectivity.registerNetworkCallback(request, networks, worker);
        } catch (RuntimeException e) {
            problem("Wi-Fi monitoring unavailable");
        }
        listener.onRadioState(false);
        maintain();
    }

    private final ConnectivityManager.NetworkCallback networks =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network candidate, LinkProperties properties) {
                    if (!started) return;
                    LinkAddress next = null;
                    for (LinkAddress link : properties.getLinkAddresses())
                        if (link.getAddress() instanceof Inet4Address
                                && link.getAddress().isSiteLocalAddress()) {
                            next = link;
                            break;
                        }
                    if (next == null) {
                        if (network == null || candidate.equals(network)) {
                            closeNetwork();
                            network = candidate;
                            address = null;
                            maintain();
                        }
                        return;
                    }
                    if (!candidate.equals(network) || !next.equals(address)) {
                        closeNetwork();
                        network = candidate;
                        address = next;
                    }
                    maintain();
                }

                @Override
                public void onLost(Network lost) {
                    if (lost.equals(network)) {
                        closeNetwork();
                        network = null;
                        address = null;
                        maintain();
                    }
                }
            };

    @Override
    public void maintain() {
        if (!started) return;
        if (!enabled) {
            status = "Off · enable local Wi-Fi sharing";
            return;
        }
        if (network == null || address == null) {
            status =
                    network == null
                            ? "Waiting for a Wi-Fi network"
                            : "Wi-Fi needs a private IPv4 address";
            return;
        }
        if (server == null && android.os.SystemClock.elapsedRealtime() >= retryAfter) openNetwork();
        if (server != null) {
            if (resolving && android.os.SystemClock.elapsedRealtime() >= resolveDeadline) {
                failDiscovery("Wi-Fi peer resolution timed out; retrying");
                return;
            }
            resolveNext(generation);
            for (Map.Entry<String, Endpoint> entry : endpoints.entrySet())
                listener.onPeerSeen(entry.getKey(), 0);
        }
    }

    private void openNetwork() {
        final int epoch = ++generation;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address.getAddress(), 0), 3);
            ServerSocket accepting = server;
            io.execute(() -> accept(accepting, epoch));
            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName(instance);
            service.setServiceType(TYPE);
            service.setPort(server.getLocalPort());
            if (Build.VERSION.SDK_INT >= 33) service.setNetwork(network);
            service.setAttribute("framing", "1");
            registration = registration(epoch);
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
            discovery = discovery(epoch);
            if (Build.VERSION.SDK_INT >= 33)
                nsd.discoverServices(
                        TYPE, NsdManager.PROTOCOL_DNS_SD, network, worker::post, discovery);
            else nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
            status = "Discovering on local Wi-Fi";
            listener.onRadioState(true);
        } catch (IOException | RuntimeException e) {
            closeNetwork();
            retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000;
            problem("Cannot start local Wi-Fi sharing; check local-network permission");
        }
    }

    private NsdManager.RegistrationListener registration(int epoch) {
        return new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo service) {
                post(epoch, () -> registeredName = service.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo service, int code) {
                post(epoch, () -> failDiscovery("Wi-Fi advertisement failed (" + code + ")"));
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo service) {}

            @Override
            public void onUnregistrationFailed(NsdServiceInfo service, int code) {}
        };
    }

    private NsdManager.DiscoveryListener discovery(int epoch) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {}

            @Override
            public void onDiscoveryStopped(String type) {}

            @Override
            public void onStartDiscoveryFailed(String type, int code) {
                post(epoch, () -> failDiscovery("Wi-Fi discovery failed (" + code + ")"));
            }

            @Override
            public void onStopDiscoveryFailed(String type, int code) {}

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                post(
                        epoch,
                        () -> {
                            if (!TYPE.replace(".", "")
                                            .equals(service.getServiceType().replace(".", ""))
                                    || instance.equals(service.getServiceName())
                                    || Objects.equals(registeredName, service.getServiceName())
                                    || resolves.size() >= 32
                                    || foundServices.size() >= 64
                                    || !foundServices.add(service.getServiceName())) return;
                            resolves.add(service);
                            resolveNext(epoch);
                        });
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                post(
                        epoch,
                        () -> {
                            foundServices.remove(service.getServiceName());
                            resolves.removeIf(
                                    e -> e.getServiceName().equals(service.getServiceName()));
                            endpoints
                                    .values()
                                    .removeIf(e -> e.name.equals(service.getServiceName()));
                        });
            }
        };
    }

    private void resolveNext(int epoch) {
        if (resolving || resolves.isEmpty() || epoch != generation) return;
        resolving = true;
        resolveDeadline = android.os.SystemClock.elapsedRealtime() + 20_000;
        NsdServiceInfo service = resolves.remove();
        try {
            nsd.resolveService(
                    service,
                    new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int code) {
                            post(
                                    epoch,
                                    () -> {
                                        resolving = false;
                                        resolveNext(epoch);
                                    });
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            post(
                                    epoch,
                                    () -> {
                                        resolving = false;
                                        InetAddress host = info.getHost();
                                        if (foundServices.contains(info.getServiceName())
                                                && localPeer(host)
                                                && info.getPort() > 0
                                                && info.getPort() <= 65535
                                                && !host.equals(address.getAddress())
                                                && endpoints.size() < 64) {
                                            String key =
                                                    host.getHostAddress() + ":" + info.getPort();
                                            endpoints.put(
                                                    key,
                                                    new Endpoint(
                                                            info.getServiceName(),
                                                            host,
                                                            info.getPort()));
                                            listener.onPeerSeen(key, 0);
                                        }
                                        resolveNext(epoch);
                                    });
                        }
                    });
        } catch (RuntimeException e) {
            resolving = false;
            problem("Wi-Fi peer resolution failed");
        }
    }

    private boolean localPeer(InetAddress host) {
        return address != null
                && LanSubnet.contains(address.getAddress(), address.getPrefixLength(), host);
    }

    private void accept(ServerSocket accepting, int epoch) {
        try {
            while (!accepting.isClosed()) {
                Socket socket = accepting.accept();
                sockets.add(socket);
                if (!inboundSlots.tryAcquire()) {
                    close(socket);
                    continue;
                }
                boolean handedOff = false;
                try {
                    // IO owns the permit until receive() takes over. A worker timeout must
                    // never strand a permit or restart an otherwise healthy listener.
                    boolean admitted =
                            call(() -> epoch == generation && localPeer(socket.getInetAddress()));
                    if (admitted) {
                        io.execute(() -> receive(socket, epoch));
                        handedOff = true;
                    }
                } catch (Exception ignored) {
                    // Busy/closed worker or exhausted IO slots: reject just this connection.
                } finally {
                    if (!handedOff) {
                        inboundSlots.release();
                        close(socket);
                    }
                }
            }
        } catch (Exception e) {
            if (!accepting.isClosed())
                post(epoch, () -> failDiscovery("Wi-Fi listener stopped; retrying"));
        }
    }

    private void receive(Socket socket, int epoch) {
        Runnable deadline = () -> close(socket);
        worker.postDelayed(deadline, 25_000);
        try (Socket ignored = socket) {
            socket.setSoTimeout(8_000);
            DataInputStream in =
                    new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            int count = LanFrames.readHeader(in);
            String peer = socket.getInetAddress().getHostAddress();
            for (int i = 0; i < count; i++) {
                byte[] packet = LanFrames.readPacket(in);
                boolean accepted =
                        call(() -> {
                            if (generation != epoch
                                    || !started
                                    || !flood.allowFrame("wifi:" + peer)) return false;
                            metrics.received(TransferProfile.WIFI, packet.length);
                            listener.onPacket(peer, packet);
                            return true;
                        });
                if (!accepted) {
                    return;
                }
                out.writeByte(
                        1); // Byte transfer confirmation only, not an authenticated delivery ACK.
                out.flush();
            }
        } catch (Exception ignored) {
            /* Bad/truncated/slow peers are closed, never retried inbound. */
        } finally {
            worker.removeCallbacks(deadline);
            sockets.remove(socket);
            inboundSlots.release();
        }
    }

    @Override
    public void send(String peer, List<Packet> packets, SendListener callbacks) {
        Endpoint endpoint = endpoints.get(peer);
        if (!enabled
                || server == null
                || endpoint == null
                || packets.isEmpty()
                || packets.size() > LanFrames.MAX_BATCH) {
            callbacks.onComplete("Wi-Fi peer unavailable", false);
            return;
        }
        callbacks = metrics.sending(TransferProfile.WIFI, packets, callbacks);
        final SendListener measured = callbacks;
        final int epoch = generation;
        try {
            Socket socket = network.getSocketFactory().createSocket();
            sockets.add(socket);
            outbound.add(socket);
            try {
                io.execute(() -> sendBatch(socket, endpoint, packets, measured, epoch));
            } catch (RejectedExecutionException e) {
                close(socket);
                callbacks.onComplete("Wi-Fi connection limit reached", false);
            }
        } catch (IOException | RuntimeException e) {
            callbacks.onComplete("Wi-Fi socket unavailable", false);
        }
    }

    private void sendBatch(
            Socket socket, Endpoint peer, List<Packet> packets, SendListener callbacks, int epoch) {
        String failure = null;
        Runnable deadline = () -> close(socket);
        worker.postDelayed(deadline, 25_000);
        try (Socket ignored = socket) {
            socket.connect(new InetSocketAddress(peer.host, peer.port), 5_000);
            socket.setSoTimeout(8_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            LanFrames.writeHeader(out, packets.size());
            for (Packet packet : packets) {
                if (!call(
                        () ->
                                generation == epoch
                                        && outbound.contains(socket)
                                        && callbacks.shouldSend(packet.id)))
                    throw new IOException("Transfer cancelled");
                LanFrames.writePacket(out, packet.bytes);
                if (in.readUnsignedByte() != 1) throw new IOException("Wi-Fi transfer rejected");
                call(() -> {
                    if (generation == epoch && outbound.contains(socket))
                        callbacks.onForwarded(packet.id);
                    return true;
                });
            }
        } catch (Exception e) {
            failure = "Wi-Fi connection failed or timed out";
        } finally {
            worker.removeCallbacks(deadline);
            close(socket);
            String result = failure;
            // Always release the relay session, even after network loss or Wi-Fi disable.
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

    private void post(int epoch, Runnable task) {
        worker.post(() -> {
            if (started && epoch == generation) task.run();
        });
    }

    private void problem(String message) {
        status = message;
        if (listener != null) listener.onError(message);
    }

    private void failDiscovery(String message) {
        closeNetwork();
        retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000;
        problem(message);
    }

    private void close(Socket socket) {
        sockets.remove(socket);
        outbound.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void closeNetwork() {
        generation++;
        if (discovery != null)
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        if (registration != null)
            try {
                nsd.unregisterService(registration);
            } catch (RuntimeException ignored) {
            }
        discovery = null;
        registration = null;
        registeredName = null;
        resolves.clear();
        foundServices.clear();
        endpoints.clear();
        resolving = false;
        if (server != null)
            try {
                server.close();
            } catch (IOException ignored) {
            }
        server = null;
        for (Socket socket : new ArrayList<>(sockets)) close(socket);
        if (listener != null) listener.onRadioState(false);
    }

    @Override
    public void cancelSends() {
        for (Socket socket : new ArrayList<>(outbound)) close(socket);
    }

    @Override
    public void stop() {
        started = false;
        closeNetwork();
        try {
            connectivity.unregisterNetworkCallback(networks);
        } catch (RuntimeException ignored) {
        }
        if (io != null) io.shutdownNow();
        network = null;
        address = null;
        status = "Off";
    }

    private static final class Endpoint {
        final String name;
        final InetAddress host;
        final int port;

        Endpoint(String name, InetAddress host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }
}
