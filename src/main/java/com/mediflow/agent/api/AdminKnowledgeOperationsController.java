package com.mediflow.agent.api;

import com.mediflow.agent.knowledge.KnowledgeAdminOperations;
import com.mediflow.agent.knowledge.KnowledgeChunk.EmbeddingStatus;
import com.mediflow.agent.knowledge.KnowledgeDocType;
import com.mediflow.agent.knowledge.KnowledgeIndexer;
import com.mediflow.agent.knowledge.KnowledgeRepository;
import com.mediflow.agent.knowledge.KnowledgeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/knowledge")
public class AdminKnowledgeOperationsController {
    private final KnowledgeRepository repository;
    private final KnowledgeAdminOperations operations;
    private final KnowledgeIndexer indexer;

    public AdminKnowledgeOperationsController(KnowledgeRepository repository, KnowledgeAdminOperations operations,
                                              KnowledgeIndexer indexer) {
        this.repository = repository;
        this.operations = operations;
        this.indexer = indexer;
    }

    @GetMapping("/library")
    public List<KnowledgeRepository.LibraryGroup> library() { return repository.libraryGroups(); }

    @GetMapping("/index/summary")
    public KnowledgeAdminOperations.IndexSummary indexSummary() { return operations.indexSummary(); }

    @GetMapping("/chunks")
    public KnowledgeAdminOperations.Page chunks(@RequestParam(required = false) KnowledgeDocType docType,
            @RequestParam(required = false) KnowledgeStatus status, @RequestParam(required = false) EmbeddingStatus embedding,
            @RequestParam(required = false) @Size(max = 200) String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "false") boolean vectors) {
        return operations.chunks(docType, status, embedding, q, page, vectors);
    }

    @PostMapping("/index/probe")
    public KnowledgeAdminOperations.ProbeResult probe(@Valid @RequestBody ProbeInput input) {
        return operations.probe(input.query(), input.docType(), input.topK());
    }

    @PostMapping("/chunks/{id}/retry-index")
    public ResponseEntity<?> retry(@PathVariable long id) {
        return switch (indexer.retryPublishedChunk(id)) {
            case INDEXED -> ResponseEntity.ok(Map.of("status", "INDEXED"));
            case ALREADY_READY -> ResponseEntity.ok(Map.of("status", "ALREADY_READY"));
            case NOT_FOUND -> error(404, "未找到知识片段。");
            case NOT_PUBLISHED -> error(409, "该片段当前不属于已发布版本，请刷新后核对。");
            case UNAVAILABLE -> error(503, "向量库暂不可用，请恢复服务后重试。");
            case FAILED -> error(503, "片段索引写入失败，可查看该行的失败原因。");
            case BUSY -> error(409, "资料索引正在发布或重建，请稍后重试。");
        };
    }

    private static ResponseEntity<Map<String, String>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    public record ProbeInput(@NotBlank @Size(max = 500) String query, KnowledgeDocType docType,
                             @NotNull @Min(1) @Max(10) Integer topK) {}
}
