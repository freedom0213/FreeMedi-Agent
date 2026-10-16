-- Hospital service material lives here and is indexed for semantic search later. MySQL keeps the
-- authoritative text, version and publish state; the vector store is only a rebuildable index, so
-- losing the index must never lose business data and re-indexing must be able to rebuild it from
-- these rows alone.
--
-- The version is part of the chunk key because an updated or withdrawn document has to stop being
-- retrievable immediately. Chunks are keyed by (document, version, position), so re-indexing writes
-- the new version next to the old one and the index drops the superseded points.

CREATE TABLE knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    doc_no VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    doc_type VARCHAR(32) NOT NULL
        COMMENT 'APPOINTMENT_RULE / VISIT_NOTICE / DEPARTMENT_INTRO / DOCTOR_PROFILE / PREPARATION',
    department_id BIGINT NULL,
    doctor_id BIGINT NULL,
    source VARCHAR(200) NOT NULL,
    version INT NOT NULL,
    status VARCHAR(24) NOT NULL COMMENT 'DRAFT / PUBLISHED / ARCHIVED',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_no_version (doc_no, version),
    KEY idx_knowledge_document_status_type (status, doc_type),
    CONSTRAINT fk_knowledge_document_department
        FOREIGN KEY (department_id) REFERENCES department (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_knowledge_document_doctor
        FOREIGN KEY (doctor_id) REFERENCES doctor (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    version INT NOT NULL,
    chunk_no INT NOT NULL,
    heading VARCHAR(300) NULL,
    content TEXT NOT NULL,
    char_count INT NOT NULL,
    embedding_status VARCHAR(24) NOT NULL COMMENT 'PENDING / INDEXED / FAILED',
    index_error VARCHAR(500) NULL,
    indexed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_chunk_position (document_id, version, chunk_no),
    KEY idx_knowledge_chunk_embedding_status (embedding_status),
    CONSTRAINT fk_knowledge_chunk_document
        FOREIGN KEY (document_id) REFERENCES knowledge_document (id)
        ON UPDATE RESTRICT ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
