package com.medix.rag.entity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record MedicalEntities(Map<EntityCategory, List<String>> byCategory) {
    public MedicalEntities {
        Map<EntityCategory, List<String>> normalized = new EnumMap<>(EntityCategory.class);
        for (EntityCategory category : EntityCategory.values()) {
            normalized.put(category, List.copyOf(byCategory.getOrDefault(category, List.of())));
        }
        byCategory = Map.copyOf(normalized);
    }

    public List<String> entityTags() {
        List<String> tags = new ArrayList<>();
        for (EntityCategory category : EntityCategory.values()) tags.addAll(byCategory.get(category));
        return List.copyOf(tags);
    }

    public List<String> values(EntityCategory category) {
        return byCategory.get(category);
    }
}
