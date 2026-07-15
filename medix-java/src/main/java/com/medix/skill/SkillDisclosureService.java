package com.medix.skill;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class SkillDisclosureService {
    private final ResourcePatternResolver resourceResolver;
    private final List<SkillDescriptor> descriptors = new CopyOnWriteArrayList<>();

    public SkillDisclosureService(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    public void loadSkillDocs() throws IOException {
        Resource[] resources = resourceResolver.getResources("classpath*:skills/*/SKILL.md");
        descriptors.clear();
        descriptors.addAll(parseDescriptors(resources));
    }

    public List<SkillDescriptor> list() {
        return List.copyOf(descriptors);
    }

    private String preview(String content) {
        String compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 220 ? compact : compact.substring(0, 220) + "...";
    }

    private List<SkillDescriptor> parseDescriptors(Resource[] resources) throws IOException {
        List<SkillDescriptor> fallback = new ArrayList<>();
        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            fallback.add(new SkillDescriptor(readFrontMatter(content, "name"), readFrontMatter(content, "description"), preview(content)));
        }
        return fallback;
    }

    private String readFrontMatter(String content, String key) {
        String prefix = key + ":";
        for (String line : content.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }
}
