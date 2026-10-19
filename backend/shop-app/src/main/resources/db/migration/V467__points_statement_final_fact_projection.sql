-- Trusted final facts get a stable first-ready sequence for bounded statement builds.
CREATE TABLE IF NOT EXISTS pts_statement_final_fact (
    ready_seq BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type_code VARCHAR(40) NOT NULL,
    sub_order_no VARCHAR(40) NOT NULL,
    final_at_millis BIGINT NOT NULL,
    CONSTRAINT uk_pts_statement_final_source UNIQUE(type_code, sub_order_no),
    INDEX idx_pts_statement_final_time(type_code, final_at_millis, ready_seq)
);

-- Existing payloads were accepted by the lifecycle fact handler. Keep datetime
-- arithmetic independent of the MySQL session timezone; Instant serializes in UTC.
INSERT INTO pts_statement_final_fact(type_code,sub_order_no,final_at_millis)
SELECT type_code,sub_order_no,
       TIMESTAMPDIFF(MICROSECOND,CAST('1970-01-01 00:00:00' AS DATETIME),
          STR_TO_DATE(LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.occurredAt')),19),
                      '%Y-%m-%dT%H:%i:%s')) DIV 1000
       + CASE WHEN SUBSTRING(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.occurredAt')),20,1)='.'
           THEN CAST(RPAD(LEFT(SUBSTRING_INDEX(SUBSTRING(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.occurredAt')),21),'Z',1),3),3,'0') AS UNSIGNED)
           ELSE 0 END
FROM pts_lifecycle_fact
WHERE deleted=0
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.typeCode'))=type_code
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.subOrderNo'))=sub_order_no
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.kind'))='FINALIZED'
  AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.finalFact.occurredAt')) REGEXP
      '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?Z$';

-- Construction is resumable; READY means frozen candidates, not a confirmed/payable bill.
CREATE TABLE IF NOT EXISTS pts_statement_build (
    build_no VARCHAR(40) NOT NULL PRIMARY KEY,
    type_code VARCHAR(40) NOT NULL,
    store_no VARCHAR(40) NOT NULL,
    period_from BIGINT NOT NULL,
    period_to BIGINT NOT NULL,
    high_water BIGINT NOT NULL,
    scan_cursor BIGINT NOT NULL DEFAULT 0,
    source_count BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(24) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_pts_statement_build_period UNIQUE(type_code,store_no,period_from),
    INDEX idx_pts_statement_build_pending(type_code,state,build_no)
);
CREATE TABLE IF NOT EXISTS pts_statement_build_item (
    build_no VARCHAR(40) NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    ready_seq BIGINT NOT NULL,
    route_key VARCHAR(40) NOT NULL,
    source_version BIGINT NOT NULL,
    points BIGINT NOT NULL,
    PRIMARY KEY(build_no,ready_seq),
    CONSTRAINT uk_pts_statement_build_source UNIQUE(position_no),
    INDEX idx_pts_statement_build_route(build_no,route_key,ready_seq)
);

-- Source revisions are append-only; a statement revision's version sum selects its exact history.
CREATE TABLE IF NOT EXISTS pts_statement_source_history (
    statement_no VARCHAR(40) NOT NULL,
    position_no VARCHAR(40) NOT NULL,
    source_version BIGINT NOT NULL,
    change_seq BIGINT NOT NULL,
    points BIGINT NOT NULL,
    PRIMARY KEY(statement_no,position_no,source_version),
    INDEX idx_pts_statement_source_history_seq(statement_no,position_no,change_seq)
);

-- Aggregate validation and first-source lookup stay within one statement.
CREATE INDEX idx_pts_redeem_statement_source ON pts_redeem_position(statement_no,deleted,id);
