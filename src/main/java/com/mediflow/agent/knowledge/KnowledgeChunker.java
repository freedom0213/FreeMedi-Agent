package com.mediflow.agent.knowledge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits material into retrievable slices along headings and paragraphs.
 *
 * <p>Three decisions are worth stating, because they are not obvious:
 *
 * <ul>
 *   <li><b>A chunk never mixes heading paths.</b> Its {@code heading} metadata names exactly one
 *       section, so a citation can be attributed to the right part of a document.
 *   <li><b>A heading line is metadata, not content.</b> The content stays a faithful excerpt of the
 *       material, which matters because a citation shows this text to a patient. What actually gets
 *       embedded is a separate decision for the indexing layer, which can combine the heading with
 *       the text without altering the stored excerpt.
 *   <li><b>Overlap applies only where a paragraph has to be cut.</b> That is the only place a
 *       boundary can land inside a sentence. Paragraph boundaries are already natural semantic
 *       breaks, and repeating text across them would make the same sentence appear in two chunks and
 *       therefore twice in the citations a patient sees.
 * </ul>
 *
 * <p>The sizes are constructor parameters rather than constants because the starting values are only
 * a baseline: the plan requires them to be settled by a small comparison, and a threshold that suits
 * one embedding model does not transfer to another.
 */
public final class KnowledgeChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    /** Sentence enders, kept attached to the sentence they close. */
    private static final String SENTENCE_END = "。！？；!?;";
    private static final int MAX_HEADING_CHARS = 300;

    private final int target;
    private final int overlap;
    private final int max;

    /**
     * @param target preferred chunk size; a chunk is flushed once adding more would exceed it
     * @param overlap characters carried into the next piece when one paragraph has to be cut
     * @param max    hard ceiling no chunk may exceed
     */
    public KnowledgeChunker(int target, int overlap, int max) {
        if (target <= 0 || overlap < 0 || max < target) {
            throw new IllegalArgumentException("chunk sizes out of range: target=" + target
                    + " overlap=" + overlap + " max=" + max);
        }
        // A cut never lands closer than half the target to where the piece started, so keeping the
        // overlap below that guarantees the next piece begins after the previous one. Without this
        // the carry could swallow a whole piece and the overlap would stop being predictable.
        if (overlap * 2 >= target) {
            throw new IllegalArgumentException("overlap must be under half the target: target=" + target
                    + " overlap=" + overlap);
        }
        this.target = target;
        this.overlap = overlap;
        this.max = max;
    }

    /** One retrievable slice: the section it belongs to, and its text. */
    public record Piece(String heading, String content) {
        public Piece {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("chunk content must not be blank");
            }
        }
    }

    public List<Piece> chunk(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<Piece> pieces = new ArrayList<>();
        for (Paragraph paragraph : paragraphs(body)) {
            for (String text : splitWithinLimit(paragraph.text())) {
                pieces.add(new Piece(paragraph.heading(), text));
            }
        }

        List<Piece> chunks = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int buffered = 0;
        String bufferHeading = null;
        for (Piece piece : pieces) {
            boolean sameSection = Objects.equals(bufferHeading, piece.heading());
            int addition = buffer.isEmpty() ? piece.content().length() : piece.content().length() + 2;
            if (!buffer.isEmpty() && (!sameSection || buffered + addition > target)) {
                chunks.add(new Piece(bufferHeading, String.join("\n\n", buffer)));
                buffer = new ArrayList<>();
                buffered = 0;
                bufferHeading = null;
                addition = piece.content().length();
            }
            if (buffer.isEmpty()) {
                bufferHeading = piece.heading();
            }
            buffer.add(piece.content());
            buffered += addition;
        }
        if (!buffer.isEmpty()) {
            chunks.add(new Piece(bufferHeading, String.join("\n\n", buffer)));
        }
        return chunks;
    }

    /** A paragraph together with the heading path it sits under. */
    private record Paragraph(String heading, String text) {
    }

    /**
     * Groups lines into paragraphs and tracks the heading path. The paragraph that precedes a
     * heading belongs to the previous section, so it is flushed before the heading stack changes.
     */
    private List<Paragraph> paragraphs(String body) {
        List<Paragraph> paragraphs = new ArrayList<>();
        Deque<String> headings = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        StringBuilder paragraph = new StringBuilder();

        for (String rawLine : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = rawLine.strip();
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(paragraph, headingPath(headings), paragraphs);
                int level = heading.group(1).length();
                while (!levels.isEmpty() && levels.peek() >= level) {
                    levels.pop();
                    headings.pop();
                }
                levels.push(level);
                headings.push(heading.group(2).strip());
                continue;
            }
            if (line.isEmpty()) {
                flush(paragraph, headingPath(headings), paragraphs);
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(line);
        }
        flush(paragraph, headingPath(headings), paragraphs);
        return paragraphs;
    }

    private static void flush(StringBuilder paragraph, String heading, List<Paragraph> paragraphs) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (!text.isEmpty()) {
            paragraphs.add(new Paragraph(heading, text));
        }
    }

    private static String headingPath(Deque<String> headings) {
        if (headings.isEmpty()) {
            return null;
        }
        List<String> ordered = new ArrayList<>(headings);
        Collections.reverse(ordered);
        String path = String.join(" > ", ordered);
        return path.length() <= MAX_HEADING_CHARS ? path : path.substring(0, MAX_HEADING_CHARS);
    }

    /**
     * Splits one paragraph only when it cannot fit within {@link #max}. A paragraph that already
     * fits is kept whole even if it exceeds {@link #target}: the target is the preferred size of a
     * chunk, not a reason to cut a coherent paragraph in half.
     *
     * <p>When a paragraph does have to be cut, the cut lands on a sentence end where one is
     * available, and the next piece repeats the tail of the previous one so a sentence spanning the
     * cut is not lost.
     */
    private List<String> splitWithinLimit(String text) {
        if (text.length() <= max) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int limit = start + target;
            int cut = limit >= text.length() ? text.length() : sentenceBoundary(text, start, limit);
            String piece = text.substring(start, cut).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (cut >= text.length()) {
                break;
            }
            start = Math.max(cut - overlap, start + 1);
        }
        return pieces;
    }

    /** Last sentence end at or before {@code limit}, or {@code limit} when the text has none there. */
    private static int sentenceBoundary(String text, int start, int limit) {
        int earliest = start + (limit - start) / 2;
        for (int index = limit - 1; index >= earliest; index--) {
            if (SENTENCE_END.indexOf(text.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return limit;
    }

    public int target() {
        return target;
    }

    public int overlap() {
        return overlap;
    }

    public int max() {
        return max;
    }
}
