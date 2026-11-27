package com.mediflow.agent.service.knowledge;

import com.mediflow.agent.knowledge.document.KnowledgeChunk;
import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import com.mediflow.agent.knowledge.document.KnowledgeDocument;
import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import com.mediflow.agent.knowledge.ingest.KnowledgeChunker;
import com.mediflow.agent.knowledge.ingest.KnowledgeMarkdownParser.ParsedDocument;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.AdminChunk;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.ChunkCount;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.ChunkFilters;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.ChunkView;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.LibraryGroup;
import com.mediflow.agent.mapper.knowledge.KnowledgeMapper.PendingChunk;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Coordinates MySQL document versions and chunks, which remain the source for indexed results. */
@Service
@Profile("mysql")
public final class KnowledgeStorageService {
    private final KnowledgeMapper mapper;

    public KnowledgeStorageService(KnowledgeMapper mapper) {
        this.mapper = mapper;
    }

    public void checkDocumentReferences(Long departmentId, Long doctorId) {
        if (departmentId != null && !mapper.departmentExists(departmentId)) {
            throw new IllegalArgumentException("department_id 对应的科室不存在，请核对编号");
        }
        if (doctorId != null && !mapper.doctorExists(doctorId)) {
            throw new IllegalArgumentException("doctor_id 对应的医生不存在，请核对编号");
        }
    }

    public Optional<KnowledgeDocument> findLatest(String docNo) {
        return mapper.findLatest(docNo);
    }

    public Optional<KnowledgeDocument> findById(long id) {
        return mapper.findById(id);
    }

    public Optional<KnowledgeDocument> findByContent(String docNo, String hash) {
        return mapper.findByContent(docNo, hash);
    }

    public List<KnowledgeDocument> listAll() {
        return mapper.listAll();
    }

    public List<KnowledgeDocument> listVersions(String docNo) {
        return mapper.listVersions(docNo);
    }

    public List<KnowledgeDocument> listByStatus(KnowledgeStatus status) {
        return mapper.listByStatus(status);
    }

    public List<KnowledgeDocument> lockVersions(String docNo) {
        return mapper.lockVersions(docNo);
    }

    public List<Long> findPublishedDocumentIds() {
        return mapper.findPublishedDocumentIds();
    }

    public void archivePublishedVersion(long documentId) {
        mapper.archivePublishedVersion(documentId);
    }

    public void publishDraft(KnowledgeDocument draft, Instant publishedAt) {
        mapper.archiveOtherVersions(draft.docNo(), draft.id());
        mapper.publishDraft(draft.id(), publishedAt);
    }

    public List<ChunkCount> countChunksByDocument() {
        return mapper.countChunksByDocument();
    }

    public List<LibraryGroup> libraryGroups() {
        return mapper.libraryGroups();
    }

    public AdminChunkPage adminChunks(KnowledgeDocType type, KnowledgeStatus status,
                                     KnowledgeChunk.EmbeddingStatus embedding, String query, int limit, long offset) {
        String pattern = query == null || query.isBlank() ? null
                : "%" + query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var filters = new ChunkFilters(type, status, embedding, pattern);
        long total = mapper.countAdminChunks(filters);
        return new AdminChunkPage(offset >= total ? List.of() : mapper.adminChunks(filters, limit, offset), total);
    }

    public boolean updatePublishedIndex(long chunkId, Instant indexedAt, String error) {
        return mapper.updatePublishedIndex(chunkId, error == null ? "INDEXED" : "FAILED", indexedAt, error) != 0;
    }

    public int archiveAllVersions(String docNo) {
        return mapper.archiveAllVersions(docNo);
    }

    public long insertDocument(ParsedDocument document, int version, KnowledgeStatus status, Instant publishedAt) {
        mapper.insertDocument(document, version, status, publishedAt);
        return mapper.documentId(document.docNo(), version);
    }

    public int insertChunks(long documentId, int version, List<KnowledgeChunker.Piece> pieces) {
        int chunkNo = 0;
        for (var piece : pieces) {
            mapper.insertChunk(documentId, version, ++chunkNo, piece.heading(), piece.content(), piece.content().length());
        }
        return chunkNo;
    }

    public List<KnowledgeChunk> listChunks(long documentId, int version) {
        return mapper.listChunks(documentId, version);
    }

    public int countDocuments() {
        return mapper.countDocuments();
    }

    public int countChunks() {
        return mapper.countChunks();
    }

    public List<PendingChunk> findChunksPendingIndex(int limit) {
        return mapper.findChunksPendingIndex(limit);
    }

    public Optional<PendingChunk> nextDraftChunk(long documentId, int version) {
        return mapper.nextDraftChunk(documentId, version);
    }

    public List<Long> indexedChunkIds(long documentId, int version) {
        return mapper.indexedChunkIds(documentId, version);
    }

    public int countVersionChunks(long documentId, int version) {
        return mapper.countVersionChunks(documentId, version);
    }

    public void resetMissingDraftChunks(long documentId, List<Long> ids) {
        if (!ids.isEmpty()) mapper.resetMissingDraftChunks(documentId, ids);
    }

    public void markDraftIndexFailed(long chunkId, String error) {
        mapper.markDraftIndexFailed(chunkId, indexError(error));
    }

    public List<ChunkView> findChunkViews(List<Long> chunkIds) {
        return chunkIds == null || chunkIds.isEmpty() ? List.of() : mapper.findChunkViews(chunkIds);
    }

    public void markIndexed(List<Long> chunkIds, Instant indexedAt) {
        if (chunkIds != null && !chunkIds.isEmpty()) mapper.markIndexed(chunkIds, indexedAt);
    }

    public void markIndexFailed(long chunkId, String error) {
        mapper.markIndexFailed(chunkId, indexError(error));
    }

    public int countChunksByEmbeddingStatus(KnowledgeChunk.EmbeddingStatus status) {
        return mapper.countChunksByEmbeddingStatus(status);
    }

    public List<Long> findChunkIdsByStatus(KnowledgeChunk.EmbeddingStatus status) {
        return mapper.findChunkIdsByStatus(status);
    }

    public int resetPublishedChunksToPending() {
        return mapper.resetPublishedChunksToPending();
    }

    private static String indexError(String error) {
        String message = error == null ? "unknown" : error;
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public record AdminChunkPage(List<AdminChunk> items, long total) {}
}
