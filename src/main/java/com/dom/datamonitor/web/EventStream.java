package com.dom.datamonitor.web;

import com.dom.datamonitor.engine.MonitorEngine;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Live updates for the browser as Server-Sent Events ({@code GET /api/events}).
 *
 * <ul>
 *   <li>{@code dashboard} – full state (settings, modules, history); sent on connect and
 *       after every add / edit / delete, so every open browser stays in sync</li>
 *   <li>{@code result}    – one module's latest result: {@code {"id":…, "sample":{…}}}</li>
 * </ul>
 *
 * <p>Each client has a bounded queue drained by its own request thread, so a slow
 * browser never blocks the SQL executor threads; a client whose queue overflows is
 * dropped and simply reconnects (EventSource does this automatically).
 */
class EventStream implements MonitorEngine.Listener {

    private static final int  QUEUE_SIZE        = 512;
    private static final long HEARTBEAT_SECONDS = 15;

    private final MonitorEngine    engine;
    private final Supplier<String> dashboardJson;
    private final Set<Client>      clients = ConcurrentHashMap.newKeySet();

    EventStream(MonitorEngine engine, Supplier<String> dashboardJson) {
        this.engine        = engine;
        this.dashboardJson = dashboardJson;
    }

    void start() {
        engine.addListener(this);
    }

    void stop() {
        engine.removeListener(this);
        clients.forEach(Client::close);
    }

    /** Streams events to one browser until it disconnects. Blocks the calling request thread. */
    void handle(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);   // 0 = chunked, open-ended

        Client client = new Client();
        client.offer(frame("dashboard", dashboardJson.get()));
        clients.add(client);
        try (OutputStream out = ex.getResponseBody()) {
            while (!client.closed) {
                String frame = client.queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                // A comment line keeps proxies from timing out and detects closed tabs
                out.write((frame != null ? frame : ": keep-alive\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Browser went away
        } finally {
            clients.remove(client);
        }
    }

    int clientCount() {
        return clients.size();
    }

    @Override
    public void onResult(String moduleId, MonitorEngine.Sample sample) {
        broadcast(frame("result", ApiHandler.resultJson(moduleId, sample)));
    }

    @Override
    public void onLayoutChanged() {
        broadcast(frame("dashboard", dashboardJson.get()));
    }

    private void broadcast(String frame) {
        for (Client c : clients) c.offer(frame);
    }

    private static String frame(String event, String json) {
        return "event: " + event + "\ndata: " + json + "\n\n";
    }

    private static final class Client {
        final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        volatile boolean closed;

        void offer(String frame) {
            if (!queue.offer(frame)) close();   // too far behind: drop; the browser reconnects
        }

        void close() {
            closed = true;
            queue.offer(": bye\n\n");   // wake the writer so it notices
        }
    }
}
