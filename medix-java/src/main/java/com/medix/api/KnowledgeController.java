package com.medix.api;

import com.medix.rag.KnowledgeBaseService;
import com.medix.rag.KnowledgeSnippet;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/search")
    public List<KnowledgeSnippet> search(@RequestParam String q, @RequestParam(defaultValue = "5") int limit) {
        return knowledgeBaseService.retrieve(q, limit);
    }

    @PostMapping("/documents")
    public int addDocument(@RequestBody KnowledgeDocumentRequest request) {
        knowledgeBaseService.addDocument(request.title(), request.content(), request.source());
        return knowledgeBaseService.size();
    }

    public record KnowledgeDocumentRequest(String title, String content, String source) {
    }
}
