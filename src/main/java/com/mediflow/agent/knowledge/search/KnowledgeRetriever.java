package com.mediflow.agent.knowledge.search;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.embedding.TextEmbedder;
import com.mediflow.agent.knowledge.index.KnowledgeIndex;
import com.mediflow.agent.knowledge.index.KnowledgeIndexFilter;
import com.mediflow.agent.knowledge.index.KnowledgeIndexHit;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.ChunkView;
import com.mediflow.agent.service.knowledge.KnowledgeStorageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Retrieves material for a question: the index proposes, MySQL decides.
 *
 * <p>The index only supplies candidate chunk ids and a score. Every candidate is then read back from
 * MySQL joined on the same version and dropped unless its document is published. That is what makes
 * a withdrawal effective immediately, even if the index still holds vectors for the retired version,
 * and it keeps the material the patient reads identical to the authoritative copy.
 *
 * <p>The most important distinction this class maintains is between <b>no material</b> and
 * <b>no index</b>. Both look like an empty result list, but reporting an outage as "the hospital has
 * no such rule" would be the most damaging kind of lie this feature could tell, so they are separate
 * outcomes rather than one.
 */
@Service
@Profile("mysql")
public final class KnowledgeRetriever {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeRetriever.class);

    /** Fetched from the index before filtering, so dropouts in MySQL do not shrink the result below topK. */
    private static final int OVER_FETCH_FACTOR = 3;

    /**
     * One retrieved passage, with everything the caller needs to verify and cite it.
     *
     * <p>{@code departmentId} and {@code doctorId} are carried because a passage may name a clinician
     * and that claim has to be checked against the live catalog before the passage is used: material
     * can outlive a doctor's registration, and quoting it would then present a retired name as
     * current.
     */
    public record RetrievedChunk(long chunkId, long documentId, int version, String docNo, String title,
                                 KnowledgeDocType docType, String source, Instant publishedAt,
                                 String heading, String content, Long departmentId, Long doctorId,
                                 double score) {
    }

    /**
     * @param indexAvailable false means the answer is "cannot look it up right now", which must not be
     *                       presented to a patient as "no such material exists"
     */
    public record RetrievalResult(boolean indexAvailable, int count, String model,
                                  String unavailableReason, List<RetrievedChunk> chunks) {
        public boolean found() {
            return count > 0;
        }
    }

    private final KnowledgeStorageService repository;
    private final TextEmbedder embedder;
    private final KnowledgeIndex index;

    public KnowledgeRetriever(KnowledgeStorageService repository, TextEmbedder embedder, KnowledgeIndex index) {
        this.repository = repository;
        this.embedder = embedder;
        this.index = index;
    }

    public RetrievalResult search(String query, KnowledgeIndexFilter filter, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return new RetrievalResult(true, 0, embedder.name(), null, List.of());
        }
        if (!index.available()) {
            return unavailable("向量索引当前不可用");
        }
        final List<KnowledgeIndexHit> hits;
        var publishedIds = repository.findPublishedDocumentIds();
        if (publishedIds.isEmpty()) {
            return new RetrievalResult(true, 0, embedder.name(), null, List.of());
        }
        try {
            var criteria = filter == null ? KnowledgeIndexFilter.none() : filter;
            hits = index.search(embedder.embed(query.trim()), criteria.restrictToPublished(publishedIds),
                    topK * OVER_FETCH_FACTOR);
        } catch (RuntimeException failure) {
            logger.warn("Knowledge retrieval could not query the index: type={}", failure.getClass().getSimpleName());
            return unavailable("向量索引查询失败");
        }
        if (hits.isEmpty()) {
            return new RetrievalResult(true, 0, embedder.name(), null, List.of());
        }

        List<Long> ordered = hits.stream().map(KnowledgeIndexHit::chunkId).toList();
        Map<Long, Double> scores = new LinkedHashMap<>();
        hits.forEach(hit -> scores.put(hit.chunkId(), hit.score()));
        Map<Long, ChunkView> readable = new LinkedHashMap<>();
        for (ChunkView view : repository.findChunkViews(ordered)) {
            if (view.retrievable() && matches(view, filter)) {
                readable.put(view.chunkId(), view);
            }
        }

        List<RetrievedChunk> chunks = new ArrayList<>();
        for (Long chunkId : ordered) {
            ChunkView view = readable.get(chunkId);
            if (view == null) {
                continue;
            }
            chunks.add(new RetrievedChunk(view.chunkId(), view.documentId(), view.version(), view.docNo(),
                    view.title(), view.docType(), view.source(), view.publishedAt(), view.heading(),
                    view.content(), view.departmentId(), view.doctorId(),
                    scores.getOrDefault(chunkId, 0d)));
            if (chunks.size() == topK) {
                break;
            }
        }
        return new RetrievalResult(true, chunks.size(), embedder.name(), null, List.copyOf(chunks));
    }

    private static boolean matches(ChunkView view, KnowledgeIndexFilter filter) {
        return filter == null || (filter.docType() == null || filter.docType() == view.docType())
                && sameSubjectOrGeneral(view.departmentId(), filter.departmentId())
                && sameSubjectOrGeneral(view.doctorId(), filter.doctorId());
    }

    private static boolean sameSubjectOrGeneral(Long declared, Long wanted) {
        return wanted == null || declared == null || wanted.equals(declared);
    }

    private static RetrievalResult unavailable(String reason) {
        return new RetrievalResult(false, 0, null, reason, List.of());
    }

    public String model() {
        return embedder.name();
    }
}
