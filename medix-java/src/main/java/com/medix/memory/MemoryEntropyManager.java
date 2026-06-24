package com.medix.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemoryEntropyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryEntropyManager.class);
    private static final List<String> SYMPTOM_KEYWORDS = List.of(
            "chest pain", "breathing difficulty", "headache", "fever", "syncope",
            "high blood pressure", "胸痛", "呼吸困难", "头痛", "发热", "昏厥", "高血压"
    );
    private static final List<String> RECOMMENDATION_KEYWORDS = List.of(
            "recommend", "urgent", "seek", "warning", "care", "建议", "就医", "急诊", "提醒"
    );

    private final MemoryProperties properties;

    public MemoryEntropyManager() {
        this(new MemoryProperties());
    }

    @Autowired
    public MemoryEntropyManager(MemoryProperties properties) {
        this.properties = properties;
    }

    public EntropyManagementResult autoClean(String sessionId, List<ChatMessage> messages) {
        List<ChatMessage> source = messages == null ? List.of() : List.copyOf(messages);
        if (!properties.entropy().enabled()) {
            return new EntropyManagementResult(source, estimate(source), 0, 0);
        }

        List<ChatMessage> deduplicated = deduplicate(source);
        int removedDuplicates = source.size() - deduplicated.size();
        List<ChatMessage> compressed = compress(deduplicated);
        int compressedMessages = Math.max(0, deduplicated.size() - compressed.size());
        EntropyReport report = estimate(compressed);

        if (report.entropyLevel() == EntropyLevel.HIGH) {
            LOGGER.warn(
                    "High memory entropy detected: sessionId={}, totalMessages={}, duplicateRate={}, averageMessageLength={}, recommendations={}",
                    sessionId,
                    report.totalMessages(),
                    String.format(Locale.ROOT, "%.2f", report.duplicateRate()),
                    String.format(Locale.ROOT, "%.2f", report.averageMessageLength()),
                    report.recommendations()
            );
        }

        return new EntropyManagementResult(List.copyOf(compressed), report, removedDuplicates, compressedMessages);
    }

    public EntropyReport estimate(List<ChatMessage> messages) {
        List<ChatMessage> source = messages == null ? List.of() : messages;
        if (source.isEmpty()) {
            return new EntropyReport(0, 0, 0, 0.0, 0.0, EntropyLevel.LOW, List.of());
        }

        int totalMessages = source.size();
        Set<String> hashes = new LinkedHashSet<>();
        int totalLength = 0;
        for (ChatMessage message : source) {
            hashes.add(messageHash(message));
            totalLength += content(message).length();
        }
        int uniqueMessages = hashes.size();
        int duplicateCount = totalMessages - uniqueMessages;
        double duplicateRate = (double) duplicateCount / totalMessages;
        double averageLength = (double) totalLength / totalMessages;

        List<String> recommendations = new ArrayList<>();
        EntropyLevel level = EntropyLevel.LOW;
        MemoryProperties.Entropy entropy = properties.entropy();

        if (totalMessages > entropy.highMessageThreshold()
                || duplicateRate > entropy.duplicateRateThreshold()
                || averageLength > entropy.averageLengthThreshold()) {
            level = EntropyLevel.HIGH;
        } else if (totalMessages > entropy.mediumMessageThreshold() || duplicateCount > 0) {
            level = EntropyLevel.MEDIUM;
        }

        if (totalMessages > entropy.highMessageThreshold()) {
            recommendations.add("compress history because message count is above high threshold");
        } else if (totalMessages > entropy.mediumMessageThreshold()) {
            recommendations.add("consider compressing history because message count is rising");
        }
        if (duplicateRate > entropy.duplicateRateThreshold()) {
            recommendations.add("deduplicate repeated tool or assistant messages");
        }
        if (averageLength > entropy.averageLengthThreshold()) {
            recommendations.add("summarize long messages to reduce prompt size");
        }

        return new EntropyReport(
                totalMessages,
                uniqueMessages,
                duplicateCount,
                duplicateRate,
                averageLength,
                level,
                List.copyOf(recommendations)
        );
    }

    public List<ChatMessage> deduplicate(List<ChatMessage> messages) {
        List<ChatMessage> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (seen.add(messageHash(message))) {
                unique.add(message);
            }
        }
        return unique;
    }

    public List<ChatMessage> compress(List<ChatMessage> messages) {
        int recentLimit = properties.entropy().recentMessageLimit();
        if (messages.size() <= recentLimit) {
            return List.copyOf(messages);
        }
        List<ChatMessage> older = messages.subList(0, messages.size() - recentLimit);
        List<ChatMessage> recent = messages.subList(messages.size() - recentLimit, messages.size());
        List<ChatMessage> compressed = new ArrayList<>();
        compressed.add(new ChatMessage("system", summarizeOlderMessages(older)));
        compressed.addAll(recent);
        return compressed;
    }

    private String summarizeOlderMessages(List<ChatMessage> older) {
        String questions = collectByRole(older, "user", 160);
        String symptoms = collectKeywords(older, SYMPTOM_KEYWORDS);
        String recommendations = collectRecommendations(older, 180);
        return "Conversation summary: questions=" + blankDefault(questions)
                + "; symptoms=" + blankDefault(symptoms)
                + "; recommendations=" + blankDefault(recommendations);
    }

    private String collectByRole(List<ChatMessage> messages, String role, int maxLength) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (role.equals(message.role())) {
                appendFragment(builder, content(message));
            }
        }
        return abbreviate(builder.toString(), maxLength);
    }

    private String collectKeywords(List<ChatMessage> messages, List<String> keywords) {
        String joined = messages.stream()
                .map(MemoryEntropyManager::content)
                .reduce("", (left, right) -> left + " " + right)
                .toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String keyword : keywords) {
            if (joined.contains(keyword.toLowerCase(Locale.ROOT))) {
                found.add(keyword);
            }
        }
        return String.join(", ", found);
    }

    private String collectRecommendations(List<ChatMessage> messages, int maxLength) {
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            String content = content(message);
            String normalized = content.toLowerCase(Locale.ROOT);
            boolean recommendation = !"user".equals(message.role())
                    && RECOMMENDATION_KEYWORDS.stream().anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
            if (recommendation) {
                appendFragment(builder, content);
            }
        }
        return abbreviate(builder.toString(), maxLength);
    }

    private static void appendFragment(StringBuilder builder, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(" | ");
        }
        builder.append(content.replaceAll("\\s+", " ").trim());
    }

    private static String blankDefault(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String messageHash(ChatMessage message) {
        return md5((message.role() == null ? "" : message.role()) + ":" + content(message));
    }

    private static String content(ChatMessage message) {
        return message.content() == null ? "" : message.content();
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 algorithm is unavailable", ex);
        }
    }
}
