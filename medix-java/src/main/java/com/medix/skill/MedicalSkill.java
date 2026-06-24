package com.medix.skill;

public interface MedicalSkill {
    String name();

    String description();

    SkillResult invoke(SkillRequest request);
}
