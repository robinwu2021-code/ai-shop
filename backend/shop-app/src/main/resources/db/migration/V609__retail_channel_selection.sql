-- Selection identities only. Original product/price/tax/media owners retain their own data.
CREATE TABLE rtl_channel_selection (
 entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,
 selection_no VARCHAR(32) NOT NULL,
 revision_no VARCHAR(32) NOT NULL,
 PRIMARY KEY(entity_no,store_no,selection_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE rtl_channel_selection_revision (
 entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,
 selection_no VARCHAR(32) NOT NULL,
 revision_no VARCHAR(32) NOT NULL,
 content_hash VARCHAR(64) NOT NULL,
 payload_json LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(entity_no,store_no,selection_no,revision_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Editable identity-only draft and committed request replay share one local transaction with publication.
CREATE TABLE rtl_channel_selection_draft (
 entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,
 selection_no VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL,
 payload_json LONGTEXT NOT NULL,
 PRIMARY KEY(entity_no,store_no,selection_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE rtl_channel_selection_command (
 entity_no VARCHAR(32) NOT NULL,
 store_no VARCHAR(32) NOT NULL,
 action_key VARCHAR(32) NOT NULL,
 key_hash VARCHAR(64) NOT NULL,
 actor_no VARCHAR(32) NOT NULL,
 request_hash VARCHAR(64) NOT NULL,
 result_json LONGTEXT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(entity_no,store_no,action_key,key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
