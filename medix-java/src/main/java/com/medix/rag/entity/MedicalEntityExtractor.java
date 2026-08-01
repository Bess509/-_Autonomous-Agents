package com.medix.rag.entity;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Local dictionary entity extractor using an Aho-Corasick trie. */
@Component
public class MedicalEntityExtractor {
    private final Node root = new Node();
    private boolean initialized;

    @PostConstruct
    public synchronized void initialize() {
        if (initialized) return;
        load("entities/diseases.txt", EntityCategory.DISEASE);
        load("entities/drugs.txt", EntityCategory.DRUG);
        load("entities/symptoms.txt", EntityCategory.SYMPTOM);
        load("entities/examinations.txt", EntityCategory.EXAMINATION);
        buildFailureLinks();
        initialized = true;
    }

    public MedicalEntities extract(String text) {
        List<EntityHit> hits = new ArrayList<>();
        Node current = root;
        String value = text == null ? "" : text;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            while (current != root && !current.children.containsKey(c)) current = current.failure;
            current = current.children.getOrDefault(c, root);
            for (Pattern pattern : current.outputs) {
                hits.add(new EntityHit(pattern.canonicalName, pattern.category,
                        index - pattern.matchedText.length() + 1, index + 1));
            }
        }
        return group(longestNonOverlapping(hits));
    }

    private void load(String resource, EntityCategory category) {
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("Missing medical entity dictionary: " + resource);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            reader.lines().map(String::trim).filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(line -> addLine(line, category));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read medical entity dictionary: " + resource, exception);
        }
    }

    private void addLine(String line, EntityCategory category) {
        String[] names = line.split("\\|");
        String canonical = names[0].trim();
        for (String name : names) {
            String alias = name.trim();
            if (alias.isBlank()) continue;
            Node node = root;
            for (int index = 0; index < alias.length(); index++) node = node.children.computeIfAbsent(alias.charAt(index), ignored -> new Node());
            node.outputs.add(new Pattern(canonical, alias, category));
        }
    }

    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        root.failure = root;
        for (Node child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node node = queue.remove();
            for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();
                Node fallback = node.failure;
                while (fallback != root && !fallback.children.containsKey(c)) fallback = fallback.failure;
                child.failure = fallback.children.getOrDefault(c, root);
                child.outputs.addAll(child.failure.outputs);
                queue.add(child);
            }
        }
    }

    private List<EntityHit> longestNonOverlapping(List<EntityHit> hits) {
        hits.sort(Comparator.comparingInt(EntityHit::startOffset).thenComparing(Comparator.comparingInt(EntityHit::length).reversed()));
        List<EntityHit> kept = new ArrayList<>();
        for (EntityHit hit : hits) {
            boolean overlaps = kept.stream().anyMatch(existing -> hit.startOffset() < existing.endOffset() && existing.startOffset() < hit.endOffset());
            if (!overlaps) kept.add(hit);
        }
        return kept;
    }

    private MedicalEntities group(List<EntityHit> hits) {
        Map<EntityCategory, Set<String>> values = new EnumMap<>(EntityCategory.class);
        for (EntityCategory category : EntityCategory.values()) values.put(category, new LinkedHashSet<>());
        hits.forEach(hit -> values.get(hit.category()).add(hit.canonicalName()));
        Map<EntityCategory, List<String>> grouped = new EnumMap<>(EntityCategory.class);
        values.forEach((category, names) -> grouped.put(category, List.copyOf(names)));
        return new MedicalEntities(grouped);
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private final List<Pattern> outputs = new ArrayList<>();
        private Node failure;
    }

    private record Pattern(String canonicalName, String matchedText, EntityCategory category) { }
}
