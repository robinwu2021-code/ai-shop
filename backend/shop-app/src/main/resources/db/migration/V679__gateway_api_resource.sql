-- 网关层的接口资源表（TDD-网关层与统一权限 §2.13、§2.18；ADR-060）。
-- 一行是运营端的一条链接：在哪（本进程入口还是哪个微服务）、要什么权限。
-- 本进程入口的行由网关启动时扫描写入（source=SCAN），是代码的副本：判权读的是进程内的索引，不读这张表；
-- 这张表给人看「哪个角色能访问哪些链接」，并承载 status 这一个可以人工改的开关。
CREATE TABLE IF NOT EXISTS sys_api_resource (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    end_code    VARCHAR(16)  NOT NULL COMMENT '端：ops（以后有 biz、mp）',
    method      VARCHAR(8)   NOT NULL COMMENT 'HTTP 方法；* 表示不限',
    pattern     VARCHAR(255) NOT NULL COMMENT '地址模式，如 /ops/payments/refunds/{payRefundNo}/review',
    source      VARCHAR(16)  NOT NULL DEFAULT 'SCAN' COMMENT 'SCAN 启动扫描的本进程入口 / MANUAL 录入的微服务链接',
    location    VARCHAR(64)  NOT NULL DEFAULT 'LOCAL' COMMENT 'LOCAL 或目标服务名',
    target_path VARCHAR(255) NULL COMMENT '微服务链接的内部地址；本进程入口为空',
    access      VARCHAR(16)  NOT NULL COMMENT 'PUBLIC / LOGIN / POINT / DEFER',
    rule_text   VARCHAR(1000) NULL COMMENT '权限码的与或式，给人读；access=POINT 时有值',
    handler     VARCHAR(500) NULL COMMENT '本进程入口的类与方法，排查用',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED。只有这一列由人改，且只能用来停用',
    stale       TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 最近一次启动扫描里已经没有这条链接；只标记不删除',
    app_version VARCHAR(64)  NULL COMMENT '最近一次写入这一行的应用版本',
    updated_by  VARCHAR(64)  NULL COMMENT '最近一次人工修改的人；扫描写入时为空',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_resource (end_code, method, pattern)
) COMMENT = '网关接口资源：一条链接在哪、要什么权限';

-- 链接涉及的权限码，拆成行，用来和 sys_function_point、sys_role_point 关联查「哪个角色可能访问哪些链接」。
-- 这里只是权限式里出现过的码；它们之间是「且」还是「或」看 sys_api_resource.rule_text。
CREATE TABLE IF NOT EXISTS sys_api_resource_point (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    resource_id BIGINT      NOT NULL COMMENT 'sys_api_resource.id',
    perm_code   VARCHAR(64) NOT NULL COMMENT '权限码',
    UNIQUE KEY uk_api_resource_point (resource_id, perm_code)
) COMMENT = '网关接口资源涉及的权限码';
