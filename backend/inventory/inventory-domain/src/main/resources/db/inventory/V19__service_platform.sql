-- 进销存独立服务 · 平台层（TDD-进销存独立服务与多业态 §2.4 / §2.5 / §2.12 / §2.14，M2 T2.1 ~ T2.3）
--
-- 一个库里会有多个调用系统（测试环境共用一套）。业主从此带系统码，
-- 外部引用的唯一性按「系统 + 外部引用」算：商城的主体号与 ShareHub 的运营方号撞了不算冲突。

-- 系统码登记：凭证与业主只能引用登记过的系统
CREATE TABLE inv_client_system (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_code VARCHAR(32) NOT NULL COMMENT 'SHOP / SHAREHUB / CHARGING …',
    name VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_client_system (system_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用系统登记';

INSERT INTO inv_client_system (system_code, name, created_by) VALUES ('SHOP', 'ai-shop', 'MIGRATION');
INSERT INTO inv_client_system (system_code, name, created_by) VALUES ('SHAREHUB', 'ShareHub', 'MIGRATION');
INSERT INTO inv_client_system (system_code, name, created_by) VALUES ('CHARGING', 'charging', 'MIGRATION');

-- 业主：带上所属系统、写入方、当前档案版本。存量业主全部是商城的、由内嵌进程写
ALTER TABLE inv_owner ADD COLUMN system_code VARCHAR(32) NOT NULL DEFAULT 'SHOP' COMMENT '所属调用系统';
ALTER TABLE inv_owner ADD COLUMN write_authority VARCHAR(16) NOT NULL DEFAULT 'EMBEDDED' COMMENT 'EMBEDDED / SVC：谁有权写这本账，同一时刻只有一个';
ALTER TABLE inv_owner ADD COLUMN profile_version INT DEFAULT NULL COMMENT '当前生效的业态档案版本；空 = 未装档案';
DROP INDEX uk_owner_ext ON inv_owner;
CREATE UNIQUE INDEX uk_owner_system_ext ON inv_owner (system_code, external_ref);

-- 凭证：有 system_code 的是服务凭证（独立服务入口用）；为空的是存量开放接口只读凭证
ALTER TABLE inv_open_credential ADD COLUMN system_code VARCHAR(32) DEFAULT NULL COMMENT '服务凭证所属系统；空 = 存量开放接口凭证';
ALTER TABLE inv_open_credential ADD COLUMN capabilities VARCHAR(255) DEFAULT NULL COMMENT '逗号分隔的能力包；空 = 只按业主档案';

-- 业态档案：业主级、带版本、只追加。单据过账时记下所用版本，规则变了也能解释历史
CREATE TABLE inv_business_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_id VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    body TEXT NOT NULL COMMENT '档案 JSON（inventory-api BusinessProfile）',
    body_hash CHAR(64) NOT NULL,
    reason VARCHAR(255) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_business_profile (owner_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业态档案（只追加）';

-- 命令幂等：同键同参返回原结果，同键异参冲突；与业务写入同事务
CREATE TABLE inv_command (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_code VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    request_id VARCHAR(64) NOT NULL COMMENT 'Idempotency-Key',
    command_type VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'DONE',
    result_body TEXT DEFAULT NULL,
    actor VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_command (system_code, owner_id, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写命令幂等与结果';

-- 审计：所有写动作，只追加
CREATE TABLE inv_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    system_code VARCHAR(32) NOT NULL,
    owner_id VARCHAR(32) NOT NULL,
    actor VARCHAR(64) DEFAULT NULL COMMENT 'STAFF:E1003 / AGENT:AG001 / SYSTEM:job',
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    before_body TEXT DEFAULT NULL,
    after_body TEXT DEFAULT NULL,
    reason VARCHAR(255) DEFAULT NULL,
    request_id VARCHAR(64) DEFAULT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_inv_audit_owner (owner_id, created_at),
    KEY idx_inv_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='写动作审计（只追加）';
