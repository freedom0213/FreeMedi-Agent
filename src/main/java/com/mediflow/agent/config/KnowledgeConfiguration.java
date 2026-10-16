package com.mediflow.agent.config;

import java.time.Duration;

import com.mediflow.agent.knowledge.ApiTextEmbedder;
import com.mediflow.agent.knowledge.DisabledKnowledgeIndex;
import com.mediflow.agent.knowledge.InMemoryKnowledgeIndex;
import com.mediflow.agent.knowledge.KnowledgeChunker;
import com.mediflow.agent.knowledge.KnowledgeIndex;
import com.mediflow.agent.knowledge.KnowledgeMarkdownParser;
import com.mediflow.agent.knowledge.LocalOnnxTextEmbedder;
import com.mediflow.agent.knowledge.TextEmbedder;

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

    /**
     * Local model by default, so semantic search needs no key and no external service. The API model
     * is opt-in and needs its own key: the conversational key is a different provider's credential
     * and must not be reused here.
     */
    @Bean
    TextEmbedder textEmbedder(
            @Value("${mediflow.embedding.provider:local}") String provider,
            @Value("${mediflow.embedding.api-key:}") String apiKey,
            @Value("${mediflow.embedding.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${mediflow.embedding.model:BAAI/bge-m3}") String model,
            @Value("${mediflow.embedding.dimensions:1024}") int dimensions) {
        if ("api".equalsIgnoreCase(provider)) {
            return new ApiTextEmbedder(model, apiKey, baseUrl, dimensions, Duration.ofSeconds(30));
        }
        if (!"local".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException("未知的 embedding 提供方：" + provider + "，可选 local / api");
        }
        return new LocalOnnxTextEmbedder();
    }

    /**
     * {@code none} is the default on purpose. Until a real vector store is configured, the index must
     * answer "unavailable" rather than "empty", because an empty index would be relayed to a patient
     * as "the hospital has no such rule". {@code memory} is an explicit opt-in for local pipeline
     * checks; it holds nothing across restarts and is never the production setting.
     */
    @Bean
    KnowledgeIndex knowledgeIndex(@Value("${mediflow.knowledge.index:none}") String kind) {
        return switch (kind.trim().toLowerCase()) {
            case "memory" -> new InMemoryKnowledgeIndex();
            case "none" -> new DisabledKnowledgeIndex("mediflow.knowledge.index 未配置向量库");
            default -> throw new IllegalArgumentException(
                    "未知的索引实现：" + kind + "，可选 none / memory");
        };
    }
}
