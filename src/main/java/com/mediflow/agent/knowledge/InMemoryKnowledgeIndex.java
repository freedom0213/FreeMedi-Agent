package com.mediflow.agent.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process index used by tests and by any run without a vector store.
 *
 * <p>It scores by cosine similarity over every point, which is exactly the brute-force approach a
 * vector database exists to avoid. That is acceptable here because it is never the production
 * implementation: the point of this class is that the whole pipeline can be exercised with no
 * container, no network and no model server.
 */
public final class InMemoryKnowledgeIndex implements KnowledgeIndex {

    private final Map<Long, KnowledgeIndexPoint> points = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    /** Lets a test reproduce an outage and check that "index unavailable" is not reported as "no data". */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public int size() {
        return points.size();
    }

    @Override
    public void upsert(List<KnowledgeIndexPoint> incoming) {
        if (incoming == null) {
            return;
        }
        for (KnowledgeIndexPoint point : incoming) {
            points.put(point.chunkId(), point);
        }
    }

    @Override
    public void removeVersion(long documentId, int version) {
        points.values().removeIf(point -> point.documentId() == documentId && point.version() == version);
    }

    @Override
    public List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK) {
        if (!available) {
            throw new IllegalStateException("知识库索引当前不可用");
        }
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        List<KnowledgeIndexHit> hits = new ArrayList<>();
        for (KnowledgeIndexPoint point : points.values()) {
            if (!matches(point, filter) || point.vector().length != queryVector.length) {
                continue;
            }
            hits.add(new KnowledgeIndexHit(point.chunkId(), cosine(queryVector, point.vector())));
        }
        hits.sort(Comparator.comparingDouble(KnowledgeIndexHit::score).reversed()
                .thenComparingLong(KnowledgeIndexHit::chunkId));
        return hits.size() <= topK ? List.copyOf(hits) : List.copyOf(hits.subList(0, topK));
    }

    @Override
    public void clear() {
        points.clear();
    }

    private static boolean matches(KnowledgeIndexPoint point, KnowledgeIndexFilter filter) {
        // Only published material is searchable; this is not something a caller can opt out of.
        if (point.status() != KnowledgeStatus.PUBLISHED) {
            return false;
        }
        if (filter == null) {
            return true;
        }
        if (filter.docType() != null && filter.docType() != point.docType()) {
            return false;
        }
        // A department or doctor restriction excludes points that declare a different one. Material
        // with no department (rules, notices) stays eligible, which is what a general question needs.
        if (filter.departmentId() != null && point.departmentId() != null
                && !filter.departmentId().equals(point.departmentId())) {
            return false;
        }
        if (filter.doctorId() != null && point.doctorId() != null
                && !filter.doctorId().equals(point.doctorId())) {
            return false;
        }
        return true;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
