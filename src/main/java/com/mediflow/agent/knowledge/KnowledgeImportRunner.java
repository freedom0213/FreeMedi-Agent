package com.mediflow.agent.knowledge;

import java.nio.file.Path;

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

/**
 * The controlled import command for M3 (the management interface arrives in M4).
 *
 * <p>Runs only when {@code mediflow.knowledge.import=true}, then shuts the process down so the
 * command behaves like a batch job rather than a server that never returns. It exits with a non-zero
 * code when any file failed, so a script can tell a clean import from a partial one.
 */
@Component
@Profile("mysql")
@ConditionalOnProperty(name = "mediflow.knowledge.import", havingValue = "true")
public final class KnowledgeImportRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeImportRunner.class);

    private final KnowledgeImporter importer;
    private final ConfigurableApplicationContext context;
    private final Path sourceDirectory;

    public KnowledgeImportRunner(KnowledgeImporter importer, ConfigurableApplicationContext context,
                                 @Value("${mediflow.knowledge.source-dir:knowledge}") String sourceDirectory) {
        this.importer = importer;
        this.context = context;
        this.sourceDirectory = Path.of(sourceDirectory);
    }

    @Override
    public void run(ApplicationArguments args) {
        KnowledgeImporter.ImportSummary summary = importer.importDirectory(sourceDirectory);
        for (KnowledgeImporter.DocumentOutcome outcome : summary.outcomes()) {
            logger.info("  [{}] {}（{} v{}，{} 个片段）：{}", outcome.action(), outcome.docNo(),
                    outcome.title(), outcome.version(), outcome.chunks(), outcome.detail());
        }
        logger.info("资料导入结果：新增 {}，跳过 {}，失败 {}", summary.imported(), summary.skipped(),
                summary.failed());

        int exitCode = SpringApplication.exit(context, () -> summary.clean() ? 0 : 1);
        System.exit(exitCode);
    }
}
