package com.mediflow.agent.knowledge.index;

import java.util.List;
import java.util.Set;

/**
 * The searchable index of knowledge chunks.
 *
 * <p>This is deliberately <b>not</b> a source of truth. It holds a vector per chunk plus the few
 * fields needed to narrow a search, and it can be deleted and rebuilt from MySQL at any time. Two
 * consequences the code must respect:
 *
 * <ul>
 *   <li>A hit is a candidate, not an answer. The caller reads the chunk back from MySQL before
 *       showing anything, which is what makes a withdrawn document stop being retrievable even if
 *       the index has not been cleaned up yet.
 *   <li>Nothing that only exists in MySQL as live state — slot inventory above all — may be stored
 *       here, because a rebuild would silently lose it.
 * </ul>
 */
public interface KnowledgeIndex {

    /** Whether the index can currently be reached. A search must fail loudly rather than look empty. */
    boolean available();

    /** Number of indexed points, for verification and rebuild reports. */
    int size();

    /**
     * Writes or replaces points. Identified by chunk id, so re-indexing the same chunk overwrites
     * its point instead of adding a duplicate.
     */
    void upsert(List<KnowledgeIndexPoint> points);

    /** IDs whose points are present and ready in this index, before MySQL publication is applied. */
    Set<Long> readyChunkIds(List<Long> ids);

    /** Drops every point of one document version, used when a version is superseded or withdrawn. */
    void removeVersion(long documentId, int version);

    /**
     * Nearest ready points first. Patient callers supply the MySQL publication allowlist in the filter.
     *
     * <p>Up to {@code topK} candidates come back even when none of them is related to the query, at a
     * score near zero: a vector store ranks, it does not judge. A non-empty result therefore says
     * nothing about whether the material answers the question, and "no material" must never be
     * concluded from it.
     */
    List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK);

    /** Drops everything. The index is rebuildable, so this is a recovery operation, not data loss. */
    void clear();
}
