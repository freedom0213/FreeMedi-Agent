package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The full pipeline against real MySQL and a real local embedding model: import, index, retrieve.
 *
 * <p>{@code mediflow.knowledge.index=memory} is set explicitly here. It is not the application
 * default, because an index that forgets everything on restart would answer "nothing matched" to
 * every question, and the production default must instead say "cannot look it up".
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "mediflow.knowledge.index=memory"
})
@ActiveProfiles({"test", "mysql"})
@EnabledIfSystemProperty(named = "mediflow.test.db.url", matches = ".+")
class MySqlKnowledgeSearchTest {

    @Autowired KnowledgeImporter importer;
    @Autowired KnowledgeIndexer indexer;
    @Autowired KnowledgeRetriever retriever;
    @Autowired KnowledgeRepository repository;
    @Autowired KnowledgeIndex index;
    @Autowired TextEmbedder embedder;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    @TempDir Path materialDir;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("mediflow.llm.api-key", () -> "");
        registry.add("spring.datasource.url", () -> System.getProperty("mediflow.test.db.url"));
        registry.add("spring.datasource.username", () ->
                System.getProperty("mediflow.test.db.username", "root"));
        registry.add("spring.datasource.password", () ->
                System.getProperty("mediflow.test.db.password", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void startFromAnEmptyKnowledgeStore() {
        // The index is a rebuildable artifact, so emptying it is a legitimate reset. The knowledge
        // tables are emptied too: the indexer deliberately processes every chunk still waiting, so
        // material left behind by another test class would be picked up and change the counts this
        // class asserts. Only the knowledge tables are touched, so the other integration tests keep
        // their own fixtures.
        jdbc.update("DELETE FROM knowledge_chunk");
        jdbc.update("DELETE FROM knowledge_document");
        index.clear();
        ((InMemoryKnowledgeIndex) index).setAvailable(true);
    }

    @Test
    void importedMaterialBecomesSearchableAndTheTextComesFromMysql() throws IOException {
        String docNo = unique("SEARCH");
        write("cancel.md", material(docNo, "APPOINTMENT_RULE", """
                ## 取消与改约

                患者可以在就诊开始前取消预约。取消后该号源回到可约状态，其他患者可以重新预约。
                """));

        KnowledgeImporter.ImportSummary imported = importer.importDirectory(materialDir);
        assertThat(imported.imported()).isEqualTo(1);
        KnowledgeIndexer.IndexSummary indexed = indexer.indexPending();
        assertThat(indexed.indexed()).isEqualTo(1);
        assertThat(indexed.failed()).isZero();
        assertThat(indexed.model()).isEqualTo(embedder.name());

        KnowledgeRetriever.RetrievalResult result = retriever.search("预约之后还能取消吗",
                KnowledgeIndexFilter.none(), 5);

        assertThat(result.indexAvailable()).isTrue();
        assertThat(result.found()).isTrue();
        KnowledgeRetriever.RetrievedChunk top = result.chunks().get(0);
        assertThat(top.docNo()).isEqualTo(docNo);
        assertThat(top.heading()).isEqualTo("取消与改约");
        assertThat(top.score()).isPositive();
        // The excerpt is read back from MySQL, so what a patient would be shown is the stored text.
        assertThat(top.content()).isEqualTo(repository.findChunkViews(List.of(top.chunkId()))
                .get(0).content());
    }

    @Test
    void retrievalMatchesMeaningRatherThanKeywords() throws IOException {
        // The whole point of the index: a question that shares no wording with the material still
        // reaches it. Keyword search would rank the unrelated document first.
        String relevant = unique("MEANING");
        write("cancel.md", material(relevant, "APPOINTMENT_RULE", """
                ## 取消与改约

                患者可以在就诊开始前取消预约，取消后该号源回到可约状态。
                """));
        write("parking.md", material(unique("PARKING"), "VISIT_NOTICE", """
                ## 停车

                医院地下车库入口位于门诊楼东侧，就诊患者可凭证件减免部分停车费用。
                """));
        importer.importDirectory(materialDir);
        indexer.indexPending();

        KnowledgeRetriever.RetrievalResult result = retriever.search("我想把号退掉",
                KnowledgeIndexFilter.none(), 5);

        assertThat(result.found()).isTrue();
        assertThat(result.chunks().get(0).docNo()).isEqualTo(relevant);
    }

    @Test
    void reindexingOnlyPicksUpWhatStillNeedsAVector() throws IOException {
        write("once.md", material(unique("ONCE"), "PREPARATION", "## 通用原则\n\n检查前准备以检查科室说明为准。"));
        importer.importDirectory(materialDir);

        KnowledgeIndexer.IndexSummary first = indexer.indexPending();
        KnowledgeIndexer.IndexSummary second = indexer.indexPending();

        assertThat(first.indexed()).isEqualTo(1);
        assertThat(second.indexed()).isZero();
        assertThat(second.failed()).isZero();
    }

    @Test
    void aWithdrawnDocumentStopsBeingRetrievableEvenThoughItsVectorIsStillIndexed() throws IOException {
        String docNo = unique("WITHDRAW");
        write("withdrawn.md", material(docNo, "VISIT_NOTICE", """
                ## 取号流程

                到院后先到自助机或人工窗口取号，再前往对应科室候诊区等候叫号。
                """));
        importer.importDirectory(materialDir);
        indexer.indexPending();
        int indexedPoints = index.size();
        assertThat(retriever.search("到院之后怎么取号", KnowledgeIndexFilter.none(), 5).found()).isTrue();

        // Withdraw it in MySQL only, leaving the index untouched on purpose.
        long documentId = repository.findLatest(docNo).orElseThrow().id();
        jdbc.update("UPDATE knowledge_document SET status = 'ARCHIVED' WHERE id = ?", documentId);

        KnowledgeRetriever.RetrievalResult afterWithdrawal =
                retriever.search("到院之后怎么取号", KnowledgeIndexFilter.none(), 5);

        // MySQL is the authority: a stale vector must not keep withdrawn material alive.
        assertThat(index.size()).isEqualTo(indexedPoints);
        assertThat(afterWithdrawal.indexAvailable()).isTrue();
        assertThat(afterWithdrawal.chunks()).noneMatch(chunk -> docNo.equals(chunk.docNo()));
    }

    @Test
    void anUnavailableIndexIsReportedAsSuchAndNotAsNoMaterial() {
        ((InMemoryKnowledgeIndex) index).setAvailable(false);

        KnowledgeRetriever.RetrievalResult unavailable = retriever.search("取消预约的规则",
                KnowledgeIndexFilter.none(), 5);

        assertThat(unavailable.indexAvailable()).isFalse();
        assertThat(unavailable.unavailableReason()).isNotBlank();
        assertThat(unavailable.chunks()).isEmpty();

        // The contrast the whole design turns on: an available index that simply has nothing to offer
        // looks identical as a list, but only that case may be relayed as "there is no such rule".
        ((InMemoryKnowledgeIndex) index).setAvailable(true);
        index.clear();
        KnowledgeRetriever.RetrievalResult genuinelyEmpty = retriever.search("完全无关的问题内容",
                KnowledgeIndexFilter.none(), 5);

        assertThat(genuinelyEmpty.indexAvailable()).isTrue();
        assertThat(genuinelyEmpty.count()).isZero();
        assertThat(genuinelyEmpty.unavailableReason()).isNull();
    }

    @Test
    void indexingRefusesToRunWhenTheIndexIsUnreachable() {
        ((InMemoryKnowledgeIndex) index).setAvailable(false);

        assertThatThrownBy(() -> indexer.indexPending())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引当前不可用");
    }

    @Test
    void oneChunkFailingToEmbedIsRecordedForRetryAndDoesNotStopTheBatch() throws IOException {
        String docNo = unique("PARTIAL");
        write("two.md", material(docNo, "PREPARATION", """
                ## 第一节

                抽血类检查可能要求空腹。

                ## 第二节

                影像检查前需要去除金属物品。
                """));
        importer.importDirectory(materialDir);
        long documentId = repository.findLatest(docNo).orElseThrow().id();
        List<KnowledgeChunk> chunks = repository.listChunks(documentId, 1);
        assertThat(chunks).hasSize(2);
        long rejectedChunkId = chunks.get(0).id();

        // A stub index that rejects one specific point, to exercise the partial-failure path that the
        // real in-memory index never produces.
        KnowledgeIndexer fragile = new KnowledgeIndexer(repository, embedder,
                new RejectingIndex(rejectedChunkId), clock);

        KnowledgeIndexer.IndexSummary summary = fragile.indexPending();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.clean()).isFalse();
        assertThat(summary.indexed()).isEqualTo(1);
        assertThat(embeddingStatus(rejectedChunkId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT index_error FROM knowledge_chunk WHERE id = ?",
                String.class, rejectedChunkId)).contains("拒绝写入");
        assertThat(embeddingStatus(chunks.get(1).id())).isEqualTo("INDEXED");
        // The failure is retryable: a later run picks the chunk up again.
        assertThat(repository.findChunksPendingIndex(10))
                .extracting(KnowledgeRepository.PendingChunk::chunkId).contains(rejectedChunkId);
    }

    private String embeddingStatus(long chunkId) {
        return jdbc.queryForObject("SELECT embedding_status FROM knowledge_chunk WHERE id = ?",
                String.class, chunkId);
    }

    private static final class RejectingIndex implements KnowledgeIndex {
        private final long rejectedChunkId;

        RejectingIndex(long rejectedChunkId) {
            this.rejectedChunkId = rejectedChunkId;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void upsert(List<KnowledgeIndexPoint> points) {
            for (KnowledgeIndexPoint point : points) {
                if (point.chunkId() == rejectedChunkId) {
                    throw new IllegalStateException("拒绝写入：" + point.chunkId());
                }
            }
        }

        @Override
        public void removeVersion(long documentId, int version) {
        }

        @Override
        public List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK) {
            return List.of();
        }

        @Override
        public void clear() {
        }
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = materialDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String material(String docNo, String type, String body) {
        return "---\n"
                + "doc_no: " + docNo + "\n"
                + "title: 测试资料 " + docNo + "\n"
                + "type: " + type + "\n"
                + "source: 测试用合成资料\n"
                + "---\n\n"
                + body + "\n";
    }
}
