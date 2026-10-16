package com.mediflow.agent.knowledge;

/**
 * Turns text into a vector.
 *
 * <p>A port rather than a direct dependency on one model, for two reasons that both come from the
 * plan: the embedding source is configuration (a local model by default, an API model when a key is
 * configured), and tests must be able to run without any model at all.
 *
 * <p>{@link #name()} and {@link #dimensions()} are part of the contract, not decoration. Vectors
 * from different models are not comparable, so the model has to be recorded next to anything derived
 * from it, and changing it requires rebuilding the index rather than mixing the two.
 */
public interface TextEmbedder {

    /** Stable identifier of the model, recorded so a vector can be traced to what produced it. */
    String name();

    /** Vector length. A different value means a different index, never a mixed one. */
    int dimensions();

    float[] embed(String text);
}
