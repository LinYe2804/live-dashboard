package com.monika.livedashboard.agent.xposed;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

final class DaemonSocket {
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(128);

    static {
        Thread worker = new Thread(DaemonSocket::run, "LiveDashboardSocket");
        worker.setDaemon(true);
        worker.start();
    }

    private DaemonSocket() {}

    static void send(JSONObject event) {
        if (event == null) return;
        String line = event.toString();
        if (!QUEUE.offer(line)) {
            QUEUE.poll();
            QUEUE.offer(line);
        }
    }

    private static void run() {
        while (true) {
            try {
                String line = QUEUE.take();
                try (LocalSocket socket = new LocalSocket()) {
                    socket.connect(new LocalSocketAddress(
                        "live_dashboard",
                        LocalSocketAddress.Namespace.ABSTRACT
                    ));
                    OutputStream output = socket.getOutputStream();
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.write('\n');
                    output.flush();
                } catch (Throwable ignored) {
                    // The KernelSU daemon can be temporarily unavailable during boot or upgrade.
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
