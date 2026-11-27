package com.mediflow.agent.knowledge.index;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import java.util.List;

/**
 * Narrows a search by metadata. Every field is optional; a null field means "do not restrict".
 *
 * <p>Department and doctor are never relaxed to widen a result set: a question about cardiology must
 * not be answered with material about another department. Patient retrieval also supplies the
 * exact document version IDs allowed by MySQL before vector ranking.
 */
public record KnowledgeIndexFilter(KnowledgeDocType docType, Long departmentId, Long doctorId,
                                   List<Long> publishedDocumentIds) {

    public KnowledgeIndexFilter(KnowledgeDocType docType, Long departmentId, Long doctorId) {
        this(docType, departmentId, doctorId, null);
    }

    public KnowledgeIndexFilter restrictToPublished(List<Long> ids) {
        return new KnowledgeIndexFilter(docType, departmentId, doctorId, List.copyOf(ids));
    }

    public static KnowledgeIndexFilter none() {
        return new KnowledgeIndexFilter(null, null, null);
    }
}
