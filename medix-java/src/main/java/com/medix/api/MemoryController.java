package com.medix.api;

import com.medix.memory.EntropyReport;
import com.medix.memory.ShortTermMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {
    private final ShortTermMemory shortTermMemory;

    public MemoryController(ShortTermMemory shortTermMemory) {
        this.shortTermMemory = shortTermMemory;
    }

    @GetMapping("/entropy/{sessionId}")
    public EntropyReport entropy(@PathVariable String sessionId) {
        return shortTermMemory.entropyReport(sessionId);
    }
}
