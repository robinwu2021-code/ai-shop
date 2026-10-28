-- 支付服务 v4 · 迁移 13（接入守卫 G3 · 开发计划 M2.5；db/pay 号须连续，先合先占 —— 开发计划 §0.2）：
-- 内部调用不校验、只留痕（用户 2026-10-27 定）—— 来源信息由调用参数 CallSource 带进来，落在请求记录里。
-- 外部入口这些列为空；client_ip 仍是支付实际看到的对端 IP（内嵌形态为空），source_ip 是调用方自报的。

ALTER TABLE pay_api_request_log ADD COLUMN source_service VARCHAR(64) NULL COMMENT '调用方服务名（spring.application.name）；内部调用取不到记 UNKNOWN';
ALTER TABLE pay_api_request_log ADD COLUMN source_module VARCHAR(24) NULL COMMENT '调用方业务线：MALL / FOOD / RETAIL / BEAUTY / STORED_VALUE …';
ALTER TABLE pay_api_request_log ADD COLUMN source_instance VARCHAR(128) NULL COMMENT '调用方实例：主机名或 pod 名';
ALTER TABLE pay_api_request_log ADD COLUMN source_ip VARCHAR(64) NULL COMMENT '调用方自报的服务器 IP；client_ip 仍是支付实际看到的对端 IP（内嵌形态为空）';
ALTER TABLE pay_api_request_log ADD COLUMN origin_api VARCHAR(128) NULL COMMENT '触发这次调用的上游接口或任务名，如 POST /mp/orders/{orderNo}/pay';
ALTER TABLE pay_api_request_log ADD COLUMN origin_client VARCHAR(24) NULL COMMENT '上游请求来源端：认证链端码 APP_C / APP_BIZ / WEB_OPS / H5 / PARTNER；JOB / OUTBOX / UNKNOWN';
ALTER TABLE pay_api_request_log ADD COLUMN origin_ip VARCHAR(64) NULL COMMENT '上游请求的终端用户 IP（经可信代理头）';
ALTER TABLE pay_api_request_log ADD COLUMN origin_ua_digest CHAR(64) NULL COMMENT '上游请求 User-Agent 的 SHA-256';

CREATE INDEX idx_pay_api_request_log_6 ON pay_api_request_log (source_service, received_at);
