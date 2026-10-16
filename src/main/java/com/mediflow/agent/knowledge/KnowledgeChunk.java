package com.mediflow.agent.knowledge;

/**
 * A retrievable slice of a document, with the metadata the index will filter on.
 *
 * <p>The content stays here; only its vector and a few filter fields go to the vector store, so the
 * text a patient finally reads is always the authoritative copy from MySQL.
 */
public record KnowledgeChunk(long id, long documentId, int version, int chunkNo, String heading,
                             String content, int charCount, EmbeddingStatus embeddingStatus,
                             String indexError) {

    /** Indexing state of a chunk. A failed chunk can be retried without rebuilding its neighbours. */
    public enum EmbeddingStatus { PENDING, INDEXED, FAILED }
}
