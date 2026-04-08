package com.spaceai.core;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * cronologia conversazionepersistenza —— Corrisponde a space-ai Sessionepersistenzameccanismo。
 * <p>
 * verràConversazionemessaggioSerializzazionecome JSON Archiviazionea ~/.space-ai-java/conversations/ Directory。
 * supportasalva、caricaeelencacronologiaConversazione。
 * <p>
 * Archiviazioneformato：messaggioSerializzazionecome -izzato JSON su，packageRuolo、contenutoechiamata strumentoInformazione。
 */
public class ConversationPersistence {

    private static final Logger log = LoggerFactory.getLogger(ConversationPersistence.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path conversationsDir;

    public ConversationPersistence() {
        this(Path.of(System.getProperty("user.home"), ".space-ai-java", "conversations"));
    }

    public ConversationPersistence(Path conversationsDir) {
        this.conversationsDir = conversationsDir;
        try {
            Files.createDirectories(conversationsDir);
        } catch (IOException e) {
            log.warn("Failed to create conversation storage directory: {}", e.getMessage());
        }
    }

    /**
     * salva la cronologia della conversazione nel file.
     *
     * @param messages messaggioLista
     * @param summary  riepilogo conversazione（inFile/Listavisualizza）
     * @return percorso del file salvato, restituisce null in caso di fallimento
     */
    public Path save(List<Message> messages, String summary) {
        if (messages == null || messages.isEmpty()) return null;

        String timestamp = FILE_DATE_FMT.format(LocalDateTime.now());
        String safeSummary = sanitizeFilename(summary);
        String filename = timestamp + "_" + safeSummary + ".json";
        Path file = conversationsDir.resolve(filename);

        try {
            List<MessageRecord> records = messages.stream()
                    .map(this::toRecord)
                    .filter(Objects::nonNull)
                    .toList();

            ConversationFile conv = new ConversationFile(
                    LocalDateTime.now().toString(),
                    summary,
                    System.getProperty("user.dir"),
                    records
            );

            MAPPER.writeValue(file.toFile(), conv);
            log.info("Conversation saved: {}", file.getFileName());
            return file;
        } catch (IOException e) {
            log.error("Failed to save conversation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * caricauna voltacronologia conversazione。
     *
     * @return messaggioLista，nessunocronologiaritornavuotoLista
     */
    public List<Message> loadLatest() {
        Path latest = findLatestFile();
        if (latest == null) return List.of();
        return loadFromFile(latest);
    }

    /**
     * carica la cronologia della conversazione dal file specificato.
     */
    public List<Message> loadFromFile(Path file) {
        try {
            ConversationFile conv = MAPPER.readValue(file.toFile(), ConversationFile.class);
            return conv.messages().stream()
                    .map(this::fromRecord)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("Failed to load conversation: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * elenca tuttisalvaConversazione（）。
     */
    public List<ConversationSummary> listConversations() {
        List<ConversationSummary> summaries = new ArrayList<>();

        try (Stream<Path> paths = Files.list(conversationsDir)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(file -> {
                        try {
                            ConversationFile conv = MAPPER.readValue(file.toFile(), ConversationFile.class);
                            summaries.add(new ConversationSummary(
                                    file.getFileName().toString(),
                                    conv.summary(),
                                    conv.savedAt(),
                                    conv.workingDir(),
                                    conv.messages().size()
                            ));
                        } catch (IOException e) {
                            log.debug("Skipping invalid conversation file: {}", file.getFileName());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list conversations: {}", e.getMessage());
        }

        return summaries;
    }

    /** ottieniConversazioneArchiviazioneDirectory */
    public Path getConversationsDir() {
        return conversationsDir;
    }

    // ==================== Serializzazione/Deserializzazione ====================

    private MessageRecord toRecord(Message msg) {
        return switch (msg) {
            case SystemMessage sm -> new MessageRecord("system", sm.getText(), null, null);
            case UserMessage um -> new MessageRecord("user", um.getText(), null, null);
            case AssistantMessage am -> {
                List<ToolCallRecord> toolCalls = null;
                if (am.hasToolCalls()) {
                    toolCalls = am.getToolCalls().stream()
                            .map(tc -> new ToolCallRecord(tc.id(), tc.name(), tc.arguments()))
                            .toList();
                }
                yield new MessageRecord("assistant", am.getText(), toolCalls, null);
            }
            case ToolResponseMessage trm -> {
                List<ToolResponseRecord> responses = trm.getResponses().stream()
                        .map(tr -> new ToolResponseRecord(tr.id(), tr.name(), tr.responseData()))
                        .toList();
                yield new MessageRecord("tool_response", null, null, responses);
            }
            default -> null;
        };
    }

    private Message fromRecord(MessageRecord record) {
        return switch (record.role()) {
            case "system" -> new SystemMessage(record.content() != null ? record.content() : "");
            case "user" -> new UserMessage(record.content() != null ? record.content() : "");
            case "assistant" -> {
                if (record.toolCalls() != null && !record.toolCalls().isEmpty()) {
                    List<AssistantMessage.ToolCall> toolCalls = record.toolCalls().stream()
                            .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.arguments()))
                            .toList();
                    yield AssistantMessage.builder()
                            .content(record.content() != null ? record.content() : "")
                            .toolCalls(toolCalls)
                            .build();
                }
                yield new AssistantMessage(record.content() != null ? record.content() : "");
            }
            case "tool_response" -> {
                if (record.toolResponses() != null) {
                    List<ToolResponseMessage.ToolResponse> responses = record.toolResponses().stream()
                            .map(tr -> new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), tr.data()))
                            .toList();
                    yield ToolResponseMessage.builder().responses(responses).build();
                }
                yield null;
            }
            default -> null;
        };
    }

    private Path findLatestFile() {
        try (Stream<Path> paths = Files.list(conversationsDir)) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "conversation";
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_")
                .substring(0, Math.min(name.length(), 40));
    }

    // ==================== JSON dati ====================

    public record ConversationFile(
            String savedAt,
            String summary,
            String workingDir,
            List<MessageRecord> messages
    ) {}

    public record MessageRecord(
            String role,
            String content,
            List<ToolCallRecord> toolCalls,
            List<ToolResponseRecord> toolResponses
    ) {}

    public record ToolCallRecord(String id, String name, String arguments) {}
    public record ToolResponseRecord(String id, String name, String data) {}

    /** riepilogo conversazione（inListavisualizza） */
    public record ConversationSummary(
            String filename,
            String summary,
            String savedAt,
            String workingDir,
            int messageCount
    ) {}
}
