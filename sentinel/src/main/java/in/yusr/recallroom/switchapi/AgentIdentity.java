package in.yusr.recallroom.switchapi;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Who sentinel is on the Switch server. The API key is returned once, at registration, so it is
 * kept on disk; the connection id is reused on every reconnect so the server can resume the stream.
 */
public record AgentIdentity(String agentId, String apiKey, String connectionId) {

    static Optional<AgentIdentity> load(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Optional.of(new AgentIdentity(p.getProperty("agent_id"), p.getProperty("api_key"), p.getProperty("connection_id")));
    }

    static AgentIdentity fresh(String agentId, String apiKey) {
        return new AgentIdentity(agentId, apiKey, UUID.randomUUID().toString());
    }

    void save(Path file) {
        Properties p = new Properties();
        p.setProperty("agent_id", agentId);
        p.setProperty("api_key", apiKey);
        p.setProperty("connection_id", connectionId);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer out = Files.newBufferedWriter(file)) {
                p.store(out, "RecallRoom sentinel's Switch identity. Secret: do not commit.");
            }
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "AgentIdentity[agentId=" + agentId + ", connectionId=" + connectionId + "]";
    }
}
