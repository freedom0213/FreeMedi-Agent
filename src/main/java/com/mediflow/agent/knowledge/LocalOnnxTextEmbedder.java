package com.mediflow.agent.knowledge;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;

/**
 * Runs a Chinese embedding model in-process, so semantic search needs no API key and no external
 * service. The model file ships inside the dependency, so there is nothing to download at runtime.
 *
 * <p>The model is trained on Chinese, which is why it is used instead of the more common English
 * MiniLM: pairing an English model with Chinese material would make similarity mostly meaningless
 * while still appearing to work.
 */
public final class LocalOnnxTextEmbedder implements TextEmbedder {

    /** Recorded with every vector, because vectors from different models are not comparable. */
    public static final String MODEL_NAME = "bge-small-zh-v1.5-q";

    private final EmbeddingModel model;

    public LocalOnnxTextEmbedder() {
        this(new BgeSmallZhV15QuantizedEmbeddingModel());
    }

    LocalOnnxTextEmbedder(EmbeddingModel model) {
        this.model = model;
    }

    @Override
    public String name() {
        return MODEL_NAME;
    }

    @Override
    public int dimensions() {
        return model.dimension();
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("要嵌入的文本不能为空");
        }
        Embedding embedding = model.embed(text.trim()).content();
        return embedding.vector();
    }
}
