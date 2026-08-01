package com.medix.rag.entity;

public enum EntityCategory {
    DISEASE("diseases"), DRUG("drugs"), SYMPTOM("symptoms"), EXAMINATION("examinations");

    private final String metadataKey;

    EntityCategory(String metadataKey) {
        this.metadataKey = metadataKey;
    }

    public String metadataKey() {
        return metadataKey;
    }
}
