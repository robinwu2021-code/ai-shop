-- F15 · Review R19：差异单每天重复生成（去重键带账期日）；自动说明写进 resolution，把「处置结论」那一列占了。
-- dedup_key = 通道|流水号|差异类型，不含日期：同一个没解决的问题只有一条，第二天不再新增。
-- 先可空、不加唯一键：存量行要回填后才能加唯一约束（开发计划迁移 13，I6）。
-- note 放系统生成的说明；resolution 只放运营填写的处置结论。
ALTER TABLE stl_recon_diff ADD COLUMN dedup_key VARCHAR(191) DEFAULT NULL COMMENT '去重键：通道|流水号|差异类型（不含日期）';
ALTER TABLE stl_recon_diff ADD COLUMN note VARCHAR(255) DEFAULT NULL COMMENT '系统生成的说明（自查原因、通道回执摘要）；resolution 只放处置结论';
