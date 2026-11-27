package com.mediflow.agent.controller.admin.knowledge;

import com.mediflow.agent.knowledge.document.KnowledgeChunk;
import com.mediflow.agent.knowledge.document.KnowledgeChunk.EmbeddingStatus;
import com.mediflow.agent.knowledge.index.IndexFailureMessage;
import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.document.KnowledgeDocument;
import com.mediflow.agent.service.knowledge.KnowledgeStorageService;
import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/knowledge/documents")
@Profile("mysql")
public class AdminKnowledgeController {
    private final KnowledgeStorageService repository;

    public AdminKnowledgeController(KnowledgeStorageService repository) {
        this.repository = repository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<DocumentRow> documents() {
        var groups = repository.listAll().stream().collect(
                Collectors.groupingBy(KnowledgeDocument::docNo, LinkedHashMap::new, Collectors.toList()));
        var counts = new LinkedHashMap<Long, Map<EmbeddingStatus, Integer>>();
        for (var row : repository.countChunksByDocument()) {
            counts.computeIfAbsent(row.documentId(), id -> new EnumMap<>(EmbeddingStatus.class))
                    .put(row.status(), row.count());
        }
        return groups.values().stream().map(versions -> {
            KnowledgeDocument displayed = defaultVersion(versions);
            KnowledgeDocument latest = versions.get(versions.size() - 1);
            return new DocumentRow(VersionInfo.from(displayed, counts.getOrDefault(displayed.id(), Map.of())),
                    versions.size(), latest.status() == KnowledgeStatus.DRAFT ? latest.version() : null);
        }).toList();
    }

    @GetMapping("/{docNo}")
    @Transactional(readOnly = true)
    public DocumentDetail detail(@PathVariable String docNo, @RequestParam(required = false) Integer version) {
        var versions = repository.listVersions(docNo);
        if (versions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        KnowledgeDocument selected = version == null ? defaultVersion(versions)
                : versions.stream().filter(item -> item.version() == version).findFirst()
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var chunks = repository.listChunks(selected.id(), selected.version());
        var counts = new EnumMap<EmbeddingStatus, Integer>(EmbeddingStatus.class);
        chunks.forEach(chunk -> counts.merge(chunk.embeddingStatus(), 1, Integer::sum));
        return new DocumentDetail(VersionInfo.from(selected, counts),
                versions.stream().sorted(Comparator.comparingInt(KnowledgeDocument::version).reversed())
                        .map(item -> new VersionOption(
                        item.version(), item.status(), item.publishedAt())).toList(),
                chunks.stream().map(chunk -> ChunkInfo.from(selected.status(), chunk)).toList());
    }

    @PostMapping("/{docNo}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void archive(@PathVariable String docNo, @Valid @RequestBody ArchiveRequest request) {
        List<KnowledgeDocument> versions = repository.lockVersions(docNo);
        KnowledgeDocument selected = versions.stream().filter(item -> item.version() == request.version())
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        long publishedCount = versions.stream().filter(KnowledgeDocument::isPublished).count();
        if (publishedCount == 0 && selected.status() == KnowledgeStatus.ARCHIVED) {
            return; // A retry after a committed withdrawal converges on the same result.
        }
        if (publishedCount != 1 || !selected.isPublished()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发布状态已变化，请重新核对资料。");
        }
        repository.archivePublishedVersion(selected.id());
    }

    private static KnowledgeDocument defaultVersion(List<KnowledgeDocument> versions) {
        return versions.stream().filter(KnowledgeDocument::isPublished).reduce((older, newer) -> newer)
                .orElseGet(() -> versions.get(versions.size() - 1));
    }

    public record DocumentRow(VersionInfo document, int versionCount, Integer draftVersion) {}

    public record ArchiveRequest(@NotNull @Positive Integer version) {}

    public record DocumentDetail(VersionInfo document, List<VersionOption> versions, List<ChunkInfo> chunks) {}

    public record VersionOption(int version, KnowledgeStatus status, Instant publishedAt) {}

    public record VersionInfo(String docNo, String title, KnowledgeDocType docType, String source,
                              int version, KnowledgeStatus status, Instant publishedAt,
                              int indexed, int pending, int failed) {
        private static VersionInfo from(KnowledgeDocument doc, Map<EmbeddingStatus, Integer> counts) {
            return new VersionInfo(doc.docNo(), doc.title(), doc.docType(), doc.source(), doc.version(),
                    doc.status(), doc.publishedAt(), counts.getOrDefault(EmbeddingStatus.INDEXED, 0),
                    counts.getOrDefault(EmbeddingStatus.PENDING, 0), counts.getOrDefault(EmbeddingStatus.FAILED, 0));
        }
    }

    public record ChunkInfo(int chunkNo, String heading, String content, int charCount,
                            EmbeddingStatus embeddingStatus, boolean retrievable, String failureReason) {
        private static ChunkInfo from(KnowledgeStatus status, KnowledgeChunk chunk) {
            var embedding = chunk.embeddingStatus();
            String reason = embedding == EmbeddingStatus.FAILED ? IndexFailureMessage.display(chunk.indexError()) : null;
            return new ChunkInfo(chunk.chunkNo(), chunk.heading(), chunk.content(), chunk.charCount(),
                    embedding, KnowledgeChunk.retrievable(status, embedding), reason);
        }

    }
}
