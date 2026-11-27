package com.mediflow.agent.mapper.knowledge;

import com.mediflow.agent.knowledge.document.KnowledgeChunk;
import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.document.KnowledgeDocument;
import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import com.mediflow.agent.knowledge.ingest.KnowledgeMarkdownParser.ParsedDocument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeMapper {
    String DOCUMENT_COLUMNS = """
            SELECT id, doc_no, title, doc_type, department_id, doctor_id, source, version, status,
                   content_hash, published_at
            FROM knowledge_document
            """;

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE doc_no = #{docNo} ORDER BY version DESC LIMIT 1")
    Optional<KnowledgeDocument> findLatest(@Param("docNo") String docNo);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE id = #{id}")
    Optional<KnowledgeDocument> findById(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE doc_no = #{docNo} AND content_hash = #{hash} ORDER BY version DESC LIMIT 1")
    Optional<KnowledgeDocument> findByContent(@Param("docNo") String docNo, @Param("hash") String hash);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " ORDER BY doc_no, version")
    List<KnowledgeDocument> listAll();

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE doc_no = #{docNo} ORDER BY version")
    List<KnowledgeDocument> listVersions(@Param("docNo") String docNo);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE status = #{status} ORDER BY doc_no, version")
    List<KnowledgeDocument> listByStatus(@Param("status") KnowledgeStatus status);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select(DOCUMENT_COLUMNS + " WHERE doc_no = #{docNo} ORDER BY version FOR UPDATE")
    List<KnowledgeDocument> lockVersions(@Param("docNo") String docNo);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM knowledge_document WHERE status = 'PUBLISHED'")
    List<Long> findPublishedDocumentIds();

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT EXISTS (SELECT 1 FROM department WHERE id = #{id})")
    boolean departmentExists(@Param("id") long id);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT EXISTS (SELECT 1 FROM doctor WHERE id = #{id})")
    boolean doctorExists(@Param("id") long id);

    @Update("UPDATE knowledge_document SET status = 'ARCHIVED' WHERE id = #{documentId} AND status = 'PUBLISHED'")
    void archivePublishedVersion(@Param("documentId") long documentId);

    @Update("""
            UPDATE knowledge_document SET status = 'ARCHIVED'
            WHERE doc_no = #{docNo} AND id <> #{id} AND status <> 'ARCHIVED'
            """)
    void archiveOtherVersions(@Param("docNo") String docNo, @Param("id") long id);

    @Update("""
            UPDATE knowledge_document SET status = 'PUBLISHED', published_at = #{publishedAt}
            WHERE id = #{id} AND status = 'DRAFT'
            """)
    void publishDraft(@Param("id") long id, @Param("publishedAt") Instant publishedAt);

    List<ChunkCount> countChunksByDocument();

    List<LibraryGroup> libraryGroups();

    long countAdminChunks(@Param("filters") ChunkFilters filters);

    List<AdminChunk> adminChunks(@Param("filters") ChunkFilters filters, @Param("limit") int limit, @Param("offset") long offset);

    int updatePublishedIndex(@Param("chunkId") long chunkId, @Param("status") String status, @Param("indexedAt") Instant indexedAt, @Param("error") String error);

    @Update("UPDATE knowledge_document SET status = 'ARCHIVED' WHERE doc_no = #{docNo} AND status <> 'ARCHIVED'")
    int archiveAllVersions(@Param("docNo") String docNo);

    @Insert("""
            INSERT INTO knowledge_document(doc_no, title, doc_type, department_id, doctor_id,
                                           source, version, status, content_hash, published_at)
            VALUES (#{document.docNo}, #{document.title}, #{document.docType}, #{document.departmentId,jdbcType=BIGINT}, #{document.doctorId,jdbcType=BIGINT}, #{document.source}, #{version}, #{status}, #{document.contentHash}, #{publishedAt,jdbcType=TIMESTAMP})
            """)
    void insertDocument(@Param("document") ParsedDocument document, @Param("version") int version, @Param("status") KnowledgeStatus status, @Param("publishedAt") Instant publishedAt);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM knowledge_document WHERE doc_no = #{docNo} AND version = #{version}")
    long documentId(@Param("docNo") String docNo, @Param("version") int version);

    @Insert("""
            INSERT INTO knowledge_chunk(document_id, version, chunk_no, heading, content,
                                        char_count, embedding_status)
            VALUES (#{documentId}, #{version}, #{chunkNo}, #{heading}, #{content}, #{charCount}, 'PENDING')
            """)
    void insertChunk(@Param("documentId") long documentId, @Param("version") int version, @Param("chunkNo") int chunkNo, @Param("heading") String heading, @Param("content") String content, @Param("charCount") int charCount);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT id, document_id, version, chunk_no, heading, content, char_count,
                   embedding_status, index_error
            FROM knowledge_chunk
            WHERE document_id = #{documentId} AND version = #{version}
            ORDER BY chunk_no
            """)
    List<KnowledgeChunk> listChunks(@Param("documentId") long documentId, @Param("version") int version);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT COUNT(*) FROM knowledge_document")
    int countDocuments();

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT COUNT(*) FROM knowledge_chunk")
    int countChunks();

    List<PendingChunk> findChunksPendingIndex(@Param("limit") int limit);

    Optional<PendingChunk> nextDraftChunk(@Param("documentId") long documentId, @Param("version") int version);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            SELECT id FROM knowledge_chunk
            WHERE document_id = #{documentId} AND version = #{version} AND embedding_status = 'INDEXED'
            ORDER BY chunk_no
            """)
    List<Long> indexedChunkIds(@Param("documentId") long documentId, @Param("version") int version);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = #{documentId} AND version = #{version}")
    int countVersionChunks(@Param("documentId") long documentId, @Param("version") int version);

    void resetMissingDraftChunks(@Param("documentId") long documentId, @Param("ids") List<Long> ids);

    void markDraftIndexFailed(@Param("chunkId") long chunkId, @Param("error") String error);

    List<ChunkView> findChunkViews(@Param("chunkIds") List<Long> chunkIds);

    void markIndexed(@Param("chunkIds") List<Long> chunkIds, @Param("indexedAt") Instant indexedAt);

    @Update("UPDATE knowledge_chunk SET embedding_status = 'FAILED', index_error = #{error} WHERE id = #{chunkId}")
    void markIndexFailed(@Param("chunkId") long chunkId, @Param("error") String error);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_status = #{status}")
    int countChunksByEmbeddingStatus(@Param("status") KnowledgeChunk.EmbeddingStatus status);

    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT id FROM knowledge_chunk WHERE embedding_status = #{status} ORDER BY id")
    List<Long> findChunkIdsByStatus(@Param("status") KnowledgeChunk.EmbeddingStatus status);

    int resetPublishedChunksToPending();

    record ChunkFilters(KnowledgeDocType type, KnowledgeStatus status, KnowledgeChunk.EmbeddingStatus embedding,
                        String pattern) {}

    record ChunkCount(long documentId, KnowledgeChunk.EmbeddingStatus status, int count) {}

    record LibraryGroup(KnowledgeDocType docType, int documents, int versions, int published,
                               int drafts, int archived, int indexed, int pending, int failed) {}

    record AdminChunk(long id, int number, String heading, String content, int charCount,
                      KnowledgeChunk.EmbeddingStatus embedding, String error, Instant indexedAt, String docNo,
                      String title, int version, KnowledgeDocType type, String source, KnowledgeStatus status) {}

    record ChunkView(long chunkId, long documentId, int version, int chunkNo, String heading,
                            String content, String docNo, String title, KnowledgeDocType docType,
                            String source, Instant publishedAt, KnowledgeStatus status,
                            Long departmentId, Long doctorId,
                            KnowledgeChunk.EmbeddingStatus embeddingStatus) {
        public boolean retrievable() {
            return KnowledgeChunk.retrievable(status, embeddingStatus);
        }
    }

    record PendingChunk(long chunkId, long documentId, int version, String heading,
                               String content, KnowledgeDocType docType, Long departmentId,
                               Long doctorId) {
    }
}
