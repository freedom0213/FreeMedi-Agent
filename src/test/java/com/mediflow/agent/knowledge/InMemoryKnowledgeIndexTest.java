package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mediflow.agent.knowledge.KnowledgeChunk.EmbeddingStatus;

class InMemoryKnowledgeIndexTest {

    private static KnowledgeIndexPoint point(long chunkId, KnowledgeDocType type, Long departmentId,
                                             Long doctorId, KnowledgeStatus status, float[] vector) {
        return new KnowledgeIndexPoint(chunkId, 100 + chunkId, 1, type, departmentId, doctorId, status,
                "test-model", vector);
    }

    /** Two clearly separated directions, so ordering assertions are about the vector, not the data. */
    private static final float[] TOWARD_A = {1f, 0f, 0f};
    private static final float[] TOWARD_B = {0f, 1f, 0f};

    @Test
    void returnsNearestFirst() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.APPOINTMENT_RULE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_B),
                point(2, KnowledgeDocType.APPOINTMENT_RULE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A, KnowledgeIndexFilter.none(), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(2L, 1L);
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    void respectsTopK() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.PREPARATION, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(2, KnowledgeDocType.PREPARATION, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(3, KnowledgeDocType.PREPARATION, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        assertThat(index.search(TOWARD_A, KnowledgeIndexFilter.none(), 2)).hasSize(2);
    }

    @Test
    void onlyPublishedMaterialIsEverReturned() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.VISIT_NOTICE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(2, KnowledgeDocType.VISIT_NOTICE, null, null, KnowledgeStatus.ARCHIVED, TOWARD_A),
                point(3, KnowledgeDocType.VISIT_NOTICE, null, null, KnowledgeStatus.DRAFT, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A, KnowledgeIndexFilter.none(), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(1L);
    }

    @Test
    void aDoctorFilterExcludesOtherDoctorsAndKeepsGeneralMaterial() {
        // The rule for every filter is the same: a point that declares a *different* value is
        // excluded, a point that declares nothing is kept. Without that, a question asked while the
        // conversation is focused on a department could not reach the general rules at all.
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.DOCTOR_PROFILE, null, 3001L, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(2, KnowledgeDocType.DOCTOR_PROFILE, null, 3006L, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(4, KnowledgeDocType.APPOINTMENT_RULE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A,
                new KnowledgeIndexFilter(null, null, 3001L), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactlyInAnyOrder(1L, 4L);
    }

    @Test
    void aDepartmentFilterExcludesOtherDepartmentsAndKeepsGeneralMaterial() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(3, KnowledgeDocType.DEPARTMENT_INTRO, 2001L, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(5, KnowledgeDocType.DEPARTMENT_INTRO, 2002L, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(4, KnowledgeDocType.PREPARATION, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A,
                new KnowledgeIndexFilter(null, 2001L, null), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactlyInAnyOrder(3L, 4L);
    }

    @Test
    void aChunkThatDeclaresADifferentDepartmentIsExcludedWhileGeneralMaterialIsKept() {
        // This is why a doctor profile has to declare its department: leaving it blank would make the
        // profile look like unscoped material, and a question about another department would reach it.
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.DOCTOR_PROFILE, 2001L, 3001L, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(2, KnowledgeDocType.APPOINTMENT_RULE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A,
                new KnowledgeIndexFilter(null, 999L, null), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(2L);
    }

    @Test
    void aDocumentTypeFilterNarrowsToThatType() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                point(1, KnowledgeDocType.PREPARATION, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A),
                point(2, KnowledgeDocType.VISIT_NOTICE, null, null, KnowledgeStatus.PUBLISHED, TOWARD_A)));

        List<KnowledgeIndexHit> hits = index.search(TOWARD_A,
                new KnowledgeIndexFilter(KnowledgeDocType.VISIT_NOTICE, null, null), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(2L);
    }

    @Test
    void reindexingTheSameChunkReplacesItsPoint() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(point(7, KnowledgeDocType.PREPARATION, null, null,
                KnowledgeStatus.PUBLISHED, TOWARD_B)));
        index.upsert(List.of(point(7, KnowledgeDocType.PREPARATION, null, null,
                KnowledgeStatus.PUBLISHED, TOWARD_A)));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(TOWARD_A, KnowledgeIndexFilter.none(), 1))
                .extracting(KnowledgeIndexHit::chunkId).containsExactly(7L);
    }

    @Test
    void removingAVersionDropsOnlyThatVersionsPoints() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(
                new KnowledgeIndexPoint(1, 50, 1, KnowledgeDocType.PREPARATION, null, null,
                        KnowledgeStatus.PUBLISHED, "m", TOWARD_A),
                new KnowledgeIndexPoint(2, 50, 2, KnowledgeDocType.PREPARATION, null, null,
                        KnowledgeStatus.PUBLISHED, "m", TOWARD_A)));

        index.removeVersion(50, 1);

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(TOWARD_A, KnowledgeIndexFilter.none(), 10))
                .extracting(KnowledgeIndexHit::chunkId).containsExactly(2L);
    }

    @Test
    void anUnavailableIndexRefusesToSearchRatherThanLookingEmpty() {
        // The distinction matters: an empty result is relayed as "no such material", an outage must not be.
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(point(1, KnowledgeDocType.PREPARATION, null, null,
                KnowledgeStatus.PUBLISHED, TOWARD_A)));
        index.setAvailable(false);

        assertThat(index.available()).isFalse();
        assertThatThrownBy(() -> index.search(TOWARD_A, KnowledgeIndexFilter.none(), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDisabledIndexReportsItselfUnavailableAndRefusesEveryOperation() {
        DisabledKnowledgeIndex index = new DisabledKnowledgeIndex("未配置向量库");

        assertThat(index.available()).isFalse();
        assertThat(index.size()).isZero();
        assertThatThrownBy(() -> index.search(TOWARD_A, KnowledgeIndexFilter.none(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未配置向量库");
        assertThatThrownBy(index::clear).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEmptyIndexReturnsNoHitsWhileStillBeingAvailable() {
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();

        assertThat(index.available()).isTrue();
        assertThat(index.search(TOWARD_A, KnowledgeIndexFilter.none(), 5)).isEmpty();
    }

    @Test
    void aPointWithAMismatchedVectorLengthIsIgnoredRatherThanScoredWrong() {
        // Mixing models would make scores meaningless, so a length mismatch must not produce a hit.
        InMemoryKnowledgeIndex index = new InMemoryKnowledgeIndex();
        index.upsert(List.of(point(1, KnowledgeDocType.PREPARATION, null, null,
                KnowledgeStatus.PUBLISHED, new float[]{1f, 0f, 0f, 0f})));

        assertThat(index.search(TOWARD_A, KnowledgeIndexFilter.none(), 5)).isEmpty();
    }

    @Test
    void statusIsRequiredAndNeverNull() {
        assertThatThrownBy(() -> point(1, KnowledgeDocType.PREPARATION, null, null, null, TOWARD_A))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new KnowledgeIndexPoint(1, 1, 1, KnowledgeDocType.PREPARATION,
                null, null, KnowledgeStatus.PUBLISHED, "m", new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embeddingStatusIsTrackedSeparatelyFromTheIndex() {
        // Guards the enum used by the repository so a rename cannot silently break the retry path.
        assertThat(EmbeddingStatus.values())
                .containsExactly(EmbeddingStatus.PENDING, EmbeddingStatus.INDEXED, EmbeddingStatus.FAILED);
    }
}
