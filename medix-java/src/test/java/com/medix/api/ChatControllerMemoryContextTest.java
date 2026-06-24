package com.medix.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medix.agent.AgentRequest;
import com.medix.evaluation.EvaluationService;
import com.medix.memory.ConversationSummary;
import com.medix.memory.LongTermMemoryService;
import com.medix.storage.ChatArchiveService;
import com.medix.swarm.RouteDecision;
import com.medix.swarm.RouteMode;
import com.medix.swarm.SwarmCoordinator;
import com.medix.swarm.SwarmResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatControllerMemoryContextTest {
    @Test
    void injectsSimilarCasesIntoAgentRequestContextBeforeProcessing() {
        SwarmCoordinator swarmCoordinator = mock(SwarmCoordinator.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        ChatArchiveService archiveService = mock(ChatArchiveService.class);
        EvaluationService evaluationService = mock(EvaluationService.class);
        ConversationSummary similarCase = new ConversationSummary(
                7,
                "old-session",
                "既往高血压胸痛问题",
                "历史摘要：胸痛需要优先评估高危信号",
                Instant.parse("2026-06-01T00:00:00Z")
        );
        when(longTermMemoryService.similarCases("我胸痛怎么办？", 3)).thenReturn(List.of(similarCase));
        when(swarmCoordinator.processDetailed(any())).thenReturn(new SwarmResponse(
                new RouteDecision(RouteMode.SINGLE_AGENT, "consultation_agent", List.of("consultation_agent"), "test"),
                "answer",
                List.of(),
                Map.of()
        ));
        ChatController controller = new ChatController(
                swarmCoordinator,
                longTermMemoryService,
                archiveService,
                evaluationService
        );

        controller.chat(new ChatRequest("memory-session", "我胸痛怎么办？", Map.of("age", 52)));

        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(swarmCoordinator).processDetailed(captor.capture());
        AgentRequest agentRequest = captor.getValue();
        assertThat(agentRequest.context()).containsEntry("age", 52);
        assertThat(agentRequest.context()).containsKey("similarCases");
        assertThat(agentRequest.context().get("similarCases")).asList().contains(similarCase);
    }
}
