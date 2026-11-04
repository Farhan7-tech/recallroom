package in.yusr.recallroom.recall;

import in.yusr.recallroom.config.SentinelProperties;
import in.yusr.recallroom.feeds.FlaggedBatch;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Recalls and the files sentinel shares with the matcher agent, as plain JSON under the data dir:
 * {@code alerts/R-….json} (what the regulator flagged), {@code matches/R-….json} (what the matcher
 * found on our shelves) and {@code recalls/R-….json} (sentinel's own record). Plain files keep the
 * hand-off between a Java agent and a Claude Code agent inspectable with {@code cat}.
 */
@Component
public class RecallStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final Path dataDir;
    private final JsonMapper json;

    public RecallStore(SentinelProperties props, JsonMapper json) {
        this.dataDir = props.dataDir();
        this.json = json.rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();
    }

    public synchronized String nextId() {
        String prefix = "R-" + LocalDate.now().format(DAY) + "-";
        int n = (int) all().stream().filter(r -> r.id.startsWith(prefix)).count() + 1;
        return prefix + n;
    }

    public synchronized void save(Recall recall) {
        write(dataDir.resolve("recalls").resolve(recall.id + ".json"), json.writeValueAsString(recall));
    }

    public Optional<Recall> find(String id) {
        Path file = dataDir.resolve("recalls").resolve(id + ".json");
        return Files.exists(file) ? Optional.of(json.readValue(file.toFile(), Recall.class)) : Optional.empty();
    }

    public List<Recall> all() {
        Path dir = dataDir.resolve("recalls");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> json.readValue(f.toFile(), Recall.class))
                    .sorted(Comparator.comparing((Recall r) -> r.openedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the regulator's list for the matcher and returns its path relative to the repo root. */
    public String writeAlerts(String recallId, String source, List<FlaggedBatch> batches) {
        Path file = dataDir.resolve("alerts").resolve(recallId + ".json");
        write(file, json.writeValueAsString(new AlertFile(recallId, source, batches.size(), batches)));
        return "data/alerts/" + recallId + ".json";
    }

    public Optional<MatchFile> readMatches(String recallId) {
        Path file = dataDir.resolve("matches").resolve(recallId + ".json");
        return Files.exists(file) ? Optional.of(json.readValue(file.toFile(), MatchFile.class)) : Optional.empty();
    }

    /** Batch keys already put in front of the matcher, so automatic polling only raises new ones. */
    public Set<String> seen() {
        Path file = dataDir.resolve("seen-batches.txt");
        try {
            return Files.exists(file) ? Set.copyOf(Files.readAllLines(file)) : Set.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void markSeen(List<FlaggedBatch> batches) {
        Set<String> all = new TreeSet<>(seen());
        all.addAll(batches.stream().map(RecallStore::seenKey).collect(Collectors.toSet()));
        write(dataDir.resolve("seen-batches.txt"), String.join("\n", all) + "\n");
    }

    public static String seenKey(FlaggedBatch b) {
        return b.source() + "|" + b.key();
    }

    /** The recall-log room document sentinel created; only its creator may update it, so the id must survive restarts. */
    public Optional<String> logDocumentId() {
        Path file = dataDir.resolve("recall-log-document-id");
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file).trim()).filter(s -> !s.isEmpty()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void saveLogDocumentId(String id) {
        write(dataDir.resolve("recall-log-document-id"), id);
    }

    /** Helper agent name to the Switch sender id it is pinned to (data/agent-ids.properties, editable by the operator). */
    public synchronized Map<String, String> agentIds() {
        Path file = dataDir.resolve("agent-ids.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                p.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        Map<String, String> out = new TreeMap<>();
        p.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }

    public synchronized void pinAgentId(String agentName, String senderId) {
        Map<String, String> ids = new TreeMap<>(agentIds());
        ids.putIfAbsent(agentName, senderId);
        StringBuilder text = new StringBuilder("# Switch sender id each helper agent is pinned to. Delete a line to re-pin it.\n");
        ids.forEach((k, v) -> text.append(k).append('=').append(v.replace(":", "\\:")).append('\n'));
        write(dataDir.resolve("agent-ids.properties"), text.toString());
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record AlertFile(String recall, String source, int count, List<FlaggedBatch> batches) {
    }

    /** Written by {@code tools/match.mjs}. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchFile(String recall, int flaggedTotal, List<StoreMatch> stores) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record StoreMatch(String store, String name, String room, List<StoreOrder.Line> lines) {
    }
}
