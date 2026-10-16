package com.mediflow.agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mediflow.agent.knowledge.KnowledgeChunker.Piece;

/** Chunking is pure logic, so it is tested without a database, an embedding model or a vector store. */
class KnowledgeChunkerTest {

    private static final KnowledgeChunker DEFAULT = new KnowledgeChunker(400, 80, 800);

    @Test
    void blankBodyProducesNoChunks() {
        assertThat(DEFAULT.chunk(null)).isEmpty();
        assertThat(DEFAULT.chunk("   \n\n  ")).isEmpty();
    }

    @Test
    void paragraphsUnderOneHeadingFormOneChunkAndKeepTheirHeading() {
        List<Piece> chunks = DEFAULT.chunk("""
                ## 取消与改约

                患者可以在就诊开始前取消预约。

                改约等同于取消后重新预约。
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("取消与改约");
        assertThat(chunks.get(0).content())
                .isEqualTo("患者可以在就诊开始前取消预约。\n\n改约等同于取消后重新预约。");
    }

    @Test
    void headingLineIsMetadataAndNeverAppearsInTheContent() {
        List<Piece> chunks = DEFAULT.chunk("## 放号时间\n\n号源按周放出。");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("号源按周放出。");
        assertThat(chunks.get(0).content()).doesNotContain("#");
    }

    @Test
    void nestedHeadingsAreRecordedAsAPath() {
        List<Piece> chunks = DEFAULT.chunk("""
                # 预约规则

                ## 取消

                可以取消。
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).heading()).isEqualTo("预约规则 > 取消");
    }

    @Test
    void aChunkNeverMixesTwoHeadingPaths() {
        // Accumulating across headings would label the chunk with whichever section came first.
        List<Piece> chunks = new KnowledgeChunker(1000, 100, 2000).chunk("""
                ## 一

                甲段。

                ## 二

                乙段。
                """);

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(Piece::heading).containsExactly("一", "二");
        assertThat(chunks.get(0).content()).isEqualTo("甲段。");
        assertThat(chunks.get(1).content()).isEqualTo("乙段。");
    }

    @Test
    void paragraphWithinMaxIsKeptWholeEvenWhenLongerThanTarget() {
        // The target is a preference, the max is the ceiling. A coherent paragraph that still fits
        // under the ceiling must not be cut just because it is bigger than the preferred size.
        String paragraph = "甲".repeat(500);
        List<Piece> chunks = DEFAULT.chunk(paragraph);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).hasSize(500);
    }

    @Test
    void paragraphBeyondMaxIsCutAtSentenceEndsAndCarriesTheOverlap() {
        KnowledgeChunker chunker = new KnowledgeChunker(10, 3, 15);
        List<Piece> chunks = chunker.chunk("一二三四五。六七八九十。甲乙丙丁戊。");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(15));
        for (int index = 1; index < chunks.size(); index++) {
            String previous = chunks.get(index - 1).content();
            assertThat(chunks.get(index).content())
                    .startsWith(previous.substring(previous.length() - 3));
        }
        // The whole paragraph is still reachable: the last piece ends where the text ends.
        assertThat(chunks.get(chunks.size() - 1).content()).endsWith("。");
    }

    @Test
    void textWithoutSentenceEndersIsStillHardCutWithinTheCeiling() {
        KnowledgeChunker chunker = new KnowledgeChunker(10, 3, 15);
        List<Piece> chunks = chunker.chunk("乙".repeat(100));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.content().length()).isLessThanOrEqualTo(15));
    }

    @Test
    void invalidSizesAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new KnowledgeChunker(0, 0, 800)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeChunker(400, 80, 300)).isInstanceOf(IllegalArgumentException.class);
        // Overlap at or above half the target cannot be applied predictably.
        assertThatThrownBy(() -> new KnowledgeChunker(400, 200, 800)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeChunker(400, -1, 800)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConfiguredStartingSizesAreAccepted() {
        KnowledgeChunker configured = new KnowledgeChunker(400, 80, 800);

        assertThat(configured.target()).isEqualTo(400);
        assertThat(configured.overlap()).isEqualTo(80);
        assertThat(configured.max()).isEqualTo(800);
    }
}
