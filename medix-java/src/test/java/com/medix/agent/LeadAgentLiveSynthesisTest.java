package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LeadAgentLiveSynthesisTest {
    @Test
    void liveSynthesisUsesOriginalQuestionAndWorkerEvidence() {
        RecordingGateway gateway = new RecordingGateway("针对睡眠问题的动态回答");
        LeadAgent lead = new LeadAgent(gateway, new ObjectMapper());

        String answer = lead.synthesize("最近睡不好怎么办？",
                List.of(new AgentResult("consultation_agent", "证据提示应保持规律作息", 1, List.of("search_knowledge"))));

        assertThat(answer).isEqualTo("针对睡眠问题的动态回答");
        assertThat(gateway.prompt).contains("最近睡不好怎么办", "规律作息");
        assertThat(gateway.calls).hasValue(1);
        assertThat(gateway.agent).isEqualTo("lead_synthesizer");
    }

    @Test
    void providerFailureFallsBackWithoutLeakingException() {
        ModelGateway gateway = new ModelGateway() {
            public String complete(String agent, String prompt, Map<String, String> metadata) {
                throw new IllegalStateException("provider diagnostic marker stack");
            }
            public boolean live() { return true; }
        };
        String answer = new LeadAgent(gateway, new ObjectMapper()).synthesize("头痛两天了，需要注意什么？", List.of());
        assertThat(answer).contains("突发最严重头痛", "意识异常")
                .doesNotContain("diagnostic marker", "provider stack");
    }

    private static final class RecordingGateway implements ModelGateway {
        private final String answer;
        private final AtomicInteger calls = new AtomicInteger();
        private String agent;
        private String prompt;
        private RecordingGateway(String answer) { this.answer = answer; }
        public String complete(String agent, String prompt, Map<String, String> metadata) {
            calls.incrementAndGet();
            this.agent = agent;
            this.prompt = prompt;
            return answer;
        }
        public boolean live() { return true; }
    }
}
