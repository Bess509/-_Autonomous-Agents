package com.medix.rag.entity;

public record EntityHit(String canonicalName, EntityCategory category, int startOffset, int endOffset) {
    public int length() {
        return endOffset - startOffset;
    }
}
