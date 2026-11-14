-- Real cart-owned address facts. Source versions change together with original cart CAS.
CREATE TABLE fnb_cart_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT,
 tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
 entity_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 store_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 created_at DATETIME(3) NOT NULL,
 created_by VARCHAR(32) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 updated_by VARCHAR(32) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 deleted INT NOT NULL DEFAULT 0,
 cart_no VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 receiver_name VARCHAR(64) NOT NULL,
 receiver_phone VARCHAR(32) NOT NULL,
 address VARCHAR(512) NOT NULL,
 delivery_instruction VARCHAR(255) NULL,
 lat_e6 INT NULL,
 lng_e6 INT NULL,
 command_history MEDIUMTEXT NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_cart_delivery(entity_no,store_no,cart_no)
) ENGINE=InnoDB;
