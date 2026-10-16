package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Verifies the local model against the two things the pipeline depends on: it must accept a
 * chunk-sized input, and its similarity has to actually reflect meaning rather than be a
 * plausible-looking no-op.
 */
class LocalOnnxTextEmbedderTest {

    private final TextEmbedder embedder = new LocalOnnxTextEmbedder();

    @Test
    void reportsADimensionAndProducesVectorsOfThatLength() {
        int dimensions = embedder.dimensions();
        float[] vector = embedder.embed("预约后可以取消吗");

        assertThat(embedder.name()).isEqualTo(LocalOnnxTextEmbedder.MODEL_NAME);
        assertThat(dimensions).isPositive();
        assertThat(vector).hasSize(dimensions);
        System.out.println("[probe] model=" + embedder.name() + " dimensions=" + dimensions);
    }

    @Test
    void acceptsAnInputOfTheConfiguredChunkCeiling() {
        // The embedding model is the real constraint on chunk size, so the ceiling assumed by the
        // chunker has to be proven to fit rather than assumed to.
        String longestChunk = "预约挂号相关说明。".repeat(100).substring(0, 800);

        float[] vector = embedder.embed(longestChunk);

        assertThat(longestChunk).hasSize(800);
        assertThat(vector).hasSize(embedder.dimensions());
    }

    @Test
    void recordsHowTheModelTreatsAChunkLongerThanItsContextWindow() {
        // A transformer has a maximum input length and truncates beyond it, which would mean the tail
        // of an over-long chunk never influences retrieval. This probe records what actually happens
        // so the chunk ceiling can be settled with evidence instead of an assumption.
        String firstHalf = "取消预约的规则说明。".repeat(40);
        String secondHalf = "医院停车位置说明。".repeat(40);
        String combined = firstHalf + secondHalf;

        float[] combinedVector = embedder.embed(combined);
        double towardFirst = cosine(combinedVector, embedder.embed("取消预约"));
        double towardSecond = cosine(combinedVector, embedder.embed("停车"));

        System.out.printf("[probe] combined=%d chars first=%d second=%d -> first=%.4f second=%.4f%n",
                combined.length(), firstHalf.length(), secondHalf.length(), towardFirst, towardSecond);
        assertThat(combinedVector).hasSize(embedder.dimensions());
    }

    @Test
    void relatedTextScoresHigherThanUnrelatedText() {
        float[] question = embedder.embed("我预约之后还能取消吗");
        float[] related = embedder.embed("患者可以在就诊开始前取消预约，取消后该号源回到可约状态");
        float[] unrelated = embedder.embed("抽血检查前可以饮少量白水，但不要饮用含糖饮料或牛奶");

        double relatedScore = cosine(question, related);
        double unrelatedScore = cosine(question, unrelated);

        System.out.printf("[probe] related=%.4f unrelated=%.4f%n", relatedScore, unrelatedScore);
        assertThat(relatedScore).isGreaterThan(unrelatedScore);
    }

    @Test
    void similarPhrasingsAreCloserThanDistantTopics() {
        float[] cancel = embedder.embed("怎么取消预约");
        float[] cancelAgain = embedder.embed("预约能不能退掉");
        float[] carParking = embedder.embed("医院附近哪里可以停车");

        double sameTopic = cosine(cancel, cancelAgain);
        double otherTopic = cosine(cancel, carParking);

        System.out.printf("[probe] same-topic=%.4f other-topic=%.4f%n", sameTopic, otherTopic);
        assertThat(sameTopic).isGreaterThan(otherTopic);
    }

    @Test
    void aBlankInputIsRejected() {
        assertThat(catchException(() -> embedder.embed("   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Throwable catchException(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    static double cosine(float[] left, float[] right) {
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
