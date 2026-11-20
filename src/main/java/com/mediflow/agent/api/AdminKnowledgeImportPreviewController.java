package com.mediflow.agent.api;

import com.mediflow.agent.knowledge.KnowledgeDocType;
import com.mediflow.agent.knowledge.KnowledgeDocument;
import com.mediflow.agent.knowledge.KnowledgeImporter;
import com.mediflow.agent.knowledge.KnowledgePublisher;
import com.mediflow.agent.knowledge.KnowledgeStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@RestController
@Profile("mysql")
public class AdminKnowledgeImportPreviewController {
    private static final int MAX_BYTES = 256 * 1024;
    private final KnowledgeImporter importer;
    private final KnowledgePublisher publisher;

    public AdminKnowledgeImportPreviewController(KnowledgeImporter importer, KnowledgePublisher publisher) {
        this.importer = importer;
        this.publisher = publisher;
    }

    @PostMapping(path = "/api/v1/admin/knowledge/import-preview", consumes = "text/markdown")
    public PreviewResponse preview(HttpServletRequest request) throws IOException {
        return PreviewResponse.from(importer.preview(readMarkdown(request)));
    }

    @PostMapping(path = "/api/v1/admin/knowledge/import-drafts", consumes = "text/markdown")
    public KnowledgePublisher.SavedDraft saveDraft(HttpServletRequest request,
            @RequestHeader("X-Preview-Hash") String hash,
            @RequestHeader("X-Expected-Latest-Version") int latest,
            @RequestHeader("X-Expected-Published-Version") int published) throws IOException {
        return publisher.saveDraft(readMarkdown(request), hash, latest, published);
    }

    @PostMapping("/api/v1/admin/knowledge/documents/{docNo}/versions/{version}/index")
    public KnowledgePublisher.IndexStep index(@PathVariable String docNo, @PathVariable int version) {
        return publisher.indexDraftStep(docNo, version);
    }

    @PostMapping("/api/v1/admin/knowledge/documents/{docNo}/versions/{version}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable String docNo, @PathVariable int version, @Valid @RequestBody PublishRequest request) {
        publisher.publish(docNo, version, request.expectedPublishedVersion());
    }

    public record PublishRequest(@NotNull @PositiveOrZero Integer expectedPublishedVersion) {}

    @ExceptionHandler(KnowledgePublisher.PublicationException.class)
    ResponseEntity<Map<String, String>> publicationError(KnowledgePublisher.PublicationException failure) {
        HttpStatus status = switch (failure.problem()) {
            case MISSING -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INDEX_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("error", failure.getMessage()));
    }

    private static String readMarkdown(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        byte[] payload = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (payload.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return decodeMarkdown(payload);
    }

    private static String decodeMarkdown(byte[] payload) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("文件编码无效，请另存为 UTF-8 后再预览");
        }
    }

    public enum Disposition { NEW_DOCUMENT, NEW_VERSION, DUPLICATE }

    public record PreviewChunk(int chunkNo, String heading, String content, int charCount) {}

    public record ExistingVersion(int version, KnowledgeStatus status) {
        static ExistingVersion from(KnowledgeDocument document) {
            return document == null ? null : new ExistingVersion(document.version(), document.status());
        }
    }

    public record PreviewResponse(String docNo, String title, KnowledgeDocType docType, String source,
                                  String departmentId, String doctorId, Disposition disposition,
                                  Integer proposedVersion, ExistingVersion published, ExistingVersion duplicate,
                                  List<PreviewChunk> chunks, String contentHash, int latestVersion) {
        static PreviewResponse from(KnowledgeImporter.ImportPreview preview) {
            var document = preview.document();
            var duplicate = ExistingVersion.from(preview.duplicate());
            var latest = preview.latest();
            var disposition = duplicate != null ? Disposition.DUPLICATE
                    : latest == null ? Disposition.NEW_DOCUMENT : Disposition.NEW_VERSION;
            Integer proposedVersion = duplicate == null ? (latest == null ? 1 : latest.version() + 1) : null;
            var chunks = IntStream.range(0, preview.chunks().size()).mapToObj(index -> {
                var chunk = preview.chunks().get(index);
                return new PreviewChunk(index + 1, chunk.heading(), chunk.content(), chunk.content().length());
            }).toList();
            return new PreviewResponse(document.docNo(), document.title(), document.docType(), document.source(),
                    id(document.departmentId()), id(document.doctorId()), disposition, proposedVersion,
                    ExistingVersion.from(preview.published()), duplicate, chunks, document.contentHash(),
                    latest == null ? 0 : latest.version());
        }

        private static String id(Long value) {
            return value == null ? null : value.toString();
        }
    }
}
