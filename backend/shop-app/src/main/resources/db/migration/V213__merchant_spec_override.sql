-- 商家对平台规格的覆盖：**用哪几个、什么顺序、在我店里叫什么**。
--
-- 三件商家一直想做而做不了的事，共用这一张表：
--   ① 本店只用其中几个（平台给蔬菜配了重量/包装/等级，我只用前两个）
--   ② 调顺序（我这儿「包装」比「等级」重要）
--   ③ 换个叫法（「重量」在我店里就叫「分量」）
--
-- **③ 改的是显示名，不是平台的规格。**这条边界是这张表存在的前提：
-- dim_no 一个字不变，所以三家店的同一个规格照样聚得到一起。
-- 与「我的类目」的 display_name 是同一个模式（那里已经证明过这条边界站得住）。
--
-- 商家自己输入的**档位**也不落这张表：它走 prd_spec_value（scope=MERCHANT），
-- 挂在同一个平台维度下并带归一量（「750g」→ 750+g），所以与平台的 500g 同轴、
-- 照样能比价。存进这张表的话它就只是一个字符串，谁也比不了。
--
-- **挂 merchant_no 而不是 store_no。**商品是商家级的（prd_goods.entity_no），
-- 店级的只有库存与价格。做成店级的话，同一件商品在 A 店建和在 B 店建会看到
-- 不同的规格，而商品只有一份 —— 那说不通。货架（mch_store_category）是店级的，
-- 因为它回答的是「这家店摆什么」，与「这个商家怎么描述商品」不是一回事。
--
-- **稀疏表：没有行 = 完全跟平台走。**不预先给每个商家灌一份全量副本，
-- 这样运营给某个类目加了新维度，所有没动过手的商家自动获得它；
-- 而灌了副本的话，新维度永远到不了他们那儿，且没有任何一处会提示。
CREATE TABLE prd_merchant_spec_override (
  id BIGINT NOT NULL AUTO_INCREMENT,
  merchant_no VARCHAR(64) NOT NULL,
  category_no VARCHAR(64) NOT NULL,
  dim_no VARCHAR(64) NOT NULL,
  -- 空串 = 这一行覆盖的是**维度**；非空 = 覆盖维度下的某个**取值**。
  -- 用空串不用 NULL：唯一键里的 NULL 在 MySQL 里互不相等，同一个维度能插进无数行
  value_no VARCHAR(64) NOT NULL DEFAULT '',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0 = 本店不用它。停用维度会连带它下面的取值一起不出现',
  sort INT DEFAULT NULL COMMENT '本店顺序，小的在前。NULL = 跟平台的顺序',
  label_override VARCHAR(64) DEFAULT NULL COMMENT '本店叫法。空 = 用平台的',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mch_spec_override (tenant_no, merchant_no, category_no, dim_no, value_no),
  KEY idx_mch_spec_override_cat (merchant_no, category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家对平台规格的覆盖（用哪几个/什么顺序/叫什么）';
