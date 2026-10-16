package com.mediflow.agent.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Imports material files into the authoritative store and slices them into chunks.
 *
 * <p>Indexing is deliberately not part of this step: the pipeline can be exercised, and its
 * idempotency proven, without any embedding model or vector store being reachable.
 *
 * <p>Two rules matter more than the mechanics:
 *
 * <ul>
 *   <li>An unchanged document is skipped, so running the importer repeatedly is a no-op. The check
 *       uses the content hash and ignores status, which is what keeps a withdrawn document
 *       withdrawn: re-importing must not publish material an operator deliberately archived.
 *   <li>A changed document gets a new version and every earlier version is archived first, because
 *       retrieval must never return the superseded answer.
 * </ul>
 */
@Service
@Profile("mysql")
public final class KnowledgeImporter {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeImporter.class);

    /** What happened to one document. */
    public record DocumentOutcome(String docNo, String title, Action action, Integer version,
                                  int chunks, String detail) {
        public enum Action { IMPORTED, SKIPPED, FAILED }
    }

    public record ImportSummary(int imported, int skipped, int failed, List<DocumentOutcome> outcomes) {
        public boolean clean() {
            return failed == 0;
        }
    }

    private final KnowledgeRepository repository;
    private final KnowledgeMarkdownParser parser;
    private final KnowledgeChunker chunker;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public KnowledgeImporter(KnowledgeRepository repository, KnowledgeMarkdownParser parser,
                             KnowledgeChunker chunker, PlatformTransactionManager transactionManager,
                             Clock clock) {
        this.repository = repository;
        this.parser = parser;
        this.chunker = chunker;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ImportSummary importDirectory(Path sourceDirectory) {
        List<Path> files = markdownFiles(sourceDirectory);
        List<DocumentOutcome> outcomes = new ArrayList<>();
        for (Path file : files) {
            outcomes.add(importFile(file));
        }
        ImportSummary summary = new ImportSummary(
                (int) outcomes.stream().filter(o -> o.action() == DocumentOutcome.Action.IMPORTED).count(),
                (int) outcomes.stream().filter(o -> o.action() == DocumentOutcome.Action.SKIPPED).count(),
                (int) outcomes.stream().filter(o -> o.action() == DocumentOutcome.Action.FAILED).count(),
                List.copyOf(outcomes));
        logger.info("Knowledge import finished from {}: files={} imported={} skipped={} failed={}",
                sourceDirectory, files.size(), summary.imported(), summary.skipped(), summary.failed());
        return summary;
    }

    private List<Path> markdownFiles(Path sourceDirectory) {
        if (!Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("资料目录不存在：" + sourceDirectory.toAbsolutePath());
        }
        try (Stream<Path> walk = Files.walk(sourceDirectory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("无法读取资料目录：" + sourceDirectory.toAbsolutePath(),
                    exception);
        }
    }

    private DocumentOutcome importFile(Path file) {
        final KnowledgeMarkdownParser.ParsedDocument parsed;
        try {
            parsed = parser.parse(file);
        } catch (RuntimeException invalid) {
            logger.warn("Knowledge file rejected: file={} reason={}", file, invalid.getMessage());
            return new DocumentOutcome(file.getFileName().toString(), null,
                    DocumentOutcome.Action.FAILED, null, 0, invalid.getMessage());
        }

        Optional<KnowledgeDocument> latest = repository.findLatest(parsed.docNo());
        if (latest.isPresent() && latest.get().contentHash().equals(parsed.contentHash())) {
            KnowledgeDocument current = latest.get();
            return new DocumentOutcome(current.docNo(), current.title(),
                    DocumentOutcome.Action.SKIPPED, current.version(), 0,
                    "内容未变化，当前状态 " + current.status());
        }

        int version = latest.map(document -> document.version() + 1).orElse(1);
        Instant publishedAt = clock.instant();
        try {
            int chunks = transactions.execute(status -> {
                if (latest.isPresent()) {
                    repository.archiveAllVersions(parsed.docNo());
                }
                long documentId = repository.insertDocument(parsed, version,
                        KnowledgeStatus.PUBLISHED, publishedAt);
                return repository.insertChunks(documentId, version, chunker.chunk(parsed.body()));
            });
            return new DocumentOutcome(parsed.docNo(), parsed.title(),
                    DocumentOutcome.Action.IMPORTED, version, chunks, "已发布");
        } catch (RuntimeException failure) {
            logger.warn("Knowledge import failed: docNo={} version={}", parsed.docNo(), version, failure);
            return new DocumentOutcome(parsed.docNo(), parsed.title(),
                    DocumentOutcome.Action.FAILED, version, 0, failure.getMessage());
        }
    }
}
