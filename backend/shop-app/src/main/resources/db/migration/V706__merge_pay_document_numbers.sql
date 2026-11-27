-- 发号收成一对表（TDD-基础能力库对齐 1d）：支付原来的游标 pay_document_sequence、单据号登记 pay_document_number
-- 并进 sys_sequence 与 core_document_number。仓储从此不按归属切表，两种归属共用这一对表。
-- 两边的单据类型与前缀互不重叠（DocumentType 枚举里前缀唯一），所以登记行不会撞键；
-- 游标若同名已存在，取较大的那个，保证新号永远在已发号之后。旧表保留不删，只是不再读写。
INSERT INTO sys_sequence (seq_name, next_value, updated_at)
SELECT p.seq_key, p.next_value, p.updated_at
FROM pay_document_sequence p
ON DUPLICATE KEY UPDATE next_value = GREATEST(sys_sequence.next_value, p.next_value);

INSERT INTO core_document_number (tenant_no, document_type, source_system, source_resource_type, source_no,
                                  document_no, business_date, created_at)
SELECT p.tenant_no, p.document_type, p.source_system, p.source_resource_type, p.source_no,
       p.document_no, p.business_date, p.created_at
FROM pay_document_number p;
