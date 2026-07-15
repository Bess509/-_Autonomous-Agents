package com.medix.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutputRepairServiceTest {
    private final OutputRepairService repairService = new OutputRepairService();

    @Test
    void addsDisclaimerWhenMissing() {
        String repaired = repairService.repair("建议低盐饮食，规律复查。");

        assertThat(repaired).contains("免责声明");
        assertThat(repaired).contains("不能替代专业医生");
    }

    @Test
    void addsEmergencyWarningForChestPain() {
        String repaired = repairService.repair("用户出现胸痛和呼吸困难。");

        assertThat(repaired).contains("立即就医");
        assertThat(repaired).contains("120");
    }

    @Test
    void doesNotTreatRetrievedEvidenceAsAUserEmergency() {
        String repaired = repairService.repair("知识资料提到胸痛属于高危信号。", "高血压有哪些健康知识？");

        assertThat(repaired).doesNotContain("你描述的症状可能提示严重风险");
        assertThat(repaired).contains("免责声明");
    }

    @Test
    void normalizesDuplicateDisclaimerAndEmergencyBlocks() {
        String repaired = repairService.repair("【重要提醒】旧提醒\n\n正文\n\n【免责声明】旧声明\n\n【免责声明】另一份声明", "剧烈头痛");

        assertThat(count(repaired, "【重要提醒】")).isEqualTo(1);
        assertThat(count(repaired, "【免责声明】")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "没有剧烈头痛，只是有点轻微不适",
            "否认胸痛，目前只是想了解日常保健",
            "未出现呼吸困难，有轻微头晕"
    })
    void doesNotAddEmergencyWarningForLocallyNegatedSignals(String userInput) {
        String repaired = repairService.repair("建议继续观察。", userInput);

        assertThat(repaired).doesNotContain("【重要提醒】", "立即就医或拨打 120");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "突发剧烈头痛",
            "没有胸痛，但现在呼吸困难",
            "起初没有剧烈头痛，不过现在出现剧烈头痛"
    })
    void addsEmergencyWarningForAffirmedSignalsEvenAfterEarlierNegation(String userInput) {
        String repaired = repairService.repair("建议继续观察。", userInput);

        assertThat(repaired).contains("【重要提醒】", "立即就医", "120");
    }

    @Test
    void guaranteesHeadacheSelfCareAndRedFlagsExactlyOnceAtFinalBoundary() {
        String repaired = repairService.repair("头痛两天，建议尽快咨询医生。", "头痛两天了，需要注意什么？");

        assertThat(repaired).contains("持续时间", "强度", "伴随症状", "休息", "补充水分",
                "突发最严重头痛", "神经功能异常", "意识改变");
        assertThat(count(repaired, "【免责声明】")).isEqualTo(1);
    }

    private int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
