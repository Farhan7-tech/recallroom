package in.yusr.recallroom.switchapi;

import in.yusr.recallroom.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The HTTP half of the Switch agent protocol: registration, posting to rooms, and operations
 * (the agent's tool surface, {@code POST /agents/{id}/ops/{name}}). Events arrive on {@link SwitchSocket}.
 */
@Component
public class SwitchApi {

    private static final Logger log = LoggerFactory.getLogger(SwitchApi.class);

    private final SentinelProperties props;
    private final RestClient http;
    private final JsonMapper json;
    private volatile AgentIdentity identity;

    public SwitchApi(SentinelProperties props, RestClient.Builder builder, JsonMapper json) {
        this.props = props;
        this.http = builder.clone().baseUrl(props.switchServer().url()).build();
        this.json = json;
    }

    /** Loads the saved identity, or registers sentinel with the server the first time it runs. */
    public synchronized AgentIdentity identity() {
        if (identity == null) {
            Path file = props.switchServer().identityFile();
            identity = AgentIdentity.load(file).orElseGet(() -> {
                AgentIdentity fresh = register();
                fresh.save(file);
                return fresh;
            });
        }
        return identity;
    }

    private AgentIdentity register() {
        Path tokenFile = props.switchServer().registrationTokenFile();
        if (tokenFile == null || !Files.isReadable(tokenFile)) {
            throw new IllegalStateException("Sentinel is not registered yet and recallroom.switch.registration-token-file is not readable");
        }
        String token;
        try {
            token = Files.readString(tokenFile).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", props.agent().name());
        body.put("display_name", props.agent().displayName());
        body.put("description", props.agent().description());
        body.put("connector_type", "external");
        body.put("integration_profile", Map.of(
                "connection_model", "always_on",
                "message_exchange", true,
                "pre_invocation_mediation", new String[0],
                "post_invocation_mediation", new String[0],
                "event_reporting", new String[0]));
        JsonNode res = json.readTree(http.post().uri("/agents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body))
                .retrieve()
                .body(String.class));
        log.info("Registered with Switch as agent {}", res.path("id").asString());
        return AgentIdentity.fresh(res.path("id").asString(), res.path("api_key").asString());
    }

    /** Posts a message into a room. Mentions written as "@name" in the text address that participant. */
    public void post(String roomId, String content) {
        AgentIdentity me = identity();
        http.post().uri("/agents/{id}/message", me.agentId())
                .header("Authorization", "Bearer " + me.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("room_id", roomId, "content", content)))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Calls an operation. Room-scoped operations (documents, participants) act on the room the
     * connection is bound to, which {@code connect_to_room} sets.
     */
    public JsonNode op(String name, Map<String, ?> args) {
        AgentIdentity me = identity();
        String res = http.post().uri("/agents/{id}/ops/{op}", me.agentId(), name)
                .header("Authorization", "Bearer " + me.apiKey())
                .header("X-Switch-Connection-Id", me.connectionId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(args))
                .retrieve()
                .body(String.class);
        return json.readTree(res).path("result");
    }

    public String url() {
        return props.switchServer().url();
    }
}
