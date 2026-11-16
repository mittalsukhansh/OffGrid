package com.example.offgrid.mesh.transport;

import static com.example.offgrid.mesh.MeshConfig.MESSAGE_CHAR_UUID;
import static com.example.offgrid.mesh.MeshConfig.SERVICE_UUID;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.function.Consumer;

/** One native GATT client; no peer policy, packet parsing, database or UI state. */
@SuppressLint("MissingPermission")
final class GattOutboundSession extends BluetoothGattCallback {
    private final Context context;
    private final Handler worker;
    private final BluetoothDevice device;
    private final String address;
    private final List<MeshTransport.Packet> packets;
    private final MeshTransport.SendListener listener;
    private final Consumer<GattOutboundSession> onClosed;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private int mtu = 23, packetIndex, frameIndex, forwarded;
    private List<byte[]> frames;
    private boolean finished, closed, incompatible;
    private final Runnable timeout = () -> finish("timeout");
    private final Runnable close = this::closeNow;

    GattOutboundSession(
            Context context,
            Handler worker,
            BluetoothDevice device,
            String address,
            List<MeshTransport.Packet> packets,
            MeshTransport.SendListener listener,
            Consumer<GattOutboundSession> onClosed) {
        this.context = context;
        this.worker = worker;
        this.device = device;
        this.address = address;
        this.packets = packets;
        this.listener = listener;
        this.onClosed = onClosed;
    }

    void start() {
        armTimeout();
        try {
            gatt =
                    device.connectGatt(
                            context,
                            false,
                            this,
                            BluetoothDevice.TRANSPORT_LE,
                            BluetoothDevice.PHY_LE_1M_MASK,
                            worker);
        } catch (RuntimeException e) {
            Log.w("OffGridMesh", "connectGatt failed", e);
        }
        if (gatt == null) finish("connectGatt failed");
    }

    void abort() {
        boolean notify = !finished;
        finished = true;
        worker.removeCallbacks(timeout);
        closeNow();
        // With multiple transports, Bluetooth can stop while LAN keeps the relay running.
        // The relay must release this session instead of permanently occupying a slot.
        if (notify) listener.onComplete("Bluetooth session cancelled", false);
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
        run(() -> {
            if (finished) {
                if (state == BluetoothGatt.STATE_DISCONNECTED) closeNow();
                return;
            }
            if (gatt == null) gatt = g;
            if (state == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                armTimeout();
                if (!g.requestMtu(247)) discover(g);
            } else if (state == BluetoothGatt.STATE_DISCONNECTED
                    || status != BluetoothGatt.GATT_SUCCESS) {
                finish(
                        status == BluetoothGatt.GATT_SUCCESS
                                ? "disconnected"
                                : "gatt status " + status);
            }
        });
    }

    @Override
    public void onMtuChanged(BluetoothGatt g, int value, int status) {
        run(() -> {
            if (finished) return;
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = Math.max(23, Math.min(517, value));
            armTimeout();
            discover(g);
        });
    }

    private void discover(BluetoothGatt g) {
        if (!g.discoverServices()) finish("discoverServices failed");
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt g, int status) {
        run(() -> {
            if (finished) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("service discovery failed");
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            characteristic = service == null ? null : service.getCharacteristic(MESSAGE_CHAR_UUID);
            if (characteristic == null) {
                incompatible = true;
                finish("peer has no OffGrid service");
                return;
            }
            nextPacket();
        });
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
        run(() -> {
            if (finished) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish("write failed: " + status);
                return;
            }
            frameIndex++;
            if (frameIndex < frames.size()) writeFrame();
            else {
                forwarded++;
                listener.onForwarded(packets.get(packetIndex).id);
                packetIndex++;
                nextPacket();
            }
        });
    }

    private void nextPacket() {
        while (packetIndex < packets.size() && !listener.shouldSend(packets.get(packetIndex).id))
            packetIndex++;
        if (packetIndex >= packets.size()) {
            finish(null);
            return;
        }
        frames = FrameCodec.fragment(packets.get(packetIndex).bytes, mtu);
        frameIndex = 0;
        writeFrame();
    }

    @SuppressWarnings("deprecation")
    private void writeFrame() {
        armTimeout();
        byte[] frame = frames.get(frameIndex);
        try {
            boolean accepted;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                accepted =
                        gatt.writeCharacteristic(
                                        characteristic,
                                        frame,
                                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                characteristic.setValue(frame);
                accepted = gatt.writeCharacteristic(characteristic);
            }
            if (!accepted) finish("write rejected");
        } catch (RuntimeException e) {
            finish("write failed");
        }
    }

    private void armTimeout() {
        worker.removeCallbacks(timeout);
        worker.postDelayed(timeout, FrameCodec.TIMEOUT_MS);
    }

    private void finish(String failure) {
        if (finished) return;
        finished = true;
        worker.removeCallbacks(timeout);
        Log.d(
                "OffGridMesh",
                "Session with "
                        + address
                        + " ended: "
                        + (failure == null ? "ok" : failure)
                        + ", forwarded="
                        + forwarded);
        listener.onComplete(failure, incompatible);
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (RuntimeException ignored) {
            }
            worker.postDelayed(close, 500);
        } else closeNow();
    }

    private void closeNow() {
        worker.removeCallbacks(close);
        worker.removeCallbacks(timeout);
        BluetoothGatt socket = gatt;
        gatt = null;
        if (socket != null) {
            try {
                socket.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                socket.close();
            } catch (RuntimeException ignored) {
            }
        }
        if (!closed) {
            closed = true;
            onClosed.accept(this);
        }
    }

    private void run(Runnable task) {
        if (Looper.myLooper() == worker.getLooper()) task.run();
        else worker.post(task);
    }
}
