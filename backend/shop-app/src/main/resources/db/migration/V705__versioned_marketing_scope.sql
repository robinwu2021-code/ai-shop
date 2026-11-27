-- Extend the existing published facts in place. The original rule snapshot and hash remain immutable.
ALTER TABLE pmt_activity_scope
    ADD COLUMN owner_type VARCHAR(16) NULL,
    ADD COLUMN owner_no VARCHAR(64) NULL,
    ADD COLUMN scope_dimension VARCHAR(16) NULL,
    ADD COLUMN included TINYINT NOT NULL DEFAULT 1,
    ADD COLUMN sort_no INT NOT NULL DEFAULT 0,
    ADD COLUMN rule_snapshot JSON NULL,
    ADD COLUMN activity_version_no VARCHAR(64) NULL COMMENT '报名选品时的平台活动发布版本，无发布版则为空',
    MODIFY COLUMN ref_no VARCHAR(128) NOT NULL;

UPDATE pmt_activity_scope s
JOIN pmt_activity_version v ON v.tenant_no=s.tenant_no AND v.version_no=s.version_no
SET s.owner_type='ACTIVITY', s.owner_no=v.activity_no, s.scope_dimension='TARGET';

ALTER TABLE pmt_activity_scope
    MODIFY COLUMN owner_type VARCHAR(16) NOT NULL,
    MODIFY COLUMN owner_no VARCHAR(64) NOT NULL,
    MODIFY COLUMN scope_dimension VARCHAR(16) NOT NULL,
    DROP INDEX uk_scope,
    ADD UNIQUE KEY uk_owner_scope (tenant_no,owner_type,owner_no,version_no,scope_dimension,scope_kind,ref_no),
    ADD KEY ix_scope_target (tenant_no,scope_dimension,scope_kind,ref_no,owner_type),
    ADD CONSTRAINT ck_scope_owner CHECK (
        (owner_type='ACTIVITY' AND (
            (scope_dimension='TARGET' AND scope_kind IN ('ALL','STORE','CATEGORY','GOODS')) OR
            (scope_dimension='AUDIENCE' AND scope_kind IN ('TAG','LEVEL','SOURCE','SEGMENT','NON_MEMBER')))) OR
        (owner_type='ENT_TEMPLATE' AND scope_dimension='TARGET' AND scope_kind IN ('ALL','STORE','BRAND_STORES','CATEGORY','GOODS','SKU')) OR
        (owner_type='ENROLLMENT' AND scope_dimension='TARGET' AND scope_kind='GOODS')),
    ADD CONSTRAINT ck_scope_all CHECK (scope_kind<>'ALL' OR ref_no='*'),
    ADD CONSTRAINT ck_scope_included CHECK (included=1 OR (owner_type='ENT_TEMPLATE' AND included=0)),
    ADD CONSTRAINT ck_scope_rule CHECK (rule_snapshot IS NULL OR scope_kind='SEGMENT'),
    ADD CONSTRAINT ck_scope_activity_version CHECK (activity_version_no IS NULL OR owner_type='ENROLLMENT');

INSERT INTO pmt_activity_scope
    (tenant_no,owner_type,owner_no,version_no,scope_dimension,scope_kind,ref_no,rule_snapshot,created_at,updated_at,created_by,updated_by)
SELECT tenant_no,'ACTIVITY',activity_no,version_no,'AUDIENCE','SEGMENT',
       JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.definition.audienceRef')),
       CASE WHEN JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.audienceRuleSnapshot'))='STRING'
            THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.audienceRuleSnapshot')) AS JSON) END,
       created_at,updated_at,created_by,updated_by
FROM pmt_activity_version
WHERE JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.definition.audienceRef'))='STRING';

-- Older enrollments only retained their current goods. Their submission revision is unknown.
ALTER TABLE pmt_enrollment ADD COLUMN scope_version_no VARCHAR(64) NULL COMMENT '选品对应的报名提交修订号';
