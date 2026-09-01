# TDD-支付域 · 数据库设计（目标态）

> 状态：**方案待评审** · 创建 2026-09-01
> 上游：[TDD-支付域 · 领域模型（需求层）](./TDD-支付域-领域模型（需求层）.md) ·
> [TDD-支付域 · 需求代码双向对齐](./TDD-支付域-需求代码双向对齐.md)
> 下游：API → Controller/Service/Mapper
> 区别于：[TDD-支付域 · 数据库设计](./TDD-支付域-数据库设计.md)（较早的一份，只覆盖账期批次与欠款）

---

## L1 · 这份怎么写的

**从领域对象出发，不从现有表出发。**

顺序是：对象 → 它要存什么 → 现有表能不能承担 → 不能承担的才是新表/新列。
反过来（先看现有表再补）会重复现状的形状，
而做这一整轮设计的全部意义就是不让它那样。

**产出三样**：目标 schema（§二）· 与现有库的字段级差集（§三）· 迁移顺序（§四）。

---

## L2 · 一、总览：29 个对象要几张表

| 结论 | 数量 | 说明 |
|---|---:|---|
| 现有表**原样承担** | 19 | 不动 |
| 现有表**加列** | 4 | 结算单币种 · 批次币种 · 主体市场 · 费率市场 |
| **新表** | 4 | 市场（从 JSON 升格）· 支付方式 · 方式×市场 · 通道×市场 |
| 不需要表 | 2 | 币种（是市场的属性）、收款单（与流水合一） |

**新表只有四张，其中一张还是把现有 JSON 配置升格。**
这是个好消息，也是个提醒：
现有的表结构基本承载得住需求层的模型 ——
<b>缺的主要不是表，是「写进去」和「核验」这两件事</b>（见对齐 §三·3、§四）。

---

## L2 · 二、目标 schema

### 2.1 市场 —— **把现有的 JSON 配置升格为表**

**这不是新建。**第二次对齐时发现：市场今天已经存在，
以一段 JSON 存在平台设置里，结构与下面这张表几乎一样：

```json
[{"code":"CN","name":"中国大陆","currency":"CNY",
  "timezone":"Asia/Shanghai","rate":1.0,"enabled":true}]
```

而且 `market` 这个列**已经在五张表上用着**（商品 SKU、门店价、
积分账户、积分流水、积分池）—— 商品域按市场定价、积分域按市场记账，
<b>市场概念早就贯穿了两个域，只是没有主数据</b>。

**那为什么还要建表**：不是「没有地方存」，而是 JSON <b>无法被引用与约束</b>。
今天没有任何东西保证某张表里的 `market` 值真的在那段 JSON 里 ——
写错一个市场码，积分会记进一个不存在的市场，而不报错。

**命名一律用 `market`**，主表与引用列都是。

先给主表造了个 `market_code`，然后发现仓库里<b>已经有现成的模式</b>：
通道主数据表的主键列就叫 `pay_channel`（不是 `channel_code`），
而业务表引用它时也叫 `pay_channel`。市场照抄这个模式。

制造第二套命名比不统一更糟，而在一份新设计里同时用
`market` 和 `market_code` 是第三套 —— 立了规则又自己破一次。

```sql
CREATE TABLE sys_market (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    market        VARCHAR(8)  NOT NULL COMMENT '市场码：CN / TW / HK / AE / SA / SG',
    name          VARCHAR(64) NOT NULL COMMENT '显示名，多语言另走词条',
    currency      VARCHAR(8)  NOT NULL COMMENT '记账币种。**一个市场一种**，改它等于换账本',
    currency_scale TINYINT    NOT NULL DEFAULT 2 COMMENT '小数位。日元 0、科威特第纳尔 3 —— 写死 2 会算错',
    time_zone     VARCHAR(48) NOT NULL COMMENT '账期与对账按它切天',
    display_rate  DECIMAL(18,6) NOT NULL DEFAULT 1 COMMENT '相对 CNY 的展示汇率。**只用于折算显示，绝不参与结算** —— 参与的话汇率一动历史账就变',
    enabled       TINYINT     NOT NULL DEFAULT 0 COMMENT '默认关。开一个市场是运营动作，不是上线动作',
    sort_no       INT         NOT NULL DEFAULT 0,
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    /* 审计六列同其他表 */
    PRIMARY KEY (id),
    UNIQUE KEY uk_market (tenant_no, market)
) COMMENT='市场。归 pay —— 币种与账期口径是资金域的知识';
```

**三条设计决定**：

- **币种是市场的属性，不是独立的表。**币种没有自己的生命周期与动作，
  它只是「这个市场用什么记账」。单开一张币种表会引出
  「币种表里有而没有市场用它」这类无意义的状态。
- **`currency_scale` 必须有。**日元是 0 位小数、科威特第纳尔是 3 位。
  全系统按「分」存整数是对的，但<b>「一元等于多少分」不是常量</b> ——
  写死 2 会让日元的金额差 100 倍，而它不会报错。
- **默认 `enabled = 0`。**开市场是运营动作。默认开的话，
  上线当天所有市场一起可见，而它们的通道都还没配。

### 2.2 新表 · 支付方式

用户看到并点的那个按钮。**挂在通道下**。

```sql
CREATE TABLE sys_pay_method (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    method_code  VARCHAR(32) NOT NULL COMMENT 'JSAPI / MINIPROGRAM / H5 / CARD / APPLE_PAY …',
    pay_channel  VARCHAR(32) NOT NULL COMMENT '钱从哪家机构走。**方式与通道多对一**',
    name         VARCHAR(64) NOT NULL,
    icon         VARCHAR(255) DEFAULT NULL,
    sort_no      INT         NOT NULL DEFAULT 0 COMMENT '端上的排列顺序。运营能调，不用改代码',
    enabled      TINYINT     NOT NULL DEFAULT 0,
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_method (tenant_no, method_code)
) COMMENT='支付方式。与通道分开：同一个「刷卡」按钮，不同市场后面可能是完全不同的机构';
```

### 2.3 新表 · 支付方式 × 市场

```sql
CREATE TABLE sys_pay_method_market (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    method_code VARCHAR(32) NOT NULL,
    market      VARCHAR(8)  NOT NULL,
    enabled     TINYINT     NOT NULL DEFAULT 0,
    sort_no     INT         NOT NULL DEFAULT 0 COMMENT '同一种方式在不同市场的排序可以不同',
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_method_market (tenant_no, method_code, market)
) COMMENT='哪种支付方式在哪个市场可用';
```

> **为什么是三张表而不是在支付方式上放一个市场列表字段**：
> 那正是今天的做法（通道上放一个字符串列表），而它的代价就是
> 「不能按市场启停、不能排序」。<b>把它换成另一个字符串列表，
> 等于把同一个问题换个地方再犯一次。</b>

### 2.4 加列 · 结算侧补币种

```sql
ALTER TABLE stl_bill         ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'CNY';
ALTER TABLE stl_settle_batch ADD COLUMN currency VARCHAR(8) NOT NULL DEFAULT 'CNY';
```

**关于批次的唯一键** —— 这里先写错过一次，更正：

我原本写「今天的键是 (主体 × 通道 × 应结日)，目标是加上币种」。
查了实际 DDL：**批次表上唯一的唯一键是批次号本身**。
(主体 × 通道 × 应结日) 是<b>代码里查询用的组合，不是数据库约束</b>。

所以「改唯一键」这个风险不存在。但反过来看到的是另一件事：
<b>今天没有任何数据库约束保证同一 (主体 × 通道 × 应结日) 只有一个批次</b> ——
靠的是代码里「先查再建」那一段。并发下它会开出两个批次，
而两个批次各自持有一部分结算单，放款时谁也不知道另一个的存在。

**建议**：加币种的同时补一个业务唯一键
`(tenant_no, entity_no, pay_channel, currency, period_from)`。
这不是多区域的需求，是<b>今天就缺的一道约束</b>。

> 回填 `CNY` 是安全的：现有数据确实全是人民币。
> 但<b>回填之后要立刻让写入路径显式赋值</b> ——
> 靠默认值活着的列，在第二个币种出现时会静默地全部写成人民币。

### 2.5 加列 · 主体挂市场

```sql
ALTER TABLE mch_entity ADD COLUMN market VARCHAR(8) NOT NULL DEFAULT 'CN';
```

**挂在主体而不是门店**：一个主体在两个市场经营的话要开两个主体 ——
这与「收款进件按主体」是同一条口径。

### 2.6 加列 · 通道挂市场（把字符串列表换成关联）

现有的通道表上有一个市场列表字段（逗号分隔）。
**保留它到迁移完成，然后删。**新的关联表：

```sql
CREATE TABLE sys_pay_channel_market (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    pay_channel VARCHAR(32) NOT NULL,
    market      VARCHAR(8)  NOT NULL,
    enabled     TINYINT     NOT NULL DEFAULT 0,
    tenant_no   VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_market (tenant_no, pay_channel, market)
) COMMENT='通道在哪些市场可用';
```

> 这一张与上面的「方式 × 市场」都是**关联表，不是领域对象** ——
> §一 的四张里包含它们。<b>对象数与表数本来就不相等</b>，
> 两处数字对不上时以 §三 的差集表为准，那张是逐条列的。

### 2.7 加列 · 退款流水号回填

售后单上已有 `refund_payment_no` 字段（只声明、从没赋值）。
**不加列，补写入** —— 见对齐 §三·2。

### 2.8 加列 · 费率版本按市场分档

通道费率版本的键今天是 (通道 × 场景 × 法人形态)。
目标补上市场：**沙特的费率不等于泰国的**。

```sql
ALTER TABLE sys_pay_channel_rate ADD COLUMN market VARCHAR(8) NOT NULL DEFAULT 'CN';
```

---

## L2 · 三、与现有库的字段级差集

| 表 | 动作 | 字段 | 风险 |
|---|---|---|---|
| `sys_market` | 新建 **+ 从 JSON 配置迁入** | — | **中**：现有市场配置在平台设置的一段 JSON 里，迁移后读取处要一起改 |
| `sys_pay_method` | 新建 | — | 无 |
| `sys_pay_method_market` | 新建 | — | 无 |
| `sys_pay_channel_market` | 新建 + 从字符串回填 | — | **回填要核对**：拆字符串的结果与原值逐条比 |
| `stl_bill` | 加列 | `currency` | 低。默认 CNY |
| `stl_settle_batch` | 加列 + **补业务唯一键** | `currency` | **中**：不是改现有键（现有只有批次号），是补一道今天就缺的约束 |
| `mch_entity` | 加列 | `market` | 低。**列名跟随现有五张表与 `sys_pay_channel` 的模式** |
| `sys_pay_channel_rate` | 加列 | `market` | 低，但**取费率的查询要跟着改**，否则新列恒不参与匹配 |
| `stl_payment` | 不动 | — | 缺的是写入（四个方向），不是结构 |
| `ord_after_sale` | 不动 | — | 缺的是给 `refund_payment_no` 赋值 |
| `sys_pay_channel` | **迁移后删** `markets` | — | 删列要等读取处全改完 |

**19 张表原样不动。**

### 三·五、三个容易被跳过的配套

1. **加了列要补实体字段。**只改迁移不改实体，那一列永远读出 null，
   而且不报错 —— 这个仓库有 `entity-alignment` 守卫在拦，加列后要跑它。
2. **补唯一键之前要先查现有数据有没有已经冲突的行。**
   `stl_settle_batch` 要补 (主体 × 通道 × 币种 × 期起) 这道键，
   而它<b>今天不存在</b> —— 也就是说库里完全可能已经有重复行。
   理论上不会（代码里先查再建），但<b>「理论上」要用一条 SQL 换成「实际上」</b>，
   否则加约束那一刻迁移直接失败，而失败发生在生产。
3. **加了列要让写入路径显式赋值。**靠默认值活着的列，
   在第二个取值出现时会静默地全部写成默认值。
   `stl_payment.currency` 就是活例子：列存在、默认人民币、
   <b>生产代码里没有任何一处给它赋值</b>。

---

## L3 · 四、迁移顺序

```
第一批（不依赖任何东西，今天就能做）
  ① stl_bill / stl_settle_batch 加 currency，回填 CNY
  ② 补实体字段，跑 entity-alignment 守卫
  ③ 让结算单生成路径显式写 currency
  ④ 所有金额聚合处按 currency 分组
     闸门：造一条 TWD 的单，断言它没有被算进 CNY 的合计

第二批（多区域的地基）
  ⑤ 建 sys_market，种 CN（enabled=1）与其余市场（enabled=0）
  ⑥ mch_entity 加 market，回填 CN
  ⑦ 建 sys_pay_channel_market，从现有字符串列表回填
     闸门：回填结果与原字符串逐条比对，条数与内容都要对
  ⑧ 两处「取可用通道」改为按商家市场筛
     闸门：造一个 TW 商家，断言他看不到只在 CN 的通道

第三批（支付方式升格）
  ⑨ 建 sys_pay_method + sys_pay_method_market，从现有字符串回填
  ⑩ 读取处从「拆字符串」改成「查表」
  ⑪ 删掉通道表上的 markets 与 pay_methods 两个字符串列
     ——【只有当 ⑧ 与 ⑩ 都上线并观察过之后】

第四批（费率分档）
  ⑫ sys_pay_channel_rate 加 market，回填 CN
  ⑬ 取费率的查询带上市场
     闸门：现有费率取值不变（回归）
```

**每一批之间可以停。**第一批做完，单币种系统更正确了；
第二批做完，多市场的通道筛选就通了；第三批之前一切照旧可用。

**第三批的 ⑪ 是唯一不可回退的一步** —— 删列之前要确认
读取处全部改完并观察过一段时间。<b>删列不是收尾动作，是一次独立发布。</b>

---

## L4 · 五、这份设计没有解决的

诚实列出来，免得被当成「设计完了」：

1. **美国与欧洲的资金流模型不适用本设计。**
   卡组织的「授权 → 请款 → 结算」是三段式，还有拒付这个国内没有的对象。
   本设计里的支付流水是「一次资金变动」，
   而三段式下一笔交易会有三个时点、三种可撤销性。<b>需要单独立项。</b>
2. **汇率不在本设计里。**汇率只用于展示折算、不参与结算 ——
   这条一旦松动，历史账会随汇率变动，整套设计的前提就没了。
3. **对账轮次的覆盖范围结构**（对齐 §三·4）留到 API 那一步定，
   因为它的形状取决于运营页面要怎么展示。
4. **`stl_payment` 四个方向的写入**不是 schema 问题，是实现问题，
   不在本文档范围 —— 但它是「退款进入对账范围」的前提，
   排在待办 T3。
