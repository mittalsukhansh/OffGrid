package com.example.offgrid.mesh.transport;

import static org.junit.Assert.*;

import android.os.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.offgrid.FloodGuard;

import org.junit.*;
import org.junit.runner.RunWith;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Real socket/worker lifecycle on loopback; does not simulate WifiP2pManager or a physical radio.
 */
@RunWith(AndroidJUnit4.class)
public class DirectTcpLinkTest {
    HandlerThread thread;
    Handler worker;
    DirectTcpLink a, b;
    int port;
    final InetAddress owner = address("127.0.0.1"), client = address("127.0.0.2");

    static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void setup() throws Exception {
        thread = new HandlerThread("direct-test");
        thread.start();
        worker = new Handler(thread.getLooper());
        try (ServerSocket free = new ServerSocket(0, 1, owner)) {
            port = free.getLocalPort();
        }
        a = link();
        b = link();
    }

    DirectTcpLink link() {
        return new DirectTcpLink(
                worker,
                new FloodGuard(SystemClock::elapsedRealtime),
                new TransportMetrics(SystemClock::elapsedRealtime),
                port,
                (local, prefix, remote) -> local.isLoopbackAddress() && remote.isLoopbackAddress());
    }

    <T> T run(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        worker.post(future);
        return future.get(10, TimeUnit.SECONDS);
    }

    @After
    public void cleanup() throws Exception {
        run(() -> {
            a.stop();
            b.stop();
            return true;
        });
        thread.quitSafely();
        thread.join(10000);
    }

    MeshTransport.Listener listening(
            CountDownLatch peers, AtomicReference<byte[]> bytes, CountDownLatch received) {
        return new MeshTransport.Listener() {
            @Override
            public void onPacket(String peer, byte[] packet) {
                bytes.set(packet);
                received.countDown();
            }

            @Override
            public void onPeerSeen(String peer, int rssi) {
                peers.countDown();
            }

            @Override
            public void onRadioState(boolean active) {}

            @Override
            public void onError(String error) {
                fail(error);
            }
        };
    }

    @Test
    public void groupClientHelloDiscoversBothEndpointsAndCarriesFullStreamPacketWithoutLan()
            throws Exception {
        CountDownLatch peers = new CountDownLatch(2),
                received = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        run(() -> {
            a.start(listening(peers, new AtomicReference<>(), new CountDownLatch(1)));
            b.start(listening(peers, bytes, received));
            a.bind(owner, 8, owner);
            b.bind(client, 8, owner);
            return true;
        });
        assertTrue(peers.await(10, TimeUnit.SECONDS));
        assertEquals(TransferProfile.WIFI_DIRECT, run(() -> a.profile(client.getHostAddress())));
        byte[] payload = new byte[LanFrames.MAX_PACKET_BYTES];
        new Random(42).nextBytes(payload);
        AtomicInteger forwarded = new AtomicInteger();
        run(() -> {
            a.send(
                    client.getHostAddress(),
                    Collections.singletonList(new MeshTransport.Packet("packet", payload)),
                    new MeshTransport.SendListener() {
                        @Override
                        public boolean shouldSend(String id) {
                            return true;
                        }

                        @Override
                        public void onForwarded(String id) {
                            forwarded.incrementAndGet();
                        }

                        @Override
                        public void onComplete(String failure, boolean incompatible) {
                            error.set(failure);
                            finished.countDown();
                        }
                    });
            return true;
        });
        assertTrue(received.await(10, TimeUnit.SECONDS));
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, forwarded.get());
        assertArrayEquals(payload, bytes.get());
    }

    @Test
    public void stoppingLinkUnblocksStalledReadAndCompletesOldRelaySession() throws Exception {
        InetAddress stalled = address("127.0.0.3");
        CountDownLatch connected = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicInteger forwarded = new AtomicInteger();
        ExecutorService remote = Executors.newSingleThreadExecutor();
        try (ServerSocket fake = new ServerSocket(port, 1, stalled)) {
            fake.setSoTimeout(10000);
            Future<?> accepted =
                    remote.submit(() -> {
                        try (Socket socket = fake.accept()) {
                            connected.countDown();
                            release.await(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            run(() -> {
                a.start(
                        listening(
                                new CountDownLatch(1),
                                new AtomicReference<>(),
                                new CountDownLatch(1)));
                a.bind(owner, 8, owner);
                return true;
            });
            try (Socket hello = new Socket()) {
                hello.bind(new InetSocketAddress(stalled, 0));
                hello.connect(new InetSocketAddress(owner, port), 5000);
                hello.setSoTimeout(5000);
                DirectHello.write(new DataOutputStream(hello.getOutputStream()));
                DirectHello.read(new DataInputStream(hello.getInputStream()));
            }
            run(() -> {
                a.send(
                        stalled.getHostAddress(),
                        Collections.singletonList(
                                new MeshTransport.Packet("stalled", new byte[100])),
                        new MeshTransport.SendListener() {
                            @Override
                            public boolean shouldSend(String id) {
                                return true;
                            }

                            @Override
                            public void onForwarded(String id) {
                                forwarded.incrementAndGet();
                            }

                            @Override
                            public void onComplete(String failure, boolean incompatible) {
                                error.set(failure);
                                finished.countDown();
                            }
                        });
                return true;
            });
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            run(() -> {
                a.closeLink();
                return true;
            });
            assertTrue(
                    "Stopped socket did not release relay session",
                    finished.await(5, TimeUnit.SECONDS));
            assertNotNull(error.get());
            assertEquals(0, forwarded.get());
            release.countDown();
            accepted.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            remote.shutdownNow();
        }
    }
}
