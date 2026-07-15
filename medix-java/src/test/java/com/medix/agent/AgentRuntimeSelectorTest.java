package com.medix.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medix.agentscope.AgentScopeRuntimeAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRuntimeSelectorTest {
    @Test
    void agentscopeIsSelectedWithoutTouchingLegacy() {
        AgentLoopEngine legacy = mock(AgentLoopEngine.class);
        AgentScopeRuntimeAdapter agentScope = mock(AgentScopeRuntimeAdapter.class);
        AgentRequest request = new AgentRequest("question", "thread", Map.of());
        AgentResult expected = new AgentResult("consultation_agent", "answer", 1, List.of());
        when(agentScope.run("consultation_agent", request)).thenReturn(expected);

        assertThat(new AgentRuntimeSelector("agentscope", legacy, agentScope).run("consultation_agent", request))
                .isSameAs(expected);
        verify(agentScope).run("consultation_agent", request);
    }

    @Test
    void legacyRemainsConfigurationOnlyRollback() {
        AgentLoopEngine legacy = mock(AgentLoopEngine.class);
        AgentScopeRuntimeAdapter agentScope = mock(AgentScopeRuntimeAdapter.class);
        AgentRequest request = new AgentRequest("question", "thread", Map.of());
        AgentResult expected = new AgentResult("consultation_agent", "legacy", 1, List.of());
        when(legacy.run("consultation_agent", request)).thenReturn(expected);

        assertThat(new AgentRuntimeSelector("legacy", legacy, agentScope).run("consultation_agent", request))
                .isSameAs(expected);
        verify(legacy).run("consultation_agent", request);
    }
}
