package com.medix.rag.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MedicalEntityExtractorTest {
    @Test
    void extractsSynonymsAndKeepsTheLongestOverlappingEntity() {
        MedicalEntityExtractor extractor = new MedicalEntityExtractor();
        extractor.initialize();

        MedicalEntities result = extractor.extract("服用二甲双胍缓释片后，空腹血糖仍然偏高");

        assertThat(result.values(EntityCategory.DRUG)).containsExactly("二甲双胍缓释片");
        assertThat(result.values(EntityCategory.EXAMINATION)).containsExactly("空腹血糖");
        assertThat(result.entityTags()).containsExactly("二甲双胍缓释片", "空腹血糖");
    }

    @Test
    void canonicalizesDictionaryAliases() {
        MedicalEntityExtractor extractor = new MedicalEntityExtractor();
        extractor.initialize();

        MedicalEntities result = extractor.extract("格华止和气短需要关注吗");

        assertThat(result.values(EntityCategory.DRUG)).containsExactly("二甲双胍");
        assertThat(result.values(EntityCategory.SYMPTOM)).containsExactly("呼吸困难");
    }
}
