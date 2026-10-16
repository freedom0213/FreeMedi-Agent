package com.mediflow.agent.knowledge;

/**
 * Narrows a search by metadata. Every field is optional; a null field means "do not restrict".
 *
 * <p>Department and doctor are never relaxed to widen a result set: a question about cardiology must
 * not be answered with material about another department. Publication status is not part of this
 * filter because it is not optional — only published material is ever searchable.
 */
public record KnowledgeIndexFilter(KnowledgeDocType docType, Long departmentId, Long doctorId) {

    public static KnowledgeIndexFilter none() {
        return new KnowledgeIndexFilter(null, null, null);
    }
}
