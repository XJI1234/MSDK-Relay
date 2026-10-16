package com.skycommand.networkprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;

/** Foreground-only network probe; it has no DJI or relay runtime dependencies. */
public final class ProbeActivity extends Activity {
    private static final String TAG = "SkyNetworkProbe";
    private static final int PORT = 39081;
    private static final class Run {
        volatile boolean active = true;
        DatagramSocket udp;
        volatile WebSocket webSocket;
    }
    private Run currentRun;
    private TextView output;
    private final StringBuilder screenLog = new StringBuilder();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        output = new TextView(this);
        output.setPadding(24, 24, 24, 24);
        output.setTextSize(15);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        setContentView(scroll);
    }

    @Override public void onStart() {
        super.onStart();
        Run run = new Run();
        currentRun = run;
        // Open before starting the thread so onStop cannot miss a late-created socket.
        try {
            run.udp = new DatagramSocket(null);
            run.udp.bind(new InetSocketAddress(PORT));
            report("UDP_READY port=" + PORT + " foreground-only");
            DatagramSocket current = run.udp;
            new Thread(() -> receive(current), "network-probe-udp").start();
        } catch (Exception error) {
            if (run.udp != null) run.udp.close();
            report("UDP_ERROR " + error.getClass().getSimpleName() + " " + error.getMessage());
        }
        String computer = getIntent().getStringExtra("computer_ipv4");
        if (computer != null && validIpv4(computer)) {
            new Thread(() -> handshake(run, computer), "network-probe-ws").start();
        } else {
            report("WS_SKIPPED supply computer_ipv4 with current numeric WLAN IPv4");
        }
    }

    @Override public void onStop() {
        Run run = currentRun;
        if (run != null) {
            run.active = false;
            if (run.udp != null) run.udp.close();
            if (run.webSocket != null) run.webSocket.cancel();
        }
        report("PROBE_STOPPED activity no longer foreground");
        super.onStop();
    }

    private void receive(DatagramSocket socket) {
        Map<String, Set<Integer>> groups = new HashMap<>();
        byte[] buffer = new byte[2048];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() > 1024) continue;
                JSONObject payload = new JSONObject(new String(packet.getData(), packet.getOffset(),
                    packet.getLength(), StandardCharsets.UTF_8));
                if (!"SKY_COMMAND_NETWORK_PROBE".equals(payload.optString("kind"))) continue;
                String testId = payload.getString("testId");
                if (!testId.matches("[0-9a-fA-F-]{36}")) continue;
                int sequence = payload.getInt("sequence");
                if (sequence < 1 || sequence > 20) continue;
                String group = packet.getAddress().getHostAddress() + "/" + testId;
                if (!groups.containsKey(group) && groups.size() >= 32) continue;
                Set<Integer> received = groups.computeIfAbsent(group, ignored -> new HashSet<>());
                boolean fresh = received.add(sequence);
                report("UDP_RECEIVED testId=" + testId + " seq=" + sequence + " unique=" + received.size()
                    + "/20 source=" + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                    + " receivedAtMs=" + System.currentTimeMillis() + " duplicate=" + !fresh);
            } catch (Exception error) {
                if (!socket.isClosed()) report("UDP_ERROR " + error.getClass().getSimpleName());
            }
        }
    }

    private void handshake(Run run, String computer) {
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).build();
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicBoolean paired = new AtomicBoolean();
        try {
            if (!run.active) return;
            Request request = new Request.Builder().url("ws://" + computer + ":8080/relay").build();
            run.webSocket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    report("WS_OPEN http=" + response.code());
                    String hello = "{\"type\":\"hello\",\"deviceId\":\"network-probe-"
                        + UUID.randomUUID() + "\",\"protocolVersion\":\"1\"}";
                    if (socket.send(ByteString.of(hello.getBytes(StandardCharsets.UTF_8)))) {
                        report("HELLO_SENT binary protocolVersion=1");
                    }
                    else { report("HELLO_SEND_FAILED"); done.countDown(); }
                }
                @Override public void onMessage(WebSocket socket, ByteString bytes) {
                    try {
                        JSONObject frame = new JSONObject(bytes.utf8());
                        if ("paired".equals(frame.optString("type"))
                            && !frame.optString("sessionId").isEmpty()
                            && "1".equals(frame.optString("protocolVersion", "1"))) {
                            paired.set(true);
                            report("WS_PAIRED valid sessionId, protocolVersion=1");
                            done.countDown();
                        } else {
                            report("WS_UNEXPECTED_FRAME");
                            done.countDown();
                        }
                    } catch (Exception error) { report("WS_INVALID_FRAME"); done.countDown(); }
                }
                @Override public void onMessage(WebSocket socket, String text) {
                    report("WS_UNEXPECTED_TEXT_FRAME");
                    done.countDown();
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) {
                    report("WS_CLOSING code=" + code);
                    socket.close(code == 1005 ? 1000 : code, null);
                    done.countDown();
                }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    report("WS_CLOSED code=" + code);
                    closed.countDown();
                    done.countDown();
                }
                @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                    report("WS_FAILED " + error.getClass().getSimpleName() + " " + error.getMessage());
                    closed.countDown();
                    done.countDown();
                }
            });
            if (!run.active) run.webSocket.cancel();
            if (!done.await(10, TimeUnit.SECONDS)) report("WS_TIMEOUT no paired within 10 seconds");
            report("WS_RESULT paired=" + paired.get());
            run.webSocket.close(1000, "network probe complete");
            if (!closed.await(2, TimeUnit.SECONDS)) run.webSocket.cancel();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (run.webSocket != null) run.webSocket.cancel();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private static boolean validIpv4(String value) {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }

    private void report(String line) {
        Log.i(TAG, line);
        runOnUiThread(() -> {
            screenLog.append(line).append('\n');
            if (screenLog.length() > 18000) screenLog.delete(0, screenLog.length() - 12000);
            output.setText(screenLog.toString());
        });
    }
}
