package com.mediflow.agent.knowledge.index;

import com.mediflow.agent.knowledge.document.KnowledgeStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The persistent index, backed by Qdrant.
 *
 * <p>Written against Qdrant's REST API rather than an embedding-store abstraction, because this
 * adapter's whole job is to hold <em>less</em> than those abstractions want to store. A point here
 * carries a vector and the fields a search filters on — no text at all — so a hit can only name a
 * chunk to read back from MySQL. That is what keeps the index rebuildable and stops it from becoming
 * a second, drifting copy of the material.
 *
 * <p>Three invariants are enforced here rather than left to convention:
 *
 * <ul>
 *   <li><b>One collection per model.</b> Qdrant fixes a collection's vector size at creation, and
 *       vectors from different models are not comparable anyway, so the model identifier is part of
 *       the collection name. Swapping the model therefore cannot mix vectors; until the index is
 *       rebuilt, a search reports "not built yet" instead of quietly answering "no such material".
 *   <li><b>Only the write path may create the collection.</b> If a read could create it, a fresh
 *       install would answer every question with an empty result, and an empty result is relayed to
 *       a patient as "the hospital has no such rule". A search against a missing collection fails
 *       loudly instead.
 *   <li><b>Publication is not optional.</b> {@code status = PUBLISHED} is part of every filter, so
 *       retired material cannot be reached by asking a different way.
 * </ul>
 */
public final class QdrantKnowledgeIndex implements KnowledgeIndex {
    private static final Logger logger = LoggerFactory.getLogger(QdrantKnowledgeIndex.class);

    /** Payload keys. This list is the complete set of fields that leave MySQL. */
    static final String KEY_STATUS = "status";
    static final String KEY_MODEL = "model";
    static final String KEY_DOC_TYPE = "doc_type";
    static final String KEY_DOCUMENT_ID = "document_id";
    static final String KEY_VERSION = "version";
    static final String KEY_DEPARTMENT_ID = "department_id";
    static final String KEY_DOCTOR_ID = "doctor_id";

    private static final String DISTANCE = "Cosine";

    /** Bounds one request body; the importer can hand over a whole document at once. */
    private static final int UPSERT_BATCH_SIZE = 128;

    private final HttpClient http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String baseName;
    private final String collection;
    private final String model;
    private final int dimensions;
    private final String apiKey;
    private final Duration timeout;

    /**
     * @param baseUrl    Qdrant HTTP endpoint, for example {@code http://localhost:6333}
     * @param collection base collection name; the model identifier is appended to it
     * @param model      embedding model that produces the vectors this index will hold
     * @param dimensions vector length of that model, which fixes the collection's vector size
     * @param apiKey     sent as the {@code api-key} header when non-blank; self-hosted Qdrant needs none
     */
    public QdrantKnowledgeIndex(String baseUrl, String collection, String model, int dimensions,
                                String apiKey, Duration timeout, ObjectMapper json) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("没有配置 Qdrant 地址");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("向量维度必须是正整数，当前为 " + dimensions);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Qdrant 超时必须是正数");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("没有指定 embedding 模型名，无法确定索引归属");
        }
        this.json = Objects.requireNonNull(json, "json");
        this.baseUrl = stripTrailingSlash(baseUrl.trim());
        this.baseName = collection == null || collection.isBlank() ? "mediflow_knowledge" : collection;
        this.model = model.trim();
        this.collection = collectionName(this.baseName, this.model);
        this.dimensions = dimensions;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** The collection actually used, which includes the model identifier. */
    public String collection() {
        return collection;
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    /**
     * Qdrant keeps one vector size per collection, and vectors from different models are not
     * comparable, so the two must not share an index. Putting the model in the name makes that
     * structural instead of a rule someone has to remember.
     */
    public static String collectionName(String baseName, String model) {
        return sanitise(baseName) + "__" + sanitise(model);
    }

    private static String sanitise(String value) {
        String cleaned = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("集合名不能为空：" + value);
        }
        return cleaned;
    }

    private static String stripTrailingSlash(String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Qdrant 地址不能为空");
        }
        return result;
    }

    /**
     * True when the server answers at all, including with a 404 for a collection that has not been
     * created yet — the write path is allowed to create it. An unreachable server is reported here so
     * that a search can say "cannot look it up" instead of "nothing matched".
     */
    @Override
    public boolean available() {
        try {
            execute(request("/collections/" + collection).GET().build(), true);
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }

    /** Point count of this model's collection. A missing collection is genuinely empty, not an outage. */
    @Override
    public int size() {
        JsonNode body = execute(request("/collections/" + collection).GET().build(), true);
        return body == null ? 0 : body.path("result").path("points_count").asInt(0);
    }

    @Override
    public void upsert(List<KnowledgeIndexPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        for (KnowledgeIndexPoint point : points) {
            if (!model.equals(point.model())) {
                throw new IllegalStateException("拒绝写入其它模型产生的向量：当前 " + model + "，来的是 "
                        + point.model() + "；不同模型的向量不可比较，必须整体重建索引");
            }
            if (point.vector().length != dimensions) {
                throw new IllegalStateException("向量维度与集合不一致：集合 " + dimensions + " 维，来的是 "
                        + point.vector().length + " 维；换模型后必须重建索引");
            }
        }
        ensureCollection();
        for (int start = 0; start < points.size(); start += UPSERT_BATCH_SIZE) {
            List<KnowledgeIndexPoint> batch =
                    points.subList(start, Math.min(points.size(), start + UPSERT_BATCH_SIZE));
            ObjectNode body = json.createObjectNode();
            ArrayNode nodes = body.putArray("points");
            for (KnowledgeIndexPoint point : batch) {
                nodes.add(toPointNode(point));
            }
            execute(request("/collections/" + collection + "/points?wait=true")
                    .PUT(jsonBody(body)).build(), false);
        }
        logger.debug("Indexed {} point(s) into {}", points.size(), collection);
    }

    @Override
    public Set<Long> readyChunkIds(List<Long> ids) {
        var ready = new HashSet<Long>();
        for (int start = 0; start < ids.size(); start += UPSERT_BATCH_SIZE) {
            ObjectNode body = json.createObjectNode();
            ArrayNode requested = body.putArray("ids");
            ids.subList(start, Math.min(ids.size(), start + UPSERT_BATCH_SIZE)).forEach(requested::add);
            body.put("with_payload", true);
            body.put("with_vector", false);
            JsonNode response = execute(request("/collections/" + collection + "/points")
                    .POST(jsonBody(body)).build(), true);
            if (response == null) return Set.of();
            for (JsonNode point : response.path("result")) {
                JsonNode payload = point.path("payload");
                if (KnowledgeStatus.PUBLISHED.name().equals(payload.path(KEY_STATUS).asText())
                        && model.equals(payload.path(KEY_MODEL).asText())) {
                    ready.add(point.path("id").asLong());
                }
            }
        }
        return ready;
    }

    @Override
    public void removeVersion(long documentId, int version) {
        // No model condition on purpose: a version that is no longer published should leave the index
        // whatever model produced it, otherwise stale vectors from a previous model could never be
        // cleared. A collection that does not exist is a no-op: nothing was indexed, nothing to drop.
        ObjectNode filter = json.createObjectNode();
        ArrayNode must = filter.putArray("must");
        must.add(keyMatch(KEY_DOCUMENT_ID, documentId));
        must.add(keyMatch(KEY_VERSION, version));
        ObjectNode body = json.createObjectNode();
        body.set("filter", filter);
        execute(request("/collections/" + collection + "/points/delete?wait=true")
                .POST(jsonBody(body)).build(), true);
    }

    @Override
    public List<KnowledgeIndexHit> search(float[] queryVector, KnowledgeIndexFilter filter, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        if (queryVector.length != dimensions) {
            throw new IllegalStateException("查询向量是 " + queryVector.length + " 维，而集合 " + collection
                    + " 是 " + dimensions + " 维；当前 embedding 模型与索引不匹配，请重建索引");
        }
        if (filter != null && filter.publishedDocumentIds() != null && filter.publishedDocumentIds().isEmpty()) {
            return List.of();
        }
        ObjectNode body = json.createObjectNode();
        ArrayNode vector = body.putArray("vector");
        for (float value : queryVector) {
            vector.add(value);
        }
        body.put("limit", topK);
        // The text deliberately does not come back: a hit is a candidate id, nothing more.
        body.put("with_payload", false);
        body.set("filter", buildFilter(filter));

        JsonNode response = execute(request("/collections/" + collection + "/points/search")
                .POST(jsonBody(body)).build(), true);
        if (response == null) {
            throw new IllegalStateException("向量集合 " + collection + " 还不存在，索引尚未建立；"
                    + "请先运行 scripts/index-knowledge.ps1");
        }
        List<KnowledgeIndexHit> hits = new ArrayList<>();
        for (JsonNode node : response.path("result")) {
            hits.add(new KnowledgeIndexHit(node.path("id").asLong(), node.path("score").asDouble()));
        }
        return List.copyOf(hits);
    }

    /**
     * Drops the collection itself rather than just its points, so that a rebuild can also change the
     * vector size. Clearing points would leave a collection that can only ever accept the old model's
     * vectors, which is precisely the situation a rebuild has to escape.
     */
    @Override
    public void clear() {
        execute(request("/collections/" + collection).DELETE().build(), true);
        logger.info("Dropped Qdrant collection {} so it can be rebuilt", collection);
    }

    private void ensureCollection() {
        JsonNode existing = execute(request("/collections/" + collection).GET().build(), true);
        if (existing == null) {
            ObjectNode body = json.createObjectNode();
            ObjectNode vectors = body.putObject("vectors");
            vectors.put("size", dimensions);
            vectors.put("distance", DISTANCE);
            execute(request("/collections/" + collection).PUT(jsonBody(body)).build(), false);
            logger.info("Created Qdrant collection {} for model {} ({} dimensions, {})",
                    collection, model, dimensions, DISTANCE);
            return;
        }
        int existingSize = existing.path("result").path("config").path("params")
                .path("vectors").path("size").asInt(-1);
        if (existingSize != dimensions) {
            throw new IllegalStateException("Qdrant 集合 " + collection + " 已是 " + existingSize
                    + " 维，而模型 " + model + " 产出 " + dimensions + " 维。集合建成后维度不可更改，"
                    + "请重建索引：scripts/index-knowledge.ps1 -Rebuild");
        }
    }

    private ObjectNode toPointNode(KnowledgeIndexPoint point) {
        ObjectNode node = json.createObjectNode();
        node.put("id", point.chunkId());
        ArrayNode vector = node.putArray("vector");
        for (float value : point.vector()) {
            vector.add(value);
        }
        ObjectNode payload = node.putObject("payload");
        payload.put(KEY_DOCUMENT_ID, point.documentId());
        payload.put(KEY_VERSION, point.version());
        payload.put(KEY_DOC_TYPE, point.docType().name());
        payload.put(KEY_STATUS, point.status().name());
        payload.put(KEY_MODEL, point.model());
        if (point.departmentId() != null) {
            payload.put(KEY_DEPARTMENT_ID, point.departmentId());
        }
        if (point.doctorId() != null) {
            payload.put(KEY_DOCTOR_ID, point.doctorId());
        }
        return node;
    }

    /**
     * Builds the metadata filter. The physical point must be ready; a department or doctor restriction
     * additionally keeps material that declares no subject at all.
     */
    private ObjectNode buildFilter(KnowledgeIndexFilter filter) {
        ObjectNode root = json.createObjectNode();
        ArrayNode must = root.putArray("must");
        must.add(keyMatch(KEY_STATUS, KnowledgeStatus.PUBLISHED.name()));
        if (filter != null) {
            if (filter.publishedDocumentIds() != null) {
                var documentCondition = must.addObject();
                documentCondition.put("key", KEY_DOCUMENT_ID);
                var ids = documentCondition.putObject("match").putArray("any");
                filter.publishedDocumentIds().forEach(ids::add);
            }
            if (filter.docType() != null) {
                must.add(keyMatch(KEY_DOC_TYPE, filter.docType().name()));
            }
            if (filter.departmentId() != null) {
                must.add(scopeCondition(KEY_DEPARTMENT_ID, filter.departmentId()));
            }
            if (filter.doctorId() != null) {
                must.add(scopeCondition(KEY_DOCTOR_ID, filter.doctorId()));
            }
        }
        return root;
    }

    /**
     * "This subject, or no subject". Material with no department or doctor is the hospital's general
     * rules and notices, which every question may draw on; material declaring a <em>different</em>
     * subject is excluded, because a cardiology question must not be answered with another
     * department's material.
     *
     * <p>The {@code should} lives inside a nested filter so it is a requirement rather than a score
     * boost, which is how Qdrant treats a {@code should} sitting next to a {@code must}.
     */
    private ObjectNode scopeCondition(String key, long value) {
        ObjectNode anyOf = json.createObjectNode();
        ArrayNode should = anyOf.putArray("should");
        ObjectNode absent = json.createObjectNode();
        absent.putObject("is_empty").put("key", key);
        should.add(absent);
        should.add(keyMatch(key, value));
        return anyOf;
    }

    private ObjectNode keyMatch(String key, Object value) {
        ObjectNode condition = json.createObjectNode();
        condition.put("key", key);
        ObjectNode match = condition.putObject("match");
        if (value instanceof Long || value instanceof Integer) {
            match.put("value", ((Number) value).longValue());
        } else {
            match.put("value", String.valueOf(value));
        }
        return condition;
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        return builder;
    }

    private HttpRequest.BodyPublisher jsonBody(JsonNode body) {
        try {
            return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),
                    StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("无法序列化请求体", impossible);
        }
    }

    /**
     * @param allowNotFound when true a 404 returns null instead of throwing. That is how a collection
     *                      that has not been created yet is told apart from a failing server
     */
    private JsonNode execute(HttpRequest request, boolean allowNotFound) {
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException unreachable) {
            throw new IllegalStateException("无法访问 Qdrant（" + baseUrl + "）：" + unreachable.getMessage(),
                    unreachable);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("访问 Qdrant 时被中断", interrupted);
        }
        int status = response.statusCode();
        if (allowNotFound && status == 404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Qdrant 返回 HTTP " + status + "：" + errorDetail(response.body()));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (IOException malformed) {
            throw new IllegalStateException("Qdrant 响应不是合法 JSON：" + errorDetail(body), malformed);
        }
    }

    private String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "(空响应)";
        }
        try {
            String message = json.readTree(body).path("status").path("error").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (IOException ignored) {
            // Fall through to the raw body, which is still more useful than nothing.
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
