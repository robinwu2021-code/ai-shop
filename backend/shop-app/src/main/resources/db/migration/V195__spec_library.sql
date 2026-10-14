-- 规格库：把「规格」从一张扁平的模板表里拆成四层 —— 规格项 / 规格值 / 类目绑定 / 商家覆盖。
--
-- 为什么要拆：prd_spec_template 一行里挤着四件事（维度是什么、有哪些值、绑给谁、归谁所有），
-- 于是同一个「颜色」在每个类目下各存一份 JSON，值没有身份也没有归一量，商家自存的模板与平台
-- 维度毫无关系。代价是可量化的：线上 378 件商品，spec_groups 里带 optionCode 的 0 件 ——
-- 「模板带 code、code 用来聚合」这条链从上线到现在一次都没在真实数据上成立过。
--
-- prd_spec_template 本版保留：历史商品的 templateNo 要靠它解释（当前引用数为 0，观察一轮后再退役）。

CREATE TABLE prd_spec_dim (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dim_no VARCHAR(64) NOT NULL COMMENT '维度编号 SD_COLOR / SD_WEIGHT',
  code VARCHAR(32) NOT NULL COMMENT '语义码 COLOR / WEIGHT。值编号与 optionCode 都以它为前缀，改码等于换一根轴',
  name VARCHAR(64) NOT NULL COMMENT '颜色 / 重量 / 口径',
  name_i18n TEXT DEFAULT NULL COMMENT 'JSON，缺的语言回落 name（不机翻）',
  value_type VARCHAR(16) NOT NULL DEFAULT 'ENUM' COMMENT 'ENUM 枚举 / QUANT 数值+单位。QUANT 的值必须有 numeric_value',
  unit VARCHAR(16) DEFAULT NULL COMMENT 'QUANT 才有：g / ml / cm / 分钟',
  usage_type VARCHAR(16) NOT NULL DEFAULT 'SALE' COMMENT 'SALE 进 SKU 笛卡尔积 / PROP 只是描述。类目绑定可覆盖 —— 口味在熟食是 SALE，在预包装是 PROP',
  universal TINYINT NOT NULL DEFAULT 0 COMMENT '1=通用。判据是「值的含义是否跨类目一致」，不是「用在几个类目」：锅的黑和手机的黑是同一个黑',
  scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM / MERCHANT。商家自建维度只对自己下发，且不参与跨店聚合 —— 这是它的定义，不是缺陷',
  entity_no VARCHAR(64) DEFAULT NULL COMMENT 'scope=MERCHANT 时的归属商家',
  sort INT NOT NULL DEFAULT 100,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED。归档不是删除：历史商品还要靠它解释自己的 code',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spec_dim_no (dim_no),
  UNIQUE KEY uk_spec_dim_code (tenant_no, code, scope, entity_no),
  KEY idx_spec_dim_scope (scope, entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规格项：一个维度一行，通用维度全站只有这一份';

CREATE TABLE prd_spec_value (
  id BIGINT NOT NULL AUTO_INCREMENT,
  value_no VARCHAR(64) NOT NULL COMMENT '值编号 SV_WEIGHT_W500G。SKU 快照记的就是它',
  dim_no VARCHAR(64) NOT NULL,
  code VARCHAR(32) NOT NULL COMMENT '维度内唯一的码 W500G / CLRBLACK。对外仍叫 optionCode',
  label VARCHAR(64) NOT NULL COMMENT '展示文案，可改 —— 改了不影响已建商品（商品侧存的是快照）',
  label_i18n TEXT DEFAULT NULL,
  numeric_value DECIMAL(14,4) DEFAULT NULL COMMENT '归一量：500g / 半斤 / 0.5kg 都是 500。没有它，「按规格排序」会把 1kg 排在 500g 前面，「同规格比价」根本无从谈起',
  numeric_unit VARCHAR(16) DEFAULT NULL COMMENT '与维度 unit 同口径，冗余在这里是为了不连表就能比',
  aliases TEXT DEFAULT NULL COMMENT 'JSON 数组：["1斤","一斤"]。识别、搜索与将来的自动归一用',
  scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM' COMMENT '商家在平台维度下加的自有值也是 MERCHANT —— 它仍挂在同一根轴上，因此照样可比',
  entity_no VARCHAR(64) DEFAULT NULL,
  sort INT NOT NULL DEFAULT 100,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_spec_value_no (value_no),
  UNIQUE KEY uk_spec_value_code (tenant_no, dim_no, code, scope, entity_no),
  KEY idx_spec_value_dim (dim_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规格值：值有身份，才谈得上聚合、排序与比价';

CREATE TABLE prd_category_spec (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category_no VARCHAR(64) NOT NULL,
  dim_no VARCHAR(64) NOT NULL,
  usage_type VARCHAR(16) DEFAULT NULL COMMENT '覆盖维度上的默认用途；空 = 跟维度走',
  is_primary TINYINT NOT NULL DEFAULT 0 COMMENT '主维度：建品选完类目自动预填的就是它。每个类目至多一条（守卫测住）',
  required TINYINT NOT NULL DEFAULT 0 COMMENT '预留：这一类目必须给出该维度。本版不校验',
  sort INT NOT NULL DEFAULT 100,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_spec (tenant_no, category_no, dim_no),
  KEY idx_cat_spec_cat (category_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目 × 规格项：这一类目用哪些维度、谁是主维度';

CREATE TABLE prd_category_spec_value (
  id BIGINT NOT NULL AUTO_INCREMENT,
  category_no VARCHAR(64) NOT NULL,
  dim_no VARCHAR(64) NOT NULL,
  value_no VARCHAR(64) NOT NULL,
  label_override VARCHAR(64) DEFAULT NULL COMMENT '同一个值在这一类目下换个说法：500g 在蔬菜下叫「约1斤」，而归一值仍是 500 —— 换说法不换轴',
  sort INT NOT NULL DEFAULT 100,
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cat_spec_value (tenant_no, category_no, dim_no, value_no),
  KEY idx_cat_spec_value (category_no, dim_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目下的取值子集：没有行 = 该维度全部值都能选';

CREATE TABLE prd_merchant_spec (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entity_no VARCHAR(64) NOT NULL,
  dim_no VARCHAR(64) NOT NULL,
  sort INT NOT NULL DEFAULT 100,
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mch_spec (tenant_no, entity_no, dim_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家常用维度：引用，不是副本';

CREATE TABLE prd_merchant_spec_value (
  id BIGINT NOT NULL AUTO_INCREMENT,
  entity_no VARCHAR(64) NOT NULL,
  dim_no VARCHAR(64) NOT NULL,
  value_no VARCHAR(64) NOT NULL,
  sort INT NOT NULL DEFAULT 100,
  tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
  created_at DATETIME NOT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL,
  updated_by VARCHAR(64) DEFAULT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mch_spec_value (tenant_no, entity_no, dim_no, value_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家常用取值：他上次挑过的那几档，下次建品排在前面';

-- SKU 侧的快照。**是快照不是外键**：值改名（黑色 → 曜石黑）不能改写历史订单里那件货当时的样子，
-- 所以文案照旧留在 option_values 里；这一列存值编号，供聚合与比价用。
-- 商家手打、没落到任何值上的那一格为 null —— 于是「有多少规格没归一」第一次变成可查的。
ALTER TABLE prd_sku ADD COLUMN option_value_nos VARCHAR(512) DEFAULT NULL COMMENT 'JSON 数组，与 option_values 一一对应；未归一的位置为 null';
