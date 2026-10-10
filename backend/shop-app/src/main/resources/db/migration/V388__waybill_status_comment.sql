-- =====================================================================
-- V388 运单状态的列注释跟上取值（TDD-物流模块 批 3）
--
-- 批 3 起运单状态多了 DELIVERING（派件中）与 CANCELLED（已作废）。只改注释 ——
-- 枚举对账脚本按建表注释里「值/值/值」认取值域，注释落后于代码，端上类型与后端就「对不上」。
-- （V387 已在生产应用，一个字都不能改，所以新开一条。）
-- =====================================================================

ALTER TABLE lgs_waybill
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PICKED_UP/IN_TRANSIT/DELIVERING/DELIVERED/EXCEPTION/CANCELLED。只进不退：EXCEPTION 不是终态（疑难件可能之后又派送成功），DELIVERED 与 CANCELLED 是';
