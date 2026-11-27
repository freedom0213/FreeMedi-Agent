package com.mediflow.agent.knowledge.runner;

import com.mediflow.agent.knowledge.document.KnowledgeChunk;
import com.mediflow.agent.knowledge.document.KnowledgeDocument;
import com.mediflow.agent.service.knowledge.KnowledgeStorageService;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local batch entry point for inspecting or withdrawing hospital material. */
@Component
@Profile("mysql")
@ConditionalOnProperty(name = "mediflow.knowledge.maintenance-run", havingValue = "true")
public final class KnowledgeMaintenanceRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeMaintenanceRunner.class);
    private final KnowledgeStorageService repository;
    private final ConfigurableApplicationContext context;
    private final String action;
    private final String docNo;

    public KnowledgeMaintenanceRunner(KnowledgeStorageService repository, ConfigurableApplicationContext context,
            @Value("${mediflow.knowledge.maintenance}") String action,
            @Value("${mediflow.knowledge.maintenance-doc-no:}") String docNo) {
        this.repository = repository;
        this.context = context;
        this.action = action;
        this.docNo = docNo.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (docNo.length() > 64 || docNo.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("资料编号无效");
        }
        switch (action) {
            case "status" -> reportStatus();
            case "archive" -> archive();
            default -> throw new IllegalArgumentException("资料维护操作支持 status 或 archive");
        }
        System.exit(SpringApplication.exit(context));
    }

    private void reportStatus() {
        List<KnowledgeDocument> documents = repository.listAll().stream()
                .filter(document -> docNo.isEmpty() || document.docNo().equals(docNo))
                .toList();
        for (KnowledgeDocument document : documents) {
            var chunks = repository.listChunks(document.id(), document.version());
            long indexed = chunks.stream().filter(chunk ->
                    chunk.embeddingStatus() == KnowledgeChunk.EmbeddingStatus.INDEXED).count();
            var failed = chunks.stream().filter(chunk ->
                    chunk.embeddingStatus() == KnowledgeChunk.EmbeddingStatus.FAILED).toList();
            logger.info("资料 {} v{} status={} indexed={} pending={} failed={}", document.docNo(), document.version(),
                    document.status(), indexed, chunks.size() - indexed - failed.size(), failed.size());
            failed.forEach(chunk -> logger.info("索引失败 chunkId={} reason={}", chunk.id(), chunk.indexError()));
        }
        logger.info("资料版本数：{}", documents.size());
    }

    private void archive() {
        if (docNo.isEmpty()) throw new IllegalArgumentException("下架时必须提供资料编号");
        KnowledgeDocument document = repository.findLatest(docNo)
                .orElseThrow(() -> new IllegalArgumentException("未找到指定资料"));
        int archived = repository.archiveAllVersions(document.docNo());
        logger.info("资料下架：docNo={} archivedVersions={}", document.docNo(), archived);
    }
}
