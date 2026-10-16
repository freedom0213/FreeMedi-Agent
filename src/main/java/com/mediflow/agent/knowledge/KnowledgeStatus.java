package com.mediflow.agent.knowledge;

/**
 * Publish state of a document.
 *
 * <p>Only {@link #PUBLISHED} documents may be retrieved. {@link #ARCHIVED} is how an update or a
 * withdrawal retires the previous version, and re-running the importer must not resurrect it.
 */
public enum KnowledgeStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
