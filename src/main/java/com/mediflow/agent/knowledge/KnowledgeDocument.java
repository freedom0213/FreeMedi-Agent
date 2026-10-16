package com.mediflow.agent.knowledge;

import java.time.Instant;

/**
 * One version of one piece of hospital material.
 *
 * <p>A document is never edited in place. A changed {@code contentHash} produces a new version and
 * the previous version is archived, because retrieval must not return a superseded answer.
 */
public record KnowledgeDocument(long id, String docNo, String title, KnowledgeDocType docType,
                                Long departmentId, Long doctorId, String source, int version,
                                KnowledgeStatus status, String contentHash, Instant publishedAt) {

    public boolean isPublished() {
        return status == KnowledgeStatus.PUBLISHED;
    }
}
