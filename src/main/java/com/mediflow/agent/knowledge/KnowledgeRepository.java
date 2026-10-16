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

    /** A chunk together with the document fields needed to decide whether it may be shown. */
    public record ChunkView(long chunkId, long documentId, int version, int chunkNo, String heading,
                            String content, String docNo, String title, KnowledgeDocType docType,
                            String source, Instant publishedAt, KnowledgeStatus status,
                            Long departmentId, Long doctorId,
                            KnowledgeChunk.EmbeddingStatus embeddingStatus) {
        public boolean retrievable() {
            return status == KnowledgeStatus.PUBLISHED;
        }
    }

    /** A chunk waiting for a vector, with the metadata the index has to filter on. */
    public record PendingChunk(long chunkId, long documentId, int version, String heading,
                               String content, KnowledgeDocType docType, Long departmentId,
                               Long doctorId) {
    }

    /**
     * Chunks that still need a vector. Only published documents are indexed, and a chunk already
     * marked INDEXED is never re-embedded: re-running is a no-op, and a retry only picks up what
     * actually failed.
     */
    public List<PendingChunk> findChunksPendingIndex(int limit) {
        return jdbc.query("""
                SELECT c.id, c.document_id, c.version, c.heading, c.content,
                       d.doc_type, d.department_id, d.doctor_id
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id AND d.version = c.version
                WHERE c.embedding_status <> 'INDEXED' AND d.status = 'PUBLISHED'
                ORDER BY c.id
                LIMIT ?
                """, (result, rowNum) -> new PendingChunk(
                result.getLong("id"), result.getLong("document_id"), result.getInt("version"),
                result.getString("heading"), result.getString("content"),
                KnowledgeDocType.from(result.getString("doc_type")),
                result.getObject("department_id", Long.class),
                result.getObject("doctor_id", Long.class)), limit);
    }

    /**
     * Reads chunks back by id and joins the document on the same version, so a chunk left over from
     * a superseded version cannot be returned even if the index still holds its vector.
     */
    public List<ChunkView> findChunkViews(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.query("""
                SELECT c.id AS chunk_id, c.document_id, c.version, c.chunk_no, c.heading, c.content,
                       c.embedding_status, d.doc_no, d.title, d.doc_type, d.source, d.published_at,
                       d.status, d.department_id, d.doctor_id
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id AND d.version = c.version
                WHERE c.id IN (%s)
                """.formatted(placeholders),
                (result, rowNum) -> new ChunkView(
                        result.getLong("chunk_id"), result.getLong("document_id"),
                        result.getInt("version"), result.getInt("chunk_no"),
                        result.getString("heading"), result.getString("content"),
                        result.getString("doc_no"), result.getString("title"),
                        KnowledgeDocType.from(result.getString("doc_type")),
                        result.getString("source"),
                        toInstant(result.getTimestamp("published_at")),
                        KnowledgeStatus.valueOf(result.getString("status")),
                        result.getObject("department_id", Long.class),
                        result.getObject("doctor_id", Long.class),
                        KnowledgeChunk.EmbeddingStatus.valueOf(result.getString("embedding_status"))),
                chunkIds.toArray());
    }

    public void markIndexed(List<Long> chunkIds, Instant indexedAt) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
        Object[] arguments = new Object[chunkIds.size() + 1];
        arguments[0] = Timestamp.from(indexedAt);
        for (int index = 0; index < chunkIds.size(); index++) {
            arguments[index + 1] = chunkIds.get(index);
        }
        jdbc.update("""
                UPDATE knowledge_chunk
                SET embedding_status = 'INDEXED', index_error = NULL, indexed_at = ?
                WHERE id IN (%s)
                """.formatted(placeholders), arguments);
    }

    /** Records why one chunk failed so it can be retried and diagnosed without rebuilding the rest. */
    public void markIndexFailed(long chunkId, String error) {
        String message = error == null ? "unknown" : error;
        jdbc.update("""
                UPDATE knowledge_chunk SET embedding_status = 'FAILED', index_error = ? WHERE id = ?
                """, message.length() <= 500 ? message : message.substring(0, 500), chunkId);
    }

    public int countChunksByEmbeddingStatus(KnowledgeChunk.EmbeddingStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_status = ?", Integer.class,
                status.name());
        return count == null ? 0 : count;
    }

    /** Drops every point of a superseded version from the index. */
    public List<Long> findChunkIdsByStatus(KnowledgeChunk.EmbeddingStatus status) {
        return jdbc.query("SELECT id FROM knowledge_chunk WHERE embedding_status = ? ORDER BY id",
                (result, rowNum) -> result.getLong("id"), status.name());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
