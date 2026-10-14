-- 经营范围改门店级（TDD-经营范围改门店级）。
--
-- 此前 mch_service_area 只有 entity_no：一个主体所有门店共用一份经营范围，
-- 店主改一家店的范围、其他店全跟着变。跨城多店（深圳一家、山西一家）根本不成立。
-- 现在每家店各有各的范围，可见性按这家店自己的范围算。
--
-- 存量（2026-10-08 线上：全平台 2 行、1 个多门店主体、mch_channel_area 0 行）：
-- 原来那份主体范围**复制到该主体每一家门店**，迁移当天谁都不丢可见性，之后店主再逐店改。

ALTER TABLE mch_service_area
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '这条范围属于哪家门店（mch_store.store_no）。空=迁移前写下、主体没有门店的孤行，不参与任何门店的可见性';

-- ① 原行归默认店
UPDATE mch_service_area a
    JOIN mch_store s ON s.entity_no = a.entity_no AND s.is_default = 1 AND s.deleted = 0
SET a.store_no = s.store_no, a.updated_at = NOW()
WHERE a.store_no IS NULL;

-- ② 没有默认店的主体：归 id 最小的那家
UPDATE mch_service_area a
    JOIN (SELECT entity_no, MIN(id) AS min_id FROM mch_store WHERE deleted = 0 GROUP BY entity_no) f
        ON f.entity_no = a.entity_no
    JOIN mch_store s ON s.id = f.min_id
SET a.store_no = s.store_no, a.updated_at = NOW()
WHERE a.store_no IS NULL;

-- ③ 先换唯一键，再复制。**顺序不能倒**：旧键 (entity_no, level, ref_code) 还在时插副本，
--   副本与原行三列相同，必撞 1062（2026-10-08 在真库副本上实跑撞出来的，H2 用例看不见 ——
--   测试库是 schema-test.sql 重放的终态，根本不跑这段数据迁移）。迁移失败会挡住所有 jar 起不来。
ALTER TABLE mch_service_area DROP INDEX uk_service_area;
CREATE UNIQUE INDEX uk_service_area_store
    ON mch_service_area (entity_no, store_no, level, ref_code);

-- ④ 复制到该主体其余每一家门店（含停用的：重新启用时范围还在）。area_no 加门店 id 后缀保证唯一
INSERT INTO mch_service_area
    (area_no, entity_no, store_no, level, ref_code, source, status, mode,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT CONCAT(a.area_no, '-', s.id), a.entity_no, s.store_no, a.level, a.ref_code, a.source, a.status, a.mode,
       a.tenant_no, NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0
FROM mch_service_area a
    JOIN mch_store s ON s.entity_no = a.entity_no AND s.deleted = 0 AND s.store_no <> a.store_no
WHERE a.store_no IS NOT NULL;

-- ⑤ 门店的渠道子集（SUBSET）原来引用的是默认店那一行的 area_no —— 指到本店自己那一份
UPDATE mch_channel_area ca
    JOIN mch_service_area a ON a.area_no = ca.area_no
    JOIN mch_store s ON s.store_no = ca.store_no
SET ca.area_no = CONCAT(a.area_no, '-', s.id)
WHERE a.store_no <> ca.store_no;

