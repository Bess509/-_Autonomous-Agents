package com.medix.harness;

import com.medix.agent.AgentResult;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class HarnessAspect {
    private final HarnessValidator validator;

    public HarnessAspect(HarnessValidator validator) {
        this.validator = validator;
    }

    @Around("execution(com.medix.agent.AgentResult com.medix.agent.AgentLoopEngine.run(String, com.medix.agent.AgentRequest)) && args(agentId, request)")
    public Object validateAgentResult(ProceedingJoinPoint joinPoint, String agentId, Object request) throws Throwable {
        AgentResult result = (AgentResult) joinPoint.proceed();
        Set<String> violations = validator.violations(agentId, result.skillCalls());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Agent " + agentId + " used disallowed skills: " + violations);
        }
        return result;
    }
}
