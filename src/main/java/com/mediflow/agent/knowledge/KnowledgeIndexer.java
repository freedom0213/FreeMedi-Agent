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
                index.upsert(java.util.List.of(new KnowledgeIndexPoint(
                        chunk.chunkId(), chunk.documentId(), chunk.version(), chunk.docType(),
                        chunk.departmentId(), chunk.doctorId(), KnowledgeStatus.PUBLISHED,
                        embedder.name(), embedder.embed(textToEmbed(chunk.heading(), chunk.content())))));
                repository.markIndexed(java.util.List.of(chunk.chunkId()), clock.instant());
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
