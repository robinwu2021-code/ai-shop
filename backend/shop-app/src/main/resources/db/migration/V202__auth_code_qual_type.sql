-- 门槛码挂上「要哪一类证」—— 从一句给人读的文案，变成机器能判的类型。
--
-- sys_auth_code.required_qualification 今天是「食品经营许可证」这样一句话：
-- 人看得懂，程序认不出它对应 QualificationType.FOOD_PERMIT。于是「这家店传了执照，
-- 能解锁哪几类」这个问题只能靠人对着两张表比 —— 而那正是没人去比、
-- 以至于线上一条资质、一条授权都没有的原因。
--
-- 挂上之后这条链才闭合：商家传证 → 平台看证 → 按 qual_type 反查该授哪些码。
-- 本迁移只做「码 → 证件类型」这一半，自动授码那半留给运营点一下（有量之后再谈 OCR）。
ALTER TABLE sys_auth_code
  ADD COLUMN qual_type VARCHAR(32) DEFAULT NULL COMMENT '这个门槛要哪一类证：BUSINESS_LICENSE/FOOD_PERMIT/FOOD_WORKSHOP/OTHER，与 mch_qualification.qual_type 同值域。NULL = 无需证件（如日用百货、家政）';

-- 回填：按 required_qualification 那句文案对号入座。
-- 食用农产品只要营业执照 —— 卖菜卖水果不需要食品经营许可证，这一条是《食品安全法》
-- 第三十五条第二款的口子，也是社区店能开起来的前提。
UPDATE sys_auth_code SET qual_type = 'BUSINESS_LICENSE'
 WHERE required_qualification LIKE '营业执照%';
UPDATE sys_auth_code SET qual_type = 'FOOD_PERMIT'
 WHERE required_qualification LIKE '食品经营许可证%';
-- 备案类（预包装食品、配方乳粉、饲料）都归 OTHER：它们各自是一张单独的备案凭证，
-- 而 QualificationType 一期只有四个值。**不为它们各造一个类型** ——
-- 值域一旦长到十几个，运营在下拉框里选错的概率比现在高得多。
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%备案%';
-- 药品经营许可证同样归 OTHER，但它在授码那一侧是**永不自动**的（见 V201 的说明）
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%药品经营许可证%';
UPDATE sys_auth_code SET qual_type = 'OTHER'
 WHERE required_qualification LIKE '%维修资质%';
