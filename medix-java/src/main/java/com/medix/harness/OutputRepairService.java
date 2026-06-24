package com.medix.harness;

import java.util.List;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

@Service
public class OutputRepairService {
    private final BeanOutputConverter<RepairEnvelope> outputConverter = new BeanOutputConverter<>(RepairEnvelope.class);

    private static final String DISCLAIMER = "\n\n【免责声明】以上信息仅供学习和参考，不能替代专业医生的诊断和治疗。如有不适或疑问，请及时就医。";
    private static final String EMERGENCY = "【重要提醒】你描述的症状可能提示严重风险，建议立即就医或拨打 120，不要延误急救。\n\n";
    private static final List<String> HIGH_RISK = List.of("胸痛", "呼吸困难", "昏厥", "意识不清", "剧烈头痛", "偏瘫");

    public String repair(String output) {
        String repaired = output == null ? "" : output;
        if (HIGH_RISK.stream().anyMatch(repaired::contains) && !containsAny(repaired, "立即就医", "急诊", "120")) {
            repaired = EMERGENCY + repaired;
        }
        if (!containsAny(repaired, "免责声明", "仅供参考", "不能替代专业医生")) {
            repaired = repaired + DISCLAIMER;
        }
        return repaired.replace("确诊为", "可能存在").replace("肯定是", "可能是");
    }

    public String formatInstructions() {
        return outputConverter.getFormat();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public record RepairEnvelope(String answer, List<String> warnings, boolean urgentCareRequired) {
    }
}
