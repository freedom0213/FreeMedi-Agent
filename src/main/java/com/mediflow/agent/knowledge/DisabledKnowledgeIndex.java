package com.mediflow.agent.knowledge;

import java.util.List;

/**
 * The index used when no vector store is configured.
 *
 * <p>It exists so that "no index" is reported honestly instead of looking like an empty one. An empty
 * index answers "nothing matched", which a model will relay as "the hospital has no such rule"; a
 * missing index must instead produce "cannot look that up right now". Silently returning no results
 * here would be the most damaging failure this feature could have, so writes and searches fail
 * loudly and {@link #available()} says so.
 */
public final class DisabledKnowledgeIndex implements KnowledgeIndex {

    private final String reason;

    public DisabledKnowledgeIndex(String reason) {
        this.reason = reason == null || reason.isBlank() ? "向量库未配置" : reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public void upsert(List<KnowledgeIndexPoint> points) {
        throw new IllegalStateException(reason + "，无法写入向量");
    }

    @Override
    public void removeVersion(long documentId, int version) {
        throw new IllegalStateException(reason + "，无法删除向量");
    }

    @Override
    public List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK) {
        throw new IllegalStateException(reason + "，无法检索");
    }

    @Override
    public void clear() {
        throw new IllegalStateException(reason + "，无法清空");
    }

    public String reason() {
        return reason;
    }
}
