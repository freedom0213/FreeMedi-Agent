package com.mediflow.agent.knowledge;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import com.mediflow.agent.knowledge.embedding.LocalOnnxTextEmbedder;
import com.mediflow.agent.knowledge.index.KnowledgeIndexFilter;
import com.mediflow.agent.knowledge.index.KnowledgeIndexHit;
import com.mediflow.agent.knowledge.index.KnowledgeIndexPoint;
import com.mediflow.agent.knowledge.index.QdrantKnowledgeIndex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The Qdrant adapter against a real server.
 *
 * <p>Skipped unless {@code mediflow.qdrant.url} is set, so the default suite stays offline and
 * repeatable with no container. {@code scripts/test-qdrant.ps1} sets it after checking the server is
 * actually reachable, so a missing container fails loudly there instead of looking like a pass.
 *
 * <p>What is asserted here is the adapter's contract, not the embedding model: the vectors are hand
 * made so that a similarity order is known in advance. Whether the model orders meaning correctly is
 * measured separately, against the model.
 */
@EnabledIfSystemProperty(named = "mediflow.qdrant.url", matches = ".+")
class QdrantKnowledgeIndexTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = LocalOnnxTextEmbedder.MODEL_NAME;
    private static final String OTHER_MODEL = "some-other-model";
    /**
     * Deliberately small. These vectors are written by hand, and using 512 of them would only make
     * the source harder to read without testing anything extra.
     */
    private static final int DIMENSIONS = 8;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private String baseName;
    private QdrantKnowledgeIndex index;

    @BeforeEach
    void useAThrowawayCollection() {
        // One collection per run, so this class can never read or damage the index the application
        // uses, and a leftover from a failed run cannot change its results.
        baseName = "mediflow_knowledge_smoke_" + UUID.randomUUID().toString().substring(0, 8);
        index = adapter(DIMENSIONS, baseName, MODEL);
    }

    @AfterEach
    void dropTheCollection() {
        try {
            index.clear();
        } catch (RuntimeException ignored) {
            // A test that already checked "the server is gone" cannot clean up; nothing to clean there.
        }
    }

    @Test
    void theCollectionIsCreatedOnTheFirstWriteAndIsNamedAfterTheModel() {
        // The server answers before any collection exists, because the write path is allowed to
        // create it — otherwise the very first index run could never start.
        assertThat(index.available()).isTrue();
        assertThat(index.size()).isZero();

        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.collection()).isEqualTo(QdrantKnowledgeIndex.collectionName(baseName, MODEL));
        assertThat(index.collection()).endsWith("__bge_small_zh_v1_5_q");
    }

    @Test
    void searchReturnsNearestFirst() {
        index.upsert(List.of(
                point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(2, 16, 1, KnowledgeDocType.PREPARATION, null, null,
                        KnowledgeStatus.PUBLISHED, mostlyFirst()),
                point(3, 17, 1, KnowledgeDocType.VISIT_NOTICE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(1))));

        List<KnowledgeIndexHit> hits = index.search(unit(0), KnowledgeIndexFilter.none(), 3);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(1L, 2L, 3L);
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(hits.get(1).score()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(hits.get(2).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void aPointCarriesNoTextSoTheAnswerCanOnlyComeFromMysql() throws Exception {
        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, 2001L, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        JsonNode payload = readPointPayload(1);

        // The complete set of fields that leave MySQL. Anything resembling the material itself
        // appearing here would mean the index had become a second, drifting copy of the text.
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
                "document_id", "version", "doc_type", "status", "model", "department_id");
        assertThat(payload.path("document_id").asLong()).isEqualTo(10);
        assertThat(payload.path("doc_type").asText()).isEqualTo("APPOINTMENT_RULE");
    }

    @Test
    void aDepartmentFilterKeepsGeneralMaterialAndExcludesOtherDepartments() {
        index.upsert(List.of(
                point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(2, 11, 1, KnowledgeDocType.DEPARTMENT_INTRO, 2001L, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(3, 12, 1, KnowledgeDocType.DEPARTMENT_INTRO, 2002L, null,
                        KnowledgeStatus.PUBLISHED, unit(0))));

        List<KnowledgeIndexHit> hits =
                index.search(unit(0), new KnowledgeIndexFilter(null, 2001L, null), 10);

        // The hospital's rules are not about one department, so they stay answerable; another
        // department's material must not be, or a cardiology question could be answered with
        // gastroenterology text.
        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aDoctorFilterBehavesTheSameWay() {
        index.upsert(List.of(
                point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(2, 11, 1, KnowledgeDocType.DOCTOR_PROFILE, 2001L, 3001L,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(3, 12, 1, KnowledgeDocType.DOCTOR_PROFILE, 2005L, 3006L,
                        KnowledgeStatus.PUBLISHED, unit(0))));

        List<KnowledgeIndexHit> hits =
                index.search(unit(0), new KnowledgeIndexFilter(null, null, 3001L), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void retiredMaterialCannotBeReachedEvenByAskingDifferently() {
        index.upsert(List.of(
                point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(2, 13, 1, KnowledgeDocType.DEPARTMENT_INTRO, 2001L, null,
                        KnowledgeStatus.ARCHIVED, unit(0))));

        // Publication is part of every filter, including a subject-scoped one, so a retired document
        // cannot be recovered by naming its department.
        assertThat(index.search(unit(0), KnowledgeIndexFilter.none(), 10))
                .extracting(KnowledgeIndexHit::chunkId).containsExactly(1L);
        assertThat(index.search(unit(0), new KnowledgeIndexFilter(null, 2001L, null), 10))
                .extracting(KnowledgeIndexHit::chunkId).containsExactly(1L);
    }

    @Test
    void removeVersionDropsOnlyThatVersion() {
        index.upsert(List.of(
                point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(0)),
                point(2, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(1)),
                point(3, 10, 2, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(2)),
                point(4, 11, 1, KnowledgeDocType.VISIT_NOTICE, null, null,
                        KnowledgeStatus.PUBLISHED, unit(3))));

        index.removeVersion(10, 1);

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.search(unit(0), KnowledgeIndexFilter.none(), 10))
                .extracting(KnowledgeIndexHit::chunkId)
                .doesNotContain(1L, 2L);
        assertThat(index.search(unit(2), KnowledgeIndexFilter.none(), 10))
                .extracting(KnowledgeIndexHit::chunkId).contains(3L);
    }

    @Test
    void aSearchAlwaysReturnsCandidatesEvenWhenNoneOfThemIsRelated() {
        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        // A vector store ranks; it does not judge. Asking about something the material never mentions
        // still comes back with the nearest chunk, at a score near zero. So "no material" can never be
        // concluded from a non-empty result — relevance has to be decided against a measured threshold,
        // and until that exists the model decides using the text it is given.
        List<KnowledgeIndexHit> hits = index.search(unit(3), KnowledgeIndexFilter.none(), 10);

        assertThat(hits).extracting(KnowledgeIndexHit::chunkId).containsExactly(1L);
        assertThat(hits.get(0).score()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void searchingAnIndexThatWasNeverBuiltSaysSoInsteadOfFindingNothing() {
        // The distinction the whole design turns on. An empty list here would be relayed to a patient
        // as "the hospital has no such rule", which would be a fabricated answer rather than an error.
        assertThatThrownBy(() -> index.search(unit(0), KnowledgeIndexFilter.none(), 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引尚未建立");
    }

    @Test
    void aVectorFromAnotherModelIsRefused() {
        KnowledgeIndexPoint foreign = new KnowledgeIndexPoint(1, 10, 1,
                KnowledgeDocType.APPOINTMENT_RULE, null, null, KnowledgeStatus.PUBLISHED, OTHER_MODEL,
                unit(0));

        assertThatThrownBy(() -> index.upsert(List.of(foreign)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重建");
    }

    @Test
    void changingTheModelCannotSilentlyReadTheOldIndex() {
        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        QdrantKnowledgeIndex afterModelChange = adapter(DIMENSIONS, baseName, OTHER_MODEL);

        // A different model lands in a different collection, so the old vectors are not comparable by
        // accident and the caller is told the index is not built rather than that nothing matched.
        assertThat(afterModelChange.collection()).isNotEqualTo(index.collection());
        assertThat(afterModelChange.available()).isTrue();
        assertThatThrownBy(() -> afterModelChange.search(unit(0), KnowledgeIndexFilter.none(), 5))
                .as("a new model must not be told 'no material' when the real answer is 'not indexed yet'")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引尚未建立");
    }

    @Test
    void aVectorOfTheWrongLengthIsRefused() {
        KnowledgeIndexPoint shortVector = point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, new float[] {1f, 0f, 0f, 0f});

        assertThatThrownBy(() -> index.upsert(List.of(shortVector)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void anExistingCollectionOfAnotherSizeIsRefusedWithAReachableRemedy() {
        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        // Same collection, different vector length: a real "the model changed" accident, and one the
        // name-based separation cannot catch on its own because the model name is unchanged.
        QdrantKnowledgeIndex differentSize = adapter(4, baseName, MODEL);

        assertThatThrownBy(() -> differentSize.upsert(List.of(point(2, 11, 1,
                KnowledgeDocType.VISIT_NOTICE, null, null, KnowledgeStatus.PUBLISHED,
                new float[] {1f, 0f, 0f, 0f}))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8 维")
                .hasMessageContaining("scripts/index-knowledge.ps1 -Rebuild");
    }

    @Test
    void clearDropsTheCollectionSoARebuildCanChangeTheVectorSize() {
        index.upsert(List.of(point(1, 10, 1, KnowledgeDocType.APPOINTMENT_RULE, null, null,
                KnowledgeStatus.PUBLISHED, unit(0))));

        index.clear();

        assertThat(index.size()).isZero();
        assertThat(index.available()).as("the server is still there").isTrue();
        // Dropping the collection rather than only its points is what lets a rebuild escape a fixed
        // vector size; clearing points would leave a collection that only accepts the old model.
        QdrantKnowledgeIndex afterRebuild = adapter(4, baseName, MODEL);
        afterRebuild.upsert(List.of(point(2, 11, 1, KnowledgeDocType.VISIT_NOTICE, null, null,
                KnowledgeStatus.PUBLISHED, new float[] {1f, 0f, 0f, 0f})));
        assertThat(afterRebuild.size()).isEqualTo(1);
        afterRebuild.clear();
    }

    @Test
    void removingFromACollectionThatDoesNotExistIsANoOp() {
        index.removeVersion(10, 1);
        index.clear();

        assertThat(index.size()).isZero();
    }

    @Test
    void anUnreachableServerReportsItselfUnavailable() {
        QdrantKnowledgeIndex offline = new QdrantKnowledgeIndex("http://127.0.0.1:1", baseName, MODEL,
                DIMENSIONS, "", Duration.ofMillis(800), JSON);

        // "Not reachable" and "nothing matched" are different answers, and only the second may be
        // presented to a patient as the hospital having no such material.
        assertThat(offline.available()).isFalse();
        assertThatThrownBy(() -> offline.search(unit(0), KnowledgeIndexFilter.none(), 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法访问 Qdrant");
    }

    private QdrantKnowledgeIndex adapter(int dimensions, String collection, String model) {
        return new QdrantKnowledgeIndex(System.getProperty("mediflow.qdrant.url"), collection, model,
                dimensions, "", TIMEOUT, JSON);
    }

    private static KnowledgeIndexPoint point(long chunkId, long documentId, int version,
                                             KnowledgeDocType docType, Long departmentId, Long doctorId,
                                             KnowledgeStatus status, float[] vector) {
        return new KnowledgeIndexPoint(chunkId, documentId, version, docType, departmentId, doctorId,
                status, MODEL, vector);
    }

    private static float[] unit(int position) {
        float[] vector = new float[DIMENSIONS];
        vector[position] = 1f;
        return vector;
    }

    /** Cosine 0.8 against {@link #unit(int)} at position 0, and already unit length. */
    private static float[] mostlyFirst() {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 0.8f;
        vector[1] = 0.6f;
        return vector;
    }

    private JsonNode readPointPayload(long pointId) throws Exception {
        String url = System.getProperty("mediflow.qdrant.url") + "/collections/" + index.collection()
                + "/points/" + pointId + "?with_vector=false";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path("result").path("payload");
    }

    private static List<String> fieldNames(JsonNode payload) {
        List<String> names = new java.util.ArrayList<>();
        payload.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
