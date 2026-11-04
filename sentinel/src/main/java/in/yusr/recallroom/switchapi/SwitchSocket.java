package in.yusr.recallroom.switchapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The event half of the Switch agent protocol: one WebSocket covering every room sentinel is in.
 *
 * <p>The server pings every 2 s and drops a connection that is silent for 6 s, so pongs are sent
 * straight from the socket's listener while events are handed to a separate executor: a slow
 * feed fetch or HTTP call can never starve the heartbeat. Reconnects reuse the connection id and
 * resume from the last processed sequence, as the protocol asks.
 */
@Component
public class SwitchSocket {

    private static final Logger log = LoggerFactory.getLogger(SwitchSocket.class);

    private final SwitchApi api;
    private final JsonMapper json;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong cursor = new AtomicLong(-1);
    private volatile long generation = -1;
    private volatile boolean stopped;
    private volatile int failures;
    private volatile Consumer<RoomMessage> onMessage = m -> { };
    private volatile Runnable onConnected = () -> { };

    public SwitchSocket(SwitchApi api, JsonMapper json) {
        this.api = api;
        this.json = json;
    }

    public void start(Consumer<RoomMessage> onMessage, Runnable onConnected) {
        this.onMessage = onMessage;
        this.onConnected = onConnected;
        connect();
    }

    public void stop() {
        stopped = true;
        timer.shutdownNow();
    }

    private void connect() {
        if (stopped) {
            return;
        }
        AgentIdentity me = api.identity();
        StringBuilder url = new StringBuilder(api.url().replaceFirst("^http", "ws"))
                .append("/agents/").append(me.agentId()).append("/connection/ws")
                .append("?connection_id=").append(me.connectionId())
                .append("&scope=all&client=recallroom-sentinel");
        if (cursor.get() >= 0) {
            url.append("&start_from=").append(cursor.get());
        }
        if (generation >= 0) {
            url.append("&expected_generation=").append(generation);
        }
        client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + me.apiKey())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url.toString()), new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        log.warn("Could not open the Switch socket: {}", error.getMessage());
                        reconnectLater();
                    }
                });
    }

    private void reconnectLater() {
        if (stopped) {
            return;
        }
        long delay = Math.min(30, 2L << Math.min(failures++, 4));
        log.info("Reconnecting to Switch in {} s", delay);
        timer.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private CompletableFuture<WebSocket> sending = CompletableFuture.completedFuture(null);

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handle(ws, json.readTree(frame));
            }
            ws.request(1);
            return null;
        }

        private void handle(WebSocket ws, JsonNode frame) {
            String event = frame.path("event").asString("");
            JsonNode data = frame.path("data");
            if (frame.has("id")) {
                cursor.accumulateAndGet(frame.path("id").asLong(), Math::max);
            }
            switch (event) {
                case "ping" -> {
                    // One send at a time is allowed on a java.net.http WebSocket, so pongs queue behind each other.
                    String pong = "{\"type\":\"pong\",\"cursor\":" + Math.max(cursor.get(), 0) + "}";
                    sending = sending.thenCompose(previous -> ws.sendText(pong, true));
                }
                case "connection_state" -> {
                    log.info("connection_state: {}", abbreviate(data.toString()));
                    generation = data.path("generation").asLong(-1);
                    if (data.has("cursor")) {
                        cursor.accumulateAndGet(data.path("cursor").asLong(), Math::max);
                    }
                    failures = 0;
                    log.info("Connected to Switch; rooms {}", data.path("rooms"));
                    handlers.submit(onConnected);
                }
                case "message" -> {
                    RoomMessage message = RoomMessage.from(data);
                    log.info("Message #{} in room {} from {} (addressed={}): {}", frame.path("id").asLong(), message.roomId(),
                            message.senderName(), message.addressed(), abbreviate(message.body()));
                    handlers.submit(() -> {
                        try {
                            onMessage.accept(message);
                        } catch (RuntimeException e) {
                            log.error("Handling a message from {} failed", message.senderName(), e);
                        }
                    });
                }
                case "room_join" -> handlers.submit(onConnected); // invited somewhere new: look the rooms up again
                case "evicted" -> {
                    String code = data.path("code").asString("");
                    log.warn("Switch evicted this connection: {} {}", code, data.path("reason").asString(""));
                    if ("taken_over".equals(code)) {
                        stopped = true; // another sentinel took the connection; two would evict each other forever
                    }
                }
                case "refused" -> log.warn("Switch refused the connection: {}", data);
                case "gap" -> {
                    log.warn("Switch reports a gap in the event stream: {}", data.path("reason").asString(""));
                    cursor.set(data.path("resumed_at").asLong(cursor.get()));
                }
                default -> log.info("Switch event {}: {}", event, abbreviate(data.toString()));
            }
        }

        private static String abbreviate(String s) {
            String one = s == null ? "" : s.replaceAll("\\s+", " ");
            return one.length() > 120 ? one.substring(0, 120) + "…" : one;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            log.info("Switch socket closed: {} {}", status, reason);
            reconnectLater();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("Switch socket error: {}", error.toString());
            reconnectLater();
        }
    }
}
