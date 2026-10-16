package com.mediflow.agent.knowledge;

import java.util.List;

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

    /** Drops every point of one document version, used when a version is superseded or withdrawn. */
    void removeVersion(long documentId, int version);

    /** Nearest points first. {@code filter} narrows by metadata; status is always restricted to published. */
    List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK);

    /** Drops everything. The index is rebuildable, so this is a recovery operation, not data loss. */
    void clear();
}
