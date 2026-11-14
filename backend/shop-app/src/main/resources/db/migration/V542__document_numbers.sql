-- Human-facing numbers only. Internal financial IDs, order rules and event payloads remain unchanged.
CREATE TABLE pay_document_sequence (
    seq_key VARCHAR(32) NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (seq_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE core_document_number (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(64) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_resource_type VARCHAR(64) NOT NULL,
    source_no VARCHAR(128) NOT NULL,
    document_no VARCHAR(32) NOT NULL,
    business_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_core_document_no (document_no),
    UNIQUE KEY uk_core_document_source (tenant_no,document_type,source_system,source_resource_type,source_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE pay_document_number (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(64) NOT NULL,
    document_type VARCHAR(40) NOT NULL,
    source_system VARCHAR(32) NOT NULL,
    source_resource_type VARCHAR(64) NOT NULL,
    source_no VARCHAR(128) NOT NULL,
    document_no VARCHAR(32) NOT NULL,
    business_date DATE NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_document_no (document_no),
    UNIQUE KEY uk_pay_document_source (tenant_no,document_type,source_system,source_resource_type,source_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
