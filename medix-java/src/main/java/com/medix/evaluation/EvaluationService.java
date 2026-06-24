package com.medix.evaluation;

import com.medix.swarm.RouteMode;
import com.medix.swarm.SwarmResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService {
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong singleAgentRequests = new AtomicLong();
    private final AtomicLong swarmRequests = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    public void record(SwarmResponse response, long latencyMs) {
        totalRequests.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        if (response.decision().mode() == RouteMode.SWARM) {
            swarmRequests.incrementAndGet();
        } else {
            singleAgentRequests.incrementAndGet();
        }
    }

    public Map<String, Object> summary() {
        long total = totalRequests.get();
        return Map.of(
                "live", Map.of(
                        "totalRequests", total,
                        "singleAgentRequests", singleAgentRequests.get(),
                        "swarmRequests", swarmRequests.get(),
                        "averageLatencyMs", total == 0 ? 0 : totalLatencyMs.get() / total
                ),
                "targets", Map.of(
                        "routingAccuracy", "95%",
                        "ragRetrievalAccuracy", "87%",
                        "singleAgentLatency", "5-15s",
                        "swarmLatency", "20-30s",
                        "contextUnderstanding", "92%"
                )
        );
    }
}
