package com.medix.agent;

import java.util.Map;

public interface ModelGateway {
    String complete(String systemPrompt, String userPrompt, Map<String, String> skillMetadata);

    default boolean live() {
        return false;
    }
}
