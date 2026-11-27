package com.mediflow.agent.knowledge.runner;

import com.mediflow.agent.knowledge.index.KnowledgeIndex;
import com.mediflow.agent.knowledge.index.KnowledgeIndexer;
import com.mediflow.agent.knowledge.index.QdrantKnowledgeIndex;
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
 * The controlled indexing command for M3 (the management interface arrives in M4).
 *
 * <p>Importing and indexing are separate commands on purpose. Importing is pure text work and runs
 * with no model and no vector store; indexing needs both. Keeping them apart means a machine without
 * a GPU, a key or a container can still import and verify its material.
 *
 * <p>Runs only when {@code mediflow.knowledge.index-run=true}, then shuts down so the command behaves
 * like a batch job. {@code mediflow.knowledge.index-rebuild=true} additionally throws the index away
 * first, which is what a model change requires. It exits non-zero when any chunk failed, so a script
 * can tell a complete index from a partial one.
 */
@Component
@Profile("mysql")
@ConditionalOnProperty(name = "mediflow.knowledge.index-run", havingValue = "true")
public final class KnowledgeIndexRunner implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIndexRunner.class);

    private final KnowledgeIndexer indexer;
    private final KnowledgeIndex index;
    private final ConfigurableApplicationContext context;
    private final boolean rebuild;

    public KnowledgeIndexRunner(KnowledgeIndexer indexer, KnowledgeIndex index,
                                ConfigurableApplicationContext context,
                                @Value("${mediflow.knowledge.index-rebuild:false}") boolean rebuild) {
        this.indexer = indexer;
        this.index = index;
        this.context = context;
        this.rebuild = rebuild;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (index instanceof QdrantKnowledgeIndex qdrant) {
            logger.info("索引后端：Qdrant 集合 {}（模型 {}，{} 维）", qdrant.collection(),
                    qdrant.model(), qdrant.dimensions());
        }
        logger.info("索引模式：{}", rebuild ? "重建（先清空索引，再由 MySQL 重新生成向量）" : "增量（只处理还没有向量的片段）");

        KnowledgeIndexer.IndexSummary summary = rebuild ? indexer.rebuild() : indexer.indexPending();

        logger.info("索引结果：新写入 {}，失败 {}，仍待处理 {}，索引点数 {}，模型 {}",
                summary.indexed(), summary.failed(), summary.stillPending(), summary.indexSize(),
                summary.model());
        if (rebuild) {
            logger.info("重建完成：索引现在只包含模型 {} 的向量", summary.model());
        }

        int exitCode = SpringApplication.exit(context, () -> summary.clean() ? 0 : 1);
        System.exit(exitCode);
    }
}
