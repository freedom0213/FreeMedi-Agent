package com.mediflow.agent.knowledge;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Profile("mysql")
public class KnowledgeAdminOperations {
    private static final int PAGE_SIZE = 20;
    private final KnowledgeRepository repository;
    private final KnowledgeIndex index;
    private final TextEmbedder embedder;
    private final KnowledgeRetriever retriever;

    public KnowledgeAdminOperations(KnowledgeRepository repository, KnowledgeIndex index,
                                    TextEmbedder embedder, KnowledgeRetriever retriever) {
        this.repository = repository;
        this.index = index;
        this.embedder = embedder;
        this.retriever = retriever;
    }

    public IndexSummary indexSummary() {
        String store = index instanceof QdrantKnowledgeIndex ? "QDRANT"
                : index instanceof InMemoryKnowledgeIndex ? "MEMORY" : "DISABLED";
        String collection = index instanceof QdrantKnowledgeIndex qdrant ? qdrant.collection() : null;
        Integer count = null;
        if (!"DISABLED".equals(store)) {
            try {
                count = index.size();
            } catch (RuntimeException unavailable) {
                // Model settings can still be read during an index outage.
            }
        }
        return new IndexSummary(embedder.name(), embedder.dimensions(),
                embedder instanceof ApiTextEmbedder ? "API" : "LOCAL",
                store, collection, count, "DISABLED".equals(store) ? "DISABLED" : count == null ? "UNAVAILABLE" : "REACHABLE");
    }

    @Transactional(readOnly = true)
    public Page chunks(KnowledgeDocType type, KnowledgeStatus status, KnowledgeChunk.EmbeddingStatus embedding,
                       String query, int page, boolean verifyVectors) {
        if (page < 1) throw new IllegalArgumentException("页码须从 1 开始。");
        var fetched = repository.adminChunks(type, status, embedding, query, PAGE_SIZE, ((long) page - 1) * PAGE_SIZE);
        var visible = fetched.items();
        long totalPages = (fetched.total() + PAGE_SIZE - 1) / PAGE_SIZE;
        Set<Long> ready = null;
        if (verifyVectors) {
            var ids = visible.stream().filter(row -> row.embedding() == KnowledgeChunk.EmbeddingStatus.INDEXED)
                    .map(KnowledgeRepository.AdminChunk::id).toList();
            try {
                if (!ids.isEmpty() && !(index instanceof DisabledKnowledgeIndex)) ready = index.readyChunkIds(ids);
            } catch (RuntimeException unavailable) {
                // MySQL records are still useful even when the physical points cannot be checked.
            }
        }
        Set<Long> verified = ready;
        return new Page(visible.stream().map(row -> {
            VectorState state = !verifyVectors ? null
                    : row.embedding() != KnowledgeChunk.EmbeddingStatus.INDEXED ? VectorState.NOT_INDEXED
                    : verified == null ? VectorState.UNVERIFIED
                    : verified.contains(row.id()) ? VectorState.READY : VectorState.MISSING;
            boolean hasVector = state == VectorState.READY;
            return new Fragment(row.id(), row.number(), row.heading(), verifyVectors ? null : row.content(),
                    row.charCount(), row.embedding(),
                    row.embedding() == KnowledgeChunk.EmbeddingStatus.FAILED ? IndexFailureMessage.display(row.error()) : null,
                    row.indexedAt(), row.docNo(), row.title(), row.version(), row.type(), row.source(), row.status(),
                    state, hasVector ? embedder.name() : null, hasVector ? embedder.dimensions() : null);
        }).toList(), page, page < totalPages, totalPages);
    }

    public ProbeResult probe(String query, KnowledgeDocType type, int topK) {
        var result = retriever.search(query, new KnowledgeIndexFilter(type, null, null), topK);
        var chunkNos = repository.findChunkViews(result.chunks().stream().map(KnowledgeRetriever.RetrievedChunk::chunkId).toList())
                .stream().collect(Collectors.toMap(KnowledgeRepository.ChunkView::chunkId, KnowledgeRepository.ChunkView::chunkNo));
        return new ProbeResult(result.indexAvailable(), result.unavailableReason(),
                result.chunks().stream().map(hit -> new Hit(hit.chunkId(), hit.docNo(), hit.title(), hit.version(),
                        hit.heading(), hit.content(), hit.source(), chunkNos.get(hit.chunkId()), hit.score())).toList());
    }

    public enum VectorState { READY, MISSING, UNVERIFIED, NOT_INDEXED }
    public record IndexSummary(String model, int dimensions, String provider, String store, String collection,
                               Integer pointCount, String status) {}
    public record Fragment(long id, int chunkNo, String heading, String content, int charCount,
                           KnowledgeChunk.EmbeddingStatus embeddingStatus, String failureReason, Instant indexedAt,
                           String docNo, String title, int version, KnowledgeDocType docType, String source,
                           KnowledgeStatus documentStatus, VectorState vectorState, String vectorModel,
                           Integer vectorDimensions) {}
    public record Page(List<Fragment> items, int page, boolean hasNext, long totalPages) {}
    public record Hit(long id, String docNo, String title, int version, String heading, String content,
                      String source, Integer chunkNo, double score) {}
    public record ProbeResult(boolean indexAvailable, String unavailableReason, List<Hit> hits) {}
}
