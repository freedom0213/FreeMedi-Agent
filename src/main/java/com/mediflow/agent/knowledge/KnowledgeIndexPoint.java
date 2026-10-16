package com.mediflow.agent.knowledge;

import java.util.Objects;

/**
 * One point in the index: the chunk's identity, the metadata a search may filter on, and its vector.
 *
 * <p>The text itself is intentionally absent. A hit only tells the caller which chunk to read from
 * MySQL, which keeps the index rebuildable and prevents it from becoming a second, drifting copy of
 * the material.
 */
public record KnowledgeIndexPoint(long chunkId, long documentId, int version, KnowledgeDocType docType,
                                  Long departmentId, Long doctorId, KnowledgeStatus status,
                                  String model, float[] vector) {

    public KnowledgeIndexPoint {
        if (chunkId <= 0 || documentId <= 0 || version <= 0) {
            throw new IllegalArgumentException("index point identity is incomplete");
        }
        Objects.requireNonNull(docType, "docType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(model, "model");
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("index point needs a vector");
        }
    }
}
