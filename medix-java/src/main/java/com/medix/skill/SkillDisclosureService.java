package com.medix.skill;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.utils.Skills;
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
    public void loadSkillDocs() throws Exception {
        Resource[] resources = resourceResolver.getResources("classpath*:skills/*/SKILL.md");
        descriptors.clear();
        try {
            for (SkillsTool.Skill skill : Skills.loadDirectories(skillDirectories(resources))) {
                String description = String.valueOf(skill.frontMatter().getOrDefault("description", ""));
                descriptors.add(new SkillDescriptor(skill.name(), description, preview(skill.content())));
            }
        } catch (RuntimeException ex) {
            descriptors.addAll(fallbackDescriptors(resources));
        }
    }

    public List<SkillDescriptor> list() {
        return List.copyOf(descriptors);
    }

    private String preview(String content) {
        String compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 220 ? compact : compact.substring(0, 220) + "...";
    }

    private List<String> skillDirectories(Resource[] resources) throws IOException {
        Set<String> directories = new LinkedHashSet<>();
        for (Resource resource : resources) {
            directories.add(resource.getFile().getParentFile().getAbsolutePath());
        }
        return List.copyOf(directories);
    }

    private List<SkillDescriptor> fallbackDescriptors(Resource[] resources) throws IOException {
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
