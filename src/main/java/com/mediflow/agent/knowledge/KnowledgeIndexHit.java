package com.mediflow.agent.knowledge;

/**
 * A candidate the index found: which chunk, and how close it was.
 *
 * <p>The score is only comparable within one model and one index. Comparing a score to a fixed
 * threshold borrowed from another model is meaningless, which is why nothing here judges relevance —
 * the retrieval step decides using the model it actually used.
 */
public record KnowledgeIndexHit(long chunkId, double score) {
}
