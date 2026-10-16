package com.mediflow.agent.config;

import com.mediflow.agent.knowledge.KnowledgeChunker;
import com.mediflow.agent.knowledge.KnowledgeMarkdownParser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Wires the knowledge pipeline. The chunking sizes are configuration, not constants, on purpose. */
@Configuration
@Profile("mysql")
public class KnowledgeConfiguration {

    @Bean
    KnowledgeMarkdownParser knowledgeMarkdownParser() {
        return new KnowledgeMarkdownParser();
    }

    /**
     * Starting sizes only: the plan requires them to be settled by a small comparison, and a
     * threshold that suits one embedding model does not transfer to another.
     */
    @Bean
    KnowledgeChunker knowledgeChunker(
            @Value("${mediflow.knowledge.chunk.target:400}") int target,
            @Value("${mediflow.knowledge.chunk.overlap:80}") int overlap,
            @Value("${mediflow.knowledge.chunk.max:800}") int max) {
        return new KnowledgeChunker(target, overlap, max);
    }
}
