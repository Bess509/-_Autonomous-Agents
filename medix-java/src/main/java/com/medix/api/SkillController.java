package com.medix.api;

import com.medix.harness.HarnessValidator;
import com.medix.skill.SkillDisclosureService;
import com.medix.skill.SkillRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillRegistry skillRegistry;
    private final SkillDisclosureService disclosureService;
    private final HarnessValidator harnessValidator;

    public SkillController(
            SkillRegistry skillRegistry,
            SkillDisclosureService disclosureService,
            HarnessValidator harnessValidator
    ) {
        this.skillRegistry = skillRegistry;
        this.disclosureService = disclosureService;
        this.harnessValidator = harnessValidator;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of(
                "registered", skillRegistry.metadata(),
                "progressiveDisclosure", disclosureService.list(),
                "agentBoundaries", harnessValidator.allowedSkills()
        );
    }
}
