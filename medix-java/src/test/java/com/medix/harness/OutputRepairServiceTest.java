package com.medix.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
}
