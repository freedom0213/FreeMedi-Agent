package com.mediflow.agent.knowledge;

import java.time.Duration;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

/**
 * Calls an OpenAI-compatible embedding endpoint, used when a dedicated key is configured.
 *
 * <p>Two rules this class exists to enforce:
 *
 * <ul>
 *   <li>The conversational model key is not an embedding key. They are separate providers and the
 *       plan says explicitly not to reuse the DeepSeek chat key for embeddings, so this adapter
 *       refuses to start without its own key.
 *   <li>The dimensions are declared, not discovered. A vector store collection is created for one
 *       length, so a mismatch must fail here rather than produce a half-written index.
 * </ul>
 */
public final class ApiTextEmbedder implements TextEmbedder {

    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;

    public ApiTextEmbedder(String modelName, String apiKey, String baseUrl, int dimensions,
                           Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("配置了 API embedding 但没有提供 Embedding Key");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("配置了 API embedding 但没有指定模型名");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding 维度必须是正整数，当前为 " + dimensions);
        }
        this.modelName = modelName;
        this.dimensions = dimensions;
        this.model = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dimensions)
                .timeout(timeout == null ? Duration.ofSeconds(30) : timeout)
                .maxRetries(2)
                .build();
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("要嵌入的文本不能为空");
        }
        Embedding embedding = model.embed(text.trim()).content();
        float[] vector = embedding.vector();
        if (vector.length != dimensions) {
            throw new IllegalStateException("embedding 返回 " + vector.length + " 维，配置声明 "
                    + dimensions + " 维；两者必须一致，否则索引会被写坏");
        }
        return vector;
    }
}
