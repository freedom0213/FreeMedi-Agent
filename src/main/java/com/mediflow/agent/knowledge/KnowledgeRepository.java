package com.mediflow.agent.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Authoritative storage for hospital material.
 *
 * <p>This is the fact source: the vector store holds only vectors and a few filter fields, so a lost
 * index is rebuilt from these rows and a retrieved answer is always read back from here.
 *
 * <p>{@code @Service} rather than {@code @Repository} on purpose: the latter wraps the bean in an
 * exception-translation proxy, which cannot subclass a final class. SQL exceptions are already
 * translated by {@link JdbcTemplate} itself, and this keeps the class final like the other
 * data-access services here.
 */
@Service
@Profile("mysql")
public final class KnowledgeRepository {

    private static final String DOCUMENT_COLUMNS = """
            SELECT id, doc_no, title, doc_type, department_id, doctor_id, source, version, status,
                   content_hash, published_at
            FROM knowledge_document
            """;

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Highest version of a document, whatever its status, or empty when it has never been imported. */
    public Optional<KnowledgeDocument> findLatest(String docNo) {
        return jdbc.query(DOCUMENT_COLUMNS + " WHERE doc_no = ? ORDER BY version DESC LIMIT 1",
                KnowledgeRepository::mapDocument, docNo).stream().findFirst();
    }

    public Optional<KnowledgeDocument> findById(long id) {
        return jdbc.query(DOCUMENT_COLUMNS + " WHERE id = ?",
                KnowledgeRepository::mapDocument, id).stream().findFirst();
    }

    public List<KnowledgeDocument> listAll() {
        return jdbc.query(DOCUMENT_COLUMNS + " ORDER BY doc_no, version", KnowledgeRepository::mapDocument);
    }

    public List<KnowledgeDocument> listByStatus(KnowledgeStatus status) {
        return jdbc.query(DOCUMENT_COLUMNS + " WHERE status = ? ORDER BY doc_no, version",
                KnowledgeRepository::mapDocument, status.name());
    }

    /**
     * Retires every version of a document. Called before a new version is written, so retrieval can
     * never return the superseded answer.
     */
    public int archiveAllVersions(String docNo) {
        return jdbc.update("UPDATE knowledge_document SET status = 'ARCHIVED' WHERE doc_no = ? AND status <> 'ARCHIVED'",
                docNo);
    }

    /** Inserts one version and returns its generated id. */
    public long insertDocument(KnowledgeMarkdownParser.ParsedDocument document, int version,
                               KnowledgeStatus status, Instant publishedAt) {
        jdbc.update("""
                INSERT INTO knowledge_document(doc_no, title, doc_type, department_id, doctor_id,
                                               source, version, status, content_hash, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, document.docNo(), document.title(), document.docType().name(),
                document.departmentId(), document.doctorId(), document.source(), version, status.name(),
                document.contentHash(), publishedAt == null ? null : Timestamp.from(publishedAt));
        return jdbc.queryForObject("SELECT id FROM knowledge_document WHERE doc_no = ? AND version = ?",
                Long.class, document.docNo(), version);
    }

    /**
     * Writes the chunks of one version in order. Positions start at 1 and are part of the unique key,
     * so an interrupted import can be retried without producing duplicate slices.
     */
    public int insertChunks(long documentId, int version, List<KnowledgeChunker.Piece> pieces) {
        int chunkNo = 0;
        for (KnowledgeChunker.Piece piece : pieces) {
            chunkNo++;
            jdbc.update("""
                    INSERT INTO knowledge_chunk(document_id, version, chunk_no, heading, content,
                                                char_count, embedding_status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                    """, documentId, version, chunkNo, piece.heading(), piece.content(),
                    piece.content().length());
        }
        return chunkNo;
    }

    public List<KnowledgeChunk> listChunks(long documentId, int version) {
        return jdbc.query("""
                SELECT id, document_id, version, chunk_no, heading, content, char_count,
                       embedding_status, index_error
                FROM knowledge_chunk
                WHERE document_id = ? AND version = ?
                ORDER BY chunk_no
                """, (result, rowNum) -> new KnowledgeChunk(
                result.getLong("id"), result.getLong("document_id"), result.getInt("version"),
                result.getInt("chunk_no"), result.getString("heading"), result.getString("content"),
                result.getInt("char_count"),
                KnowledgeChunk.EmbeddingStatus.valueOf(result.getString("embedding_status")),
                result.getString("index_error")), documentId, version);
    }

    public int countDocuments() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document", Integer.class);
        return count == null ? 0 : count;
    }

    public int countChunks() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Integer.class);
        return count == null ? 0 : count;
    }

    private static KnowledgeDocument mapDocument(java.sql.ResultSet result, int rowNum)
            throws java.sql.SQLException {
        Timestamp publishedAt = result.getTimestamp("published_at");
        Long departmentId = result.getObject("department_id", Long.class);
        Long doctorId = result.getObject("doctor_id", Long.class);
        return new KnowledgeDocument(
                result.getLong("id"), result.getString("doc_no"), result.getString("title"),
                KnowledgeDocType.from(result.getString("doc_type")), departmentId, doctorId,
                result.getString("source"), result.getInt("version"),
                KnowledgeStatus.valueOf(result.getString("status")), result.getString("content_hash"),
                publishedAt == null ? null : publishedAt.toInstant());
    }
}
