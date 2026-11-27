package com.mediflow.agent.knowledge.admin;

import com.mediflow.agent.knowledge.document.KnowledgeDocument;
import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import com.mediflow.agent.knowledge.index.KnowledgeIndex;
import com.mediflow.agent.knowledge.index.KnowledgeIndexer;
import com.mediflow.agent.knowledge.ingest.KnowledgeImporter;
import com.mediflow.agent.service.knowledge.KnowledgeIndexMaintenanceLock;
import com.mediflow.agent.service.knowledge.KnowledgeIndexMaintenanceLock.IndexMaintenanceBusy;
import com.mediflow.agent.service.knowledge.KnowledgeStorageService;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("mysql")
public final class KnowledgePublisher {
    private final KnowledgeImporter importer;
    private final KnowledgeStorageService repository;
    private final KnowledgeIndexMaintenanceLock maintenanceLock;
    private final KnowledgeIndexer indexer;
    private final KnowledgeIndex index;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public KnowledgePublisher(KnowledgeImporter importer, KnowledgeStorageService repository, KnowledgeIndexer indexer,
                              KnowledgeIndex index, Clock clock, PlatformTransactionManager transactionManager,
                              KnowledgeIndexMaintenanceLock maintenanceLock) {
        this.importer = importer;
        this.repository = repository;
        this.maintenanceLock = maintenanceLock;
        this.indexer = indexer;
        this.index = index;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record SavedDraft(String docNo, int version) {}

    public record IndexStep(boolean finished, boolean failed) {}

    public enum Problem { MISSING, CONFLICT, INDEX_UNAVAILABLE }

    public static final class PublicationException extends RuntimeException {
        private final Problem problem;

        PublicationException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public Problem problem() { return problem; }
    }

    public SavedDraft saveDraft(String markdown, String expectedHash, int expectedLatest, int expectedPublished) {
        var preview = importer.preview(markdown);
        var parsed = preview.document();
        if (!parsed.contentHash().equals(expectedHash)) {
            throw conflict("文件内容已变化，请重新预览。");
        }
        try {
            return transactions.execute(status -> {
                var versions = repository.lockVersions(parsed.docNo());
                requirePublication(versions, expectedPublished);
                var latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
                if (latest != null && latest.status() == KnowledgeStatus.DRAFT
                        && latest.contentHash().equals(expectedHash)) {
                    return new SavedDraft(latest.docNo(), latest.version());
                }
                int latestVersion = latest == null ? 0 : latest.version();
                if (latestVersion != expectedLatest) throw conflict("资料已更新，请重新预览。");
                if (versions.stream().anyMatch(version -> version.contentHash().equals(expectedHash))) {
                    throw conflict("相同内容已有版本，请重新预览并查看原版本。");
                }
                int version = latestVersion + 1;
                long id = repository.insertDocument(parsed, version, KnowledgeStatus.DRAFT, null);
                repository.insertChunks(id, version, preview.chunks());
                return new SavedDraft(parsed.docNo(), version);
            });
        } catch (ConcurrencyFailureException busy) {
            throw conflict("资料正在被其他操作更新，请重新核对版本。");
        }
    }

    // One chunk per request bounds remote work and makes a cancelled or interrupted batch resumable.
    public IndexStep indexDraftStep(String docNo, int version) {
        var draft = requireLatestDraft(repository.listVersions(docNo), version);
        var next = repository.nextDraftChunk(draft.id(), version);
        if (next.isEmpty()) return new IndexStep(true, false);
        var chunk = next.get();
        try {
            indexer.indexChunk(chunk);
        } catch (RuntimeException failedWrite) {
            repository.markDraftIndexFailed(chunk.chunkId(), failedWrite.getMessage());
            return new IndexStep(false, true);
        }
        return new IndexStep(repository.nextDraftChunk(draft.id(), version).isEmpty(), false);
    }

    public void publish(String docNo, int version, int expectedPublished) {
        try {
            maintenanceLock.withIndexMaintenanceLock(() -> {
                publishReadyDraft(docNo, version, expectedPublished);
                return null;
            });
        } catch (IndexMaintenanceBusy busy) {
            throw conflict(busy.getMessage());
        }
    }

    private void publishReadyDraft(String docNo, int version, int expectedPublished) {
        var initial = repository.listVersions(docNo);
        if (alreadyPublished(initial, version)) return;
        var draft = requireLatestDraft(initial, version);
        requirePublication(initial, expectedPublished);
        var chunkIds = repository.indexedChunkIds(draft.id(), version);
        if (chunkIds.isEmpty() || chunkIds.size() != repository.countVersionChunks(draft.id(), version)) {
            throw conflict("新版索引尚未完成，请先完成索引。");
        }
        Set<Long> ready;
        try {
            ready = index.readyChunkIds(chunkIds);
        } catch (RuntimeException unavailable) {
            throw new PublicationException(Problem.INDEX_UNAVAILABLE, "暂时无法核验向量库，请恢复后重试。");
        }
        var missing = chunkIds.stream().filter(id -> !ready.contains(id)).toList();
        if (!missing.isEmpty()) {
            repository.resetMissingDraftChunks(draft.id(), missing);
            throw conflict("向量库缺少部分索引，请重新建立缺失片段的索引。");
        }
        try {
            transactions.executeWithoutResult(status -> {
                var versions = repository.lockVersions(docNo);
                if (alreadyPublished(versions, version)) return;
                requireLatestDraft(versions, version);
                requirePublication(versions, expectedPublished);
                if (!repository.indexedChunkIds(draft.id(), version).equals(chunkIds)) {
                    throw conflict("草稿索引状态已变化，请重新核对。");
                }
                repository.publishDraft(draft, clock.instant());
            });
        } catch (ConcurrencyFailureException busy) {
            throw conflict("资料正在被其他操作更新，请重新核对版本。");
        }
    }

    private static KnowledgeDocument requireLatestDraft(List<KnowledgeDocument> versions, int version) {
        var selected = versions.stream().filter(item -> item.version() == version).findFirst()
                .orElseThrow(() -> new PublicationException(Problem.MISSING, "找不到这个草稿版本。"));
        if (selected.status() != KnowledgeStatus.DRAFT || versions.get(versions.size() - 1).version() != version) {
            throw conflict("此版本已变化，请查看最新版本。");
        }
        return selected;
    }

    private static boolean alreadyPublished(List<KnowledgeDocument> versions, int version) {
        var published = versions.stream().filter(KnowledgeDocument::isPublished).toList();
        return published.size() == 1 && published.get(0).version() == version;
    }

    private static void requirePublication(List<KnowledgeDocument> versions, int expected) {
        var published = versions.stream().filter(KnowledgeDocument::isPublished).toList();
        if (published.size() > 1 || (published.isEmpty() ? 0 : published.get(0).version()) != expected) {
            throw conflict("当前发布版本已变化，请重新核对后确认。");
        }
    }

    private static PublicationException conflict(String message) {
        return new PublicationException(Problem.CONFLICT, message);
    }
}
