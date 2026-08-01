package com.medix.rag;

import com.medix.rag.entity.EntityCategory;
import com.medix.rag.entity.MedicalEntities;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class MetadataEntities {
    private MetadataEntities() { }

    static Map<String, Object> medicalMetadata(MedicalEntities entities, String department,
                                                String diseaseCategory, String questionType) {
        Map<String, Object> categories = new LinkedHashMap<>();
        for (EntityCategory category : EntityCategory.values()) categories.put(category.metadataKey(), entities.values(category));
        Map<String, Object> medical = new LinkedHashMap<>();
        medical.put("entity_tags", entities.entityTags());
        medical.put("entities", categories);
        putIfPresent(medical, "department", department);
        putIfPresent(medical, "disease_category", diseaseCategory);
        putIfPresent(medical, "question_type", questionType);
        return Map.copyOf(medical);
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }

    static Set<String> tags(Map<String, Object> metadata) {
        Object medical = metadata.get("medical");
        if (!(medical instanceof Map<?, ?> medicalMap) || !(medicalMap.get("entity_tags") instanceof Iterable<?> tags)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        tags.forEach(value -> result.add(String.valueOf(value)));
        return Set.copyOf(result);
    }
}
