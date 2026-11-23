package com.mediflow.agent.knowledge;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Gives chunks their vectors and writes them to the index.
 *
 * <p>Two decisions live here rather than in the chunker:
 *
 * <ul>
 *   <li><b>What text gets embedded.</b> The heading is combined with the excerpt so a question about
 *       the section topic can still match a chunk whose wording is neutral. The stored excerpt stays
 *       untouched, so a citation still shows the original text.
 *   <li><b>One failure does not stop the batch.</b> A chunk that cannot be embedded is marked FAILED
 *       with its reason and the rest continue, which is what makes a retry meaningful.
 * </ul>
 */
@Service
@Profile("mysql")
public final class KnowledgeIndexer {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIndexer.class);

    /** Cap on how many chunks one run embeds, so a first run cannot turn into a surprise batch job. */
    public static final int DEFAULT_BATCH_LIMIT = 500;

    public record IndexSummary(int indexed, int failed, int stillPending, int indexSize, String model) {
        public boolean clean() {
            return failed == 0;
        }
    }

    private final KnowledgeRepository repository;
    private final TextEmbedder embedder;
    private final KnowledgeIndex index;
    private final Clock clock;

    public KnowledgeIndexer(KnowledgeRepository repository, TextEmbedder embedder, KnowledgeIndex index,
                            Clock clock) {
        this.repository = repository;
        this.embedder = embedder;
        this.index = index;
        this.clock = clock;
    }

    /**
     * Embeds everything still waiting and reports what happened. Throws when the index is
     * unreachable: silently skipping indexing would leave chunks that look importable but can never
     * be retrieved, which is worse than a failed run.
     */
    public IndexSummary indexPending() {
        return indexPending(DEFAULT_BATCH_LIMIT);
    }

    public IndexSummary indexPending(int batchLimit) {
        if (batchLimit <= 0) {
            throw new IllegalArgumentException("批大小必须是正整数");
        }
        if (!index.available()) {
            throw new IllegalStateException(model() + " 的索引当前不可用，无法写入向量；"
                    + "请先确认向量库是否在运行");
        }
        var pending = repository.findChunksPendingIndex(batchLimit);
        int indexed = 0;
        int failed = 0;
        for (KnowledgeRepository.PendingChunk chunk : pending) {
            try {
                indexChunk(chunk);
                indexed++;
            } catch (RuntimeException failure) {
                repository.markIndexFailed(chunk.chunkId(), failure.getMessage());
                failed++;
                logger.warn("Chunk could not be indexed: chunkId={} reason={}", chunk.chunkId(),
                        failure.getMessage());
            }
        }
        int stillPending = repository.countChunksByEmbeddingStatus(KnowledgeChunk.EmbeddingStatus.PENDING)
                + repository.countChunksByEmbeddingStatus(KnowledgeChunk.EmbeddingStatus.FAILED);
        IndexSummary summary = new IndexSummary(indexed, failed, stillPending, index.size(), embedder.name());
        logger.info("Knowledge indexing finished: indexed={} failed={} stillPending={} indexSize={} model={}",
                summary.indexed(), summary.failed(), summary.stillPending(), summary.indexSize(),
                summary.model());
        return summary;
    }

    void indexChunk(KnowledgeRepository.PendingChunk chunk) {
        writePoint(chunk);
        repository.markIndexed(java.util.List.of(chunk.chunkId()), clock.instant());
    }

    private void writePoint(KnowledgeRepository.PendingChunk chunk) {
        // The Qdrant payload marks a ready point. MySQL's version allowlist controls patient visibility.
        index.upsert(java.util.List.of(new KnowledgeIndexPoint(
                chunk.chunkId(), chunk.documentId(), chunk.version(), chunk.docType(),
                chunk.departmentId(), chunk.doctorId(), KnowledgeStatus.PUBLISHED,
                embedder.name(), embedder.embed(textToEmbed(chunk.heading(), chunk.content())))));
    }

    public RetryOutcome retryPublishedChunk(long chunkId) {
        try {
            return repository.withIndexMaintenanceLock(() -> {
                var chunk = repository.findChunkViews(java.util.List.of(chunkId)).stream().findFirst().orElse(null);
                if (chunk == null) return RetryOutcome.NOT_FOUND;
                if (chunk.status() != KnowledgeStatus.PUBLISHED) return RetryOutcome.NOT_PUBLISHED;
                try {
                    if (!index.available()) return RetryOutcome.UNAVAILABLE;
                    if (chunk.embeddingStatus() == KnowledgeChunk.EmbeddingStatus.INDEXED
                            && index.readyChunkIds(java.util.List.of(chunkId)).contains(chunkId)) {
                        return RetryOutcome.ALREADY_READY;
                    }
                } catch (RuntimeException unavailable) {
                    return RetryOutcome.UNAVAILABLE;
                }
                try {
                    writePoint(new KnowledgeRepository.PendingChunk(chunk.chunkId(), chunk.documentId(), chunk.version(),
                            chunk.heading(), chunk.content(), chunk.docType(), chunk.departmentId(), chunk.doctorId()));
                } catch (RuntimeException failed) {
                    logger.warn("Published chunk retry failed: chunkId={} type={}", chunkId, failed.getClass().getSimpleName());
                    return repository.updatePublishedIndex(chunkId, null, IndexFailureMessage.display(failed.getMessage()))
                            ? RetryOutcome.FAILED : RetryOutcome.NOT_PUBLISHED;
                }
                return repository.updatePublishedIndex(chunkId, clock.instant(), null)
                        ? RetryOutcome.INDEXED : RetryOutcome.NOT_PUBLISHED;
            });
        } catch (KnowledgeRepository.IndexMaintenanceBusy busy) {
            return RetryOutcome.BUSY;
        }
    }

    public enum RetryOutcome { INDEXED, ALREADY_READY, NOT_FOUND, NOT_PUBLISHED, UNAVAILABLE, FAILED, BUSY }

    /**
     * Throws the index away and builds it again from MySQL.
     *
     * <p>This is the remedy the index's own guards point at. A collection is created for one vector
     * size and one model, so changing either makes every existing vector unusable — and "unusable"
     * must not be discovered as a search returning nothing, because an empty result reads as "the
     * hospital has no such rule". It is also the recovery path for a lost index: MySQL is the fact
     * source, so the vectors are reproducible.
     *
     * <p>Order matters: the index is dropped first, and only then are the chunks marked as needing a
     * vector. Doing it the other way round would leave chunks marked INDEXED while their points are
     * gone, and nothing would ever bring them back.
     */
    public IndexSummary rebuild() {
        return rebuild(DEFAULT_BATCH_LIMIT);
    }

    public IndexSummary rebuild(int batchLimit) {
        return repository.withIndexMaintenanceLock(() -> {
            if (!index.available()) {
                throw new IllegalStateException("索引当前不可用，无法重建；请先确认向量库是否在运行");
            }
            index.clear();
            int reset = repository.resetPublishedChunksToPending();
            logger.info("Knowledge index cleared for rebuild: chunksResetToPending={} model={}", reset,
                    embedder.name());
            return indexPending(batchLimit);
        });
    }

    /**
     * The string handed to the model. Kept as a method so the choice is visible and testable rather
     * than buried in a call.
     */
    static String textToEmbed(String heading, String content) {
        if (heading == null || heading.isBlank()) {
            return content;
        }
        return heading + "\n" + content;
    }

    public String model() {
        return embedder.name();
    }

    public int dimensions() {
        return embedder.dimensions();
    }
}
