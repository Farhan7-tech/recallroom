package in.yusr.recallroom.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("recallroom")
public record SentinelProperties(
        @Name("switch") SwitchServer switchServer,
        Agent agent,
        Rooms rooms,
        List<String> qaHeads,
        Agents agents,
        Path dataDir,
        Path inventoryDir,
        Feeds feeds) {

    public SentinelProperties {
        qaHeads = qaHeads == null ? List.of() : qaHeads.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code recallroom.switch}: "switch" is a Java keyword, hence the different component name. */
    public record SwitchServer(String url, Path registrationTokenFile, Path identityFile) {
    }

    public record Agent(String name, String displayName, String description) {
    }

    public record Rooms(String hq) {
    }

    /** Switch names of the Console-run agents sentinel hands work to. */
    public record Agents(String matcher, String comms, String storeRunner) {
    }

    public record Feeds(String cdscoBaseUrl, String openfdaUrl, int pollMinutes) {
    }
}
