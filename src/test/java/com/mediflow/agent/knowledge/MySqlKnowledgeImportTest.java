package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

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
 * Import against real MySQL. The supplied URL must point to an isolated, initially empty test
 * schema; the fixtures from V1-V7 (department 2001, doctor 3001) are used where an identifier has to
 * exist.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@ActiveProfiles({"test", "mysql"})
@EnabledIfSystemProperty(named = "mediflow.test.db.url", matches = ".+")
class MySqlKnowledgeImportTest {

    @Autowired KnowledgeImporter importer;
    @Autowired KnowledgeRepository repository;
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

    @Test
    void materialIsStoredAsOnePublishedVersionWithOrderedChunks() throws IOException {
        String docNo = unique("IMPORT");
        write("rules.md", material(docNo, "APPOINTMENT_RULE", null, """
                ## 放号时间

                号源按周放出。

                ## 取消

                就诊前可以取消。
                """));

        KnowledgeImporter.ImportSummary summary = importer.importDirectory(materialDir);

        assertThat(summary.imported()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
        KnowledgeDocument document = repository.findLatest(docNo).orElseThrow();
        assertThat(document.version()).isEqualTo(1);
        assertThat(document.status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        assertThat(document.publishedAt()).isNotNull();

        List<KnowledgeChunk> chunks = repository.listChunks(document.id(), 1);
        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(KnowledgeChunk::chunkNo).containsExactly(1, 2);
        assertThat(chunks).extracting(KnowledgeChunk::heading).containsExactly("放号时间", "取消");
        assertThat(chunks.get(0).content()).isEqualTo("号源按周放出。");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.embeddingStatus()).isEqualTo(KnowledgeChunk.EmbeddingStatus.PENDING);
            assertThat(chunk.charCount()).isEqualTo(chunk.content().length());
        });
    }

    @Test
    void importingTheSameMaterialAgainChangesNothing() throws IOException {
        String docNo = unique("REPEAT");
        write("repeat.md", material(docNo, "VISIT_NOTICE", null, "正文一段。"));

        importer.importDirectory(materialDir);
        int documents = repository.countDocuments();
        int chunks = repository.countChunks();

        KnowledgeImporter.ImportSummary second = importer.importDirectory(materialDir);

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.imported()).isZero();
        assertThat(repository.countDocuments()).isEqualTo(documents);
        assertThat(repository.countChunks()).isEqualTo(chunks);
    }

    @Test
    void changedMaterialBecomesANewVersionAndRetiresTheOldOne() throws IOException {
        String docNo = unique("VERSION");
        write("version.md", material(docNo, "PREPARATION", null, "第一版内容。"));
        importer.importDirectory(materialDir);

        write("version.md", material(docNo, "PREPARATION", null, "第二版内容，已经改过。"));
        KnowledgeImporter.ImportSummary second = importer.importDirectory(materialDir);

        assertThat(second.imported()).isEqualTo(1);
        assertThat(repository.listByStatus(KnowledgeStatus.ARCHIVED))
                .anySatisfy(archived -> {
                    assertThat(archived.docNo()).isEqualTo(docNo);
                    assertThat(archived.version()).isEqualTo(1);
                });
        KnowledgeDocument current = repository.findLatest(docNo).orElseThrow();
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.status()).isEqualTo(KnowledgeStatus.PUBLISHED);
        // Only the current version may be retrieved; the superseded text is still stored for audit.
        assertThat(repository.listByStatus(KnowledgeStatus.PUBLISHED))
                .filteredOn(document -> document.docNo().equals(docNo)).hasSize(1);
        assertThat(repository.listChunks(current.id(), 2)).hasSize(1);
    }

    @Test
    void aWithdrawnDocumentIsNotRepublishedByRunningTheImporterAgain() throws IOException {
        String docNo = unique("WITHDRAWN");
        write("withdrawn.md", material(docNo, "APPOINTMENT_RULE", null, "即将下架的内容。"));
        importer.importDirectory(materialDir);
        KnowledgeDocument published = repository.findLatest(docNo).orElseThrow();
        jdbc.update("UPDATE knowledge_document SET status = 'ARCHIVED' WHERE id = ?", published.id());

        KnowledgeImporter.ImportSummary second = importer.importDirectory(materialDir);

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(repository.findLatest(docNo).orElseThrow().status()).isEqualTo(KnowledgeStatus.ARCHIVED);
    }

    @Test
    void anInvalidFileIsReportedAndLeavesNoHalfImportedDocument() throws IOException {
        int documentsBefore = repository.countDocuments();
        write("broken.md", """
                ---
                doc_no: 缺少标题的资料
                type: APPOINTMENT_RULE
                source: 测试用合成资料
                ---

                正文。
                """);

        KnowledgeImporter.ImportSummary summary = importer.importDirectory(materialDir);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.clean()).isFalse();
        assertThat(summary.outcomes().get(0).action())
                .isEqualTo(KnowledgeImporter.DocumentOutcome.Action.FAILED);
        assertThat(repository.countDocuments()).isEqualTo(documentsBefore);
    }

    @Test
    void aDoctorProfilePointingAtAnUnknownDoctorIsRejectedWhole() throws IOException {
        // The foreign key is the guard: material must reference a doctor the hospital actually has,
        // and a rejected document must not leave a document row without its chunks.
        String docNo = unique("GHOST");
        int documentsBefore = repository.countDocuments();
        int chunksBefore = repository.countChunks();
        write("ghost.md", material(docNo, "DOCTOR_PROFILE", 999_999_999L, "不存在的医生。"));

        KnowledgeImporter.ImportSummary summary = importer.importDirectory(materialDir);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(repository.findLatest(docNo)).isEmpty();
        assertThat(repository.countDocuments()).isEqualTo(documentsBefore);
        assertThat(repository.countChunks()).isEqualTo(chunksBefore);
    }

    @Test
    void anExistingDirectoryIsRequired() {
        Path missing = materialDir.resolve("does-not-exist");

        assertThat(catchException(() -> importer.importDirectory(missing)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("资料目录不存在");
    }

    private static Throwable catchException(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException exception) {
            return exception;
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

    private static String material(String docNo, String type, Long doctorId, String body) {
        StringBuilder front = new StringBuilder();
        front.append("---\n")
                .append("doc_no: ").append(docNo).append('\n')
                .append("title: 测试资料 ").append(docNo).append('\n')
                .append("type: ").append(type).append('\n');
        if (doctorId != null) {
            front.append("doctor_id: ").append(doctorId).append('\n');
        }
        front.append("source: 测试用合成资料\n")
                .append("---\n\n")
                .append(body)
                .append('\n');
        return front.toString();
    }
}
