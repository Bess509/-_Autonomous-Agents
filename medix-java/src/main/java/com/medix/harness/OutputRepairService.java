package com.medix.harness;

import com.medix.nlu.NegationAwareSignalMatcher;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class OutputRepairService {
    private static final String DISCLAIMER = "\n\n【免责声明】以上信息仅供学习和参考，不能替代专业医生的诊断和治疗。如有不适或疑问，请及时就医。";
    private static final String EMERGENCY = "【重要提醒】你描述的症状可能提示严重风险，建议立即就医或拨打 120，不要延误急救。\n\n";
    private static final List<String> HIGH_RISK = List.of("胸痛", "呼吸困难", "昏厥", "意识不清", "剧烈头痛", "偏瘫");
    private static final Pattern DISCLAIMER_BLOCK = Pattern.compile("(?s)\\n*【免责声明】[^【]*");
    private static final Pattern EMERGENCY_BLOCK = Pattern.compile("(?s)【重要提醒】[^【]*");

    public String repair(String output) {
        return repair(output, output);
    }

    public String repair(String output, String userInput) {
        String repaired = output == null ? "" : output;
        repaired = DISCLAIMER_BLOCK.matcher(repaired).replaceAll("").trim();
        repaired = EMERGENCY_BLOCK.matcher(repaired).replaceAll("").trim();
        String riskSource = userInput == null ? "" : userInput;
        if (NegationAwareSignalMatcher.containsNonNegated(riskSource, "头痛")) {
            repaired = ensureHeadacheSafety(repaired);
        }
        if (HIGH_RISK.stream().anyMatch(signal -> NegationAwareSignalMatcher.containsNonNegated(riskSource, signal))) {
            repaired = EMERGENCY + repaired;
        }
        repaired = repaired + DISCLAIMER;
        return repaired.replace("确诊为", "可能存在").replace("肯定是", "可能是");
    }

    private String ensureHeadacheSafety(String answer) {
        String repaired = answer;
        if (!(repaired.contains("持续") && (repaired.contains("强度") || repaired.contains("变化"))
                && (repaired.contains("伴随") || repaired.contains("发热") || repaired.contains("呕吐")))) {
            repaired += "\n\n请记录头痛持续时间、强度和变化，并留意发热、呕吐、视力或神经功能等伴随症状。";
        }
        if (!(repaired.contains("休息") && repaired.contains("补充水分"))) {
            repaired += "\n\n若没有红旗症状，可先在安静环境休息、补充水分，并减少强光和屏幕刺激。";
        }
        if (!(repaired.contains("突发最严重") && repaired.contains("神经功能") && repaired.contains("意识"))) {
            repaired += "\n\n如出现突发最严重头痛、肢体无力或言语不清等神经功能异常、意识改变，请立即急诊。";
        }
        return repaired;
    }

    public String formatInstructions() {
        return "Return JSON: {\"answer\":string,\"warnings\":string[],\"urgentCareRequired\":boolean}";
    }

    public record RepairEnvelope(String answer, List<String> warnings, boolean urgentCareRequired) {
    }
}
