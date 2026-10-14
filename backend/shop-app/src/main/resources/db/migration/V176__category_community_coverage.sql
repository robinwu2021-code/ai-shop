-- 类目补齐到「楼下小店真的在卖什么」（承 V175）。
--
-- 判据只有一条，与平台定位一致（ADR-004：服务小商家；C 端是「邻居在自己社区买东西」）：
-- **这东西楼下拿得到、买得频**。低频大件、需冷链、需专卖许可的一律不开 ——
-- 类目一开商家就会传，而下架比不上架贵得多。

-- ── 1. 授权码：先有发证机关，再开门槛 ─────────────────────────
--
-- 顺序不能反。先开类目后补码 = 那个类目在补码之前**永远拒绝所有人**，
-- 而商家看到的是「你还没有资质授权」，去哪申请没人说得出（V174 刚修过这个形状）。
INSERT INTO sys_auth_code
(code, name, required_qualification, sort, enabled, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FRESH_MEAT',      '肉禽蛋',     '食品经营许可证',           15, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 宠物**食品**要饲料经营备案；宠物玩具、猫砂不要 —— 所以门槛挂在「宠物食品」
-- 这个二级上，不挂在「宠物用品」上，否则卖猫爬架的也被拦住
('PET_FOOD',        '宠物食品',   '饲料和饲料添加剂经营备案', 70, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 婴幼儿配方乳粉是单独一档备案，与预包装食品不是一张证
('INFANT_FORMULA',  '婴幼儿食品', '婴幼儿配方乳粉销售备案',   28, 1, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- 乳制品的码 V5 就建好了，却一直 enabled=0 —— 补类目时正好用上
UPDATE sys_auth_code SET enabled = 1 WHERE code = 'FRESH_DAIRY' AND enabled = 0;

-- ── 2. 食品生鲜补两件：肉禽蛋、乳制品 ─────────────────────────
--
-- 生鲜三大件此前只有菜和果，缺了客单最高的那一件。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT170', 'CAT100', 2, '肉禽蛋', 'Meat & Eggs', NULL, 70, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_MEAT', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT180', 'CAT100', 2, '乳制品', 'Dairy', NULL, 80, 'FRESH', NULL,
 '["食品经营许可证"]', 'FRESH_DAIRY', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 3. 新一级「食品饮料」 ─────────────────────────────────────
--
-- 粮油调味/零食/饮料/烘焙都是**标品**（STANDARD → NORMAL），挂在「食品生鲜」下
-- 会让它们跟着生鲜的心智走；而食品生鲜那一级的二级槽位也已经排满。
--
-- ⚠️ 这四类都不是生鲜：形态写成 FRESH 不报错，只会让一袋米在建品页上要求填截单时间。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT700', NULL,     1, '食品饮料',   'Food & Drinks',     NULL, 70, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT710', 'CAT700', 2, '粮油调味',   'Grain & Seasoning', NULL, 10, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT720', 'CAT700', 2, '休闲零食',   'Snacks',            NULL, 20, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT730', 'CAT700', 2, '饮料冲调',   'Drinks',            NULL, 30, 'STANDARD', NULL,
 '["仅销售预包装食品备案"]', 'PACKAGED_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT740', 'CAT700', 2, '烘焙面点',   'Bakery',            NULL, 40, 'STANDARD', NULL,
 '["食品经营许可证"]', 'FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT750', 'CAT700', 2, '婴幼儿食品', 'Baby Food',         NULL, 50, 'STANDARD', NULL,
 '["婴幼儿配方乳粉销售备案"]', 'INFANT_FORMULA', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 4. 日用百货补三件 ─────────────────────────────────────────
--
-- 母婴用品**不挂门槛**：纸尿裤、湿巾、洗护没有前置许可 ——
-- 奶粉那一档单独放在「婴幼儿食品」下（见上），两者分开是有意的。
-- 宠物同理：食品要备案，用品不要。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT250', 'CAT200', 2, '母婴用品', 'Baby Care',   NULL, 50, 'STANDARD', NULL, NULL, NULL,        'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT260', 'CAT200', 2, '宠物用品', 'Pet Supplies', NULL, 60, 'STANDARD', NULL, NULL, NULL,       'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT270', 'CAT200', 2, '宠物食品', 'Pet Food',    NULL, 70, 'STANDARD', NULL,
 '["饲料和饲料添加剂经营备案"]', 'PET_FOOD', 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT280', 'CAT200', 2, '文具玩具', 'Stationery & Toys', NULL, 80, 'STANDARD', NULL, NULL, NULL,  'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 5. 新一级「鲜花绿植」 ─────────────────────────────────────
--
-- 独立一级而不是塞进日用百货：它有自己的心智与节日峰值，
-- 埋在纸品清洁旁边的结果是买花的人找不到、卖花的店懒得开。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT800', NULL,     1, '鲜花绿植', 'Flowers & Plants', NULL, 80, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT810', 'CAT800', 2, '鲜花',     'Fresh Flowers',    NULL, 10, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT820', 'CAT800', 2, '绿植盆栽', 'Potted Plants',    NULL, 20, 'STANDARD', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 6. 生活服务补四件 ─────────────────────────────────────────
--
-- 社区里最高频的四种上门/到店服务。都不挂门槛：这几类没有前置许可，
-- 挂一个要不来的证等于把类目关死。
INSERT INTO prd_category
(category_no, parent_no, level, name, name_en, icon, sort, template,
 attr_template, qualification_required, required_code, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('CAT330', 'CAT300', 2, '洗衣洗鞋', 'Laundry',      NULL, 30, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT340', 'CAT300', 2, '美容美发', 'Beauty & Hair', NULL, 40, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT350', 'CAT300', 2, '宠物洗护', 'Pet Grooming', NULL, 50, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('CAT360', 'CAT300', 2, '跑腿代办', 'Errands',      NULL, 60, 'SERVICE', NULL, NULL, NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

-- ── 7. 卡券与虚拟商品：**先关，不是先补** ─────────────────────
--
-- 这两类现在是「有类目、没链路」：后端没有任何按 CARD / VIRTUAL 分支的代码，
-- 建品页给它们的履约候选是实物那四种（自提/快递），`INSTANT` 后端未实现，
-- 规格模板 0 个。商家**选得到、卖不了** —— 与 V174 刚修掉的是同一个形状。
--
-- 归档不删：已经建出来的商品照常存在（`categoryTypeOf` 看的是 template 不是 status），
-- 核销/发码链路做完之后，把这四行 status 改回 ACTIVE 即可重新开放。
UPDATE prd_category SET status = 'ARCHIVED'
WHERE category_no IN ('CAT400', 'CAT500', 'CAT510', 'CAT520') AND status = 'ACTIVE';
