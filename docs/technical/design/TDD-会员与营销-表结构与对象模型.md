# TDD-会员与营销 · 表结构与对象模型

状态：草稿（待确认）
关联：[会员体系与活动联动 · 需求](../../requirements/会员体系与活动联动-需求.md) ·
[活动体系 · 产品方案](../../requirements/活动体系-需求.md) ·
[TDD-券与活动模型](./TDD-券与活动模型.md)（抽象与取舍）·
[TDD-会员与营销-数据库与UI](./TDD-会员与营销-数据库与UI.md)（UI 部分仍以那一份为准）
创建日期：2026-08-24

---

## 0. 前提与范围

**这一份走「新建表」路线**（2026-08-24 拍板）：营销侧不再往 `mkt_coupon` / `mkt_campaign`
上打补丁，而是新建一套 `pmt_*` 表，按统一模型一次做对；旧表在切换完成后退场。

**代价要说在前面**：重建表意味着**券与活动的服务层、三端页面、算价接入点都要跟着重写**，
不是只写几段 DDL。DDL 是这份文档的产物，重写是接下来的工作量（见 §5 退场路径）。
库里现在是测试数据，可以删了重来 —— 这也是现在做比以后做便宜得多的原因。

命名前缀：会员 `mbr_`（member）、营销 `pmt_`（promotion）。
用新前缀而不是沿用 `mkt_`，是为了**新旧可以并存一段**：先双写/只读切换，再删旧表。

---

## 1. 表与表之间的关系

**共 19 张**：user 域 2（**person 平台人档** + person_merge_log）、会员 9（setting / member / member_store /
member_source / tag / member_tag / tag_merge_log / segment / reach_log）、
营销 8（activity / activity_audience / activity_goods / coupon / coupon_scope /
user_coupon / coupon_issue / apply）。

**层次是三层**：`usr_person`（平台身份：这个人）→ `mbr_member`（他与某家主体的会员关系）
→ `mbr_member_store`（他在这家主体某家门店的往来）。

### 1.1 全景

```mermaid
erDiagram
    usr_person      ||--o{ mbr_member        : "一个人在多家商家各有一条会员"
    usr_person      |o--|| usr_account        : "注册后绑定（可空 1:1）"
    mbr_setting     ||--|| MERCHANT       : "每个主体一行：会员按主体还是按门店"
    mbr_member      ||--o{ mbr_member_store  : "他在每家店的往来"
    mbr_member      ||--o{ mbr_member_source : "每一次来源留一行"
    mbr_member      ||--o{ mbr_member_tag    : "打了哪些标签"
    mbr_tag         ||--o{ mbr_member_tag    : "标签字典（tag_no 不可变）"
    mbr_tag         ||--o{ mbr_tag_merge_log : "合并留痕"
    mbr_member      ||--o{ mbr_reach_log     : "被触达的记录"
    mbr_segment     ||--o{ mbr_reach_log     : "这次发给哪一群人"

    pmt_activity    ||--o{ pmt_activity_audience : "给谁"
    pmt_activity    ||--o{ pmt_activity_goods   : "作用在哪些商品"
    pmt_activity    |o--o| pmt_coupon           : "发券型活动引用一张券"
    pmt_coupon      ||--o{ pmt_user_coupon      : "发出去的每一张"
    pmt_coupon      ||--o{ pmt_coupon_issue     : "每一批发放"
    mbr_segment     ||--o{ pmt_coupon_issue     : "发给哪一群人"
    pmt_user_coupon ||--o{ pmt_apply            : "被用掉的每一次（次卡多行）"
    pmt_apply       }o--|| pmt_activity         : "这一单命中的活动"
    pmt_apply       }o--|| pmt_user_coupon      : "这一单用掉的券"
```

### 1.2 一句话说明每张表

| 表 | 一句话 | 为什么必须独立 |
|---|---|---|
| `usr_person` | **平台人档：这个自然人**，以已验证的手机号为准（不要求注册过账号）。手机号只在这里存一份密文 | 会员是"人 × 商家"的关系，先得有"人"；线索与正式会员因此不再需要事后合并 |
| `usr_person_merge_log` | 谁的人档并进了谁、影响多少条会员关系 | **常年应该是空的**：会员必须有手机号之后，只剩换号撞档与人工纠错两种；不空就说明别处错了 |
| `mbr_setting` | 这个主体的会员按主体经营还是按门店经营 | 开关要能随时切；放在主体表上会与商家资料的读写混在一起 |
| `mbr_member` | **一个人 × 一家主体**的关系（主表） | 名单、分层、可触达状态的唯一真源 |
| `mbr_member_store` | 他在**某一家门店**的往来与分层 | 十公里外那家店要按自己的口径看人；两级数据一直都算，开关只决定展示哪级 |
| `mbr_member_source` | **每一次**来源：哪家店、哪条链接、谁发的、谁录的、哪场活动 | 「李姐拉来多少人」是按列聚合的问题，塞 JSON 只能全表扫 |
| `mbr_tag` | 标签字典：`tag_no` 不可变、`name` 可改 | 改名不动关系行；合并要留下 `merged_into`。**不存人数**，要用时 COUNT |
| `mbr_member_tag` | 谁被打了哪个标签 | 按标签筛人、按标签统计人数 |
| `mbr_tag_merge_log` | 谁在什么时候把哪个标签并进了哪个 | 合并不可逆，要能回答「这批人的标签怎么变了」 |
| `mbr_segment` | 一组筛选条件 = 一个人群，可命名保存 | 发券、活动受众、触达都要引用「同一群人」，条件散在各处会对不上 |
| `mbr_reach_log` | 谁在什么时候被发过什么 | 频次闸查它，效果也算它 |
| `pmt_activity` | 活动 = 触发 × 优惠 × 排期 × 限量 × 门店 | 统一模型，四类玩法只是取值组合 |
| `pmt_activity_audience` | 这场活动给谁 | 没有任何一行 = 给所有人 |
| `pmt_activity_goods` | 这场活动作用在哪些商品 | 用表不用 TEXT：要反查「这个商品在哪些活动里」（冲突提示） |
| `pmt_coupon` | 券模板 = 权益 × 门槛 × 范围 × 有效期 × 发放 × 核销 × 次数 | 券是资产的模具，与活动解耦 |
| `pmt_user_coupon` | 发到某个人手上的**那一张** | 有自己的有效期与状态，活动结束不该动它 |
| `pmt_coupon_issue` | 一批发放（发给哪个人群、发了多少、谁发的） | 定向发券要能回看与追责 |
| `pmt_apply` | **优惠发生记录**：一单命中了什么、一张券被用了第几次 | 活动效果、券对账、来源归因三件事都读它；线上抵扣与线下核销合并在这一张，券的钱只记一处 |

### 1.3 三条贯穿的规则

1. **关系表只存号，不存文本**（标签、商品、门店都是）：文本会变，号不变。
2. **凡是"给谁"，都指向 `mbr_segment` 或标签号**，不各自存一份 JSON 条件 ——
   否则同一群人在发券、活动、触达三处会算出三个数。
3. **凡是"发生过什么"，都单独留一行**（`mbr_member_source` / `mbr_reach_log` /
   `pmt_apply`）：这些是事实，不是状态；用状态字段覆盖会丢掉历史。

### 1.4 会员表是哪一张：`mbr_member`

**`mbr_member` 就是会员表**，一行 = 一个人在一家主体的会员身份。
`mbr_member_store` **不是第二张会员表**，它是同一份事实的门店粒度汇总，
里面**没有任何身份字段**（没有 status / source / phone / 标签 / 退订状态）——
不会出现「两边身份不一致」这种问题。

即便如此，「一次入会写两行」仍然值得压到最小。三条规矩：

1. **单店主体不写门店行。** 绝大多数商家只有一家店，门店行等于主表的复制。
   只有 `mch_store` 数 > 1 的主体才写 `mbr_member_store`；读的时候没有门店行就回落主表。
2. **写入只有一个入口**（`MemberService.applyOrder`），同一事务里更新两级指标 ——
   不是两个地方各自维护。
3. **夜间全量重算兜底**（幂等）：两级指标的**唯一真源是订单**（`ord_sub_order`），
   两张表都只是派生缓存。对不上就以订单为准重算，不需要人工对账。

### 1.5 哪些冗余保留、哪些去掉

「不要双写」这条规矩要分清三种东西，它们看起来都是"存了两份"：

| 类型 | 判据 | 处理 |
|---|---|---|
| **并发计数器** | 用来做原子扣减、防超发 | **必须保留**：`pmt_coupon.received_count`、`pmt_activity.quota_used` / `budget_used_minor`、`pmt_user_coupon.times_used`。它们不是缓存，是并发控制的手段 —— 用 COUNT 代替就没法在一条 UPDATE 里判「还有没有」 |
| **派生缓存** | 能从真源算出来，只是为了查得快 | **能去就去，去不掉的标注真源 + 重算任务**：会员两级指标（真源=订单，夜间重算）保留；`mbr_tag.usage_count` **去掉**，改查询时 COUNT（标签总量小，几十行，COUNT 比维护一致性便宜） |
| **事实快照** | 记录"当时是什么样"，本来就该与现状不同 | **保留且不可改**：`mbr_member_source`（入会那一刻谁带来的 —— 归因表 `mkt_attribution` 有滚动窗口，过了窗口就查不到，快照必须自己留一份）、`pmt_coupon_issue.rule_snapshot`（发放当时的人群条件） |

**本次因此砍掉一张表**：原设计里的 `pmt_redeem_log` 与 `pmt_apply` 有实打实的重叠 ——
券在下单抵扣时两边都要写一行。合并成 `pmt_apply` 一张：券的每一次使用就是它的一行，
线上带 `order_no`、线下带 `store_no` + `operator_no`，次卡用 N 次就是 N 行。
**券的钱只在一处记**，结算对账不必两表相加。

---

## 2. 会员域逐表

### 2.0 `usr_person` —— 先有平台身份，再谈会员（user 域）

**会员是"某个自然人与某家商家的关系"，所以必须先有"这个自然人"。**

平台今天有两张身份相关的表，都不够用：

| 表 | 是什么 | 为什么不够 |
|---|---|---|
| `usr_account` | 账号（昵称/头像/openid/手机号） | **要注册才有**。商家录入的手机号还没有账号 |
| `usr_identity` | 登录身份映射（PHONE/OPENID/… → user_no） | `user_no` 非空，同样要先有账号 |

所以在 user 域新增一张**人档**：它表示"这个人"，不要求他注册过。

```sql
CREATE TABLE IF NOT EXISTS usr_person
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    person_no VARCHAR(64) NOT NULL COMMENT '平台唯一身份。所有域引用它，而不是各存一份手机号',
    phone_hash VARCHAR(64) NOT NULL COMMENT '手机号哈希，用来匹配同一个人。**不可逆**。人档以手机号为准 —— 没有手机号就没有人档',
    phone_enc VARCHAR(255) DEFAULT NULL COMMENT '手机号密文，只有平台能解。商家侧永远只拿得到后四位',
    user_no VARCHAR(64) DEFAULT NULL COMMENT '他注册之后绑定的账号。没注册就是空 —— 人先于账号存在',
    merged_into VARCHAR(64) DEFAULT NULL COMMENT '换号/重复人档合并后指向的目标 person_no，保留不删',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MERGED',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_person_no (person_no),
    UNIQUE KEY uk_person_phone (tenant_no, phone_hash),
    UNIQUE KEY uk_person_user (tenant_no, user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台人档：这个自然人，以已验证的手机号为准';
```

**这张表把三个麻烦一次解决**：

1. **线索与正式会员不再需要"合并两行"**。之前 `mbr_member` 上挂两个唯一键
   （`user_no` 一个、`phone` 一个），同一个人可能先被商家录入、后又自己下单，
   变成两行、事后要合并。现在一开始就是同一个 `person_no`，**从头到尾一行**。
2. **一个人被三家商家录入 = 一个人档、三条会员关系**。他注册那天，
   只回填一次 `user_no`，三家的会员同时转正 —— 而不是三次认领。
3. **手机号只存一份**（还是密文）。`mbr_member` 上不再有 `phone` 列 ——
   商家库里散着一堆手机号，本身就是最容易出事的那种数据。

#### 一把锚：手机号。**没有手机号就不是会员**

2026-08-24 拍板：**会员必须有已验证的手机号**。这一条不是为了严格，是为了**把合并的代价降到最低**。

对比一下两种规则下，同一段真实经历会发生什么：

| | 允许无手机号入会（旧） | 会员必须有手机号（现在） |
|---|---|---|
| 商家先录了他的号 | 建线索人档 A | 建线索人档 A |
| 他用微信登录（没授权号） | 建人档 B，**并入会** | 只有账号，**不入会**、不建人档 |
| 他后来补了手机号 | A 与 B 撞车：要合并两份人档，**还要合并两边的会员关系、标签、备注、指标** | 找到 A，把账号绑上去。**没有任何会员关系需要合并** |

代价的差别就在最后一行：前者每一次补号都可能触发一次跨商家的会员合并（他可能是五家店的会员），
后者只是给一份人档补一个 `user_no`。

**合并因此从"常规路径"降级为"罕见异常"**：只剩换号撞档与人工纠错两种，
`usr_person_merge_log` 保留，但它应该常年是空的 —— 如果不空，说明有别的地方错了。

#### 代价转移到哪儿去了：微信登录没授权手机号的人

他不是会员。这件事要在三处交代清楚，否则商家会以为数据丢了：

| 场景 | 处理 |
|---|---|
| C 端点「加入会员」 | 弹一次手机号授权（小程序里是一次点击）。拒绝就不入会，可以继续逛 |
| **他下单了**（自提单可以没有收货人） | **单照下，不阻塞交易** —— 交易永远优先于会员。但不入会 |
| 商家的会员页 | 顶部一行：「本月有 3 位买家未绑手机号，未计入会员」。**把差额说出来**，别让他自己去猜订单数与会员数为什么对不上 |

> **不阻塞交易**这条是硬的：为了一个会员关系挡住一笔已经要付钱的单，
> 换来的是商家损失一单、平台损失一次履约 —— 而会员关系明天补上也不迟。

#### 绑号：三种情况，两种是一步到位

```
补号 / 首次用手机号登录 ──► 按 phone_hash 查人档
     │
     ├─ A 查不到 ──────────► 建人档 + 绑账号。一步到位
     │
     ├─ B 查到、且**没绑过账号**（商家早就录过他，或他曾经用手机号下过单）
     │     └─► 直接绑账号。**不需要合并任何会员关系** —— 那些关系本来就挂在这份人档上，
     │         只把它们的 status 从 LEAD 转 ACTIVE
     │
     └─ C 查到、且**绑着另一个账号**
           └─► 拒绝自动绑定，提示「该手机号已绑定其它账号」，走人工流程
```

B 就是"认领"，它现在只是一次 UPDATE。这正是要求手机号换来的东西。

**C 必须拒绝**，这是安全边界：允许自动合并等于「知道你手机号就能把你的账号并过来」。

**换号**：旧号解绑、新号按 A/B/C 判。新号若已有人档且没绑账号（B），
两份人档下都可能挂着会员关系 —— **这是唯一还需要真合并的场景**，走 `usr_person_merge_log`。
罕见，但要能做。

```sql
CREATE TABLE IF NOT EXISTS usr_person_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    from_person_no VARCHAR(64) NOT NULL,
    to_person_no VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL COMMENT 'BIND_PHONE 补号撞上线索档 / CHANGE_PHONE 换号 / OPS 人工',
    affected_members INT(11) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '人工合并时是谁',
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_person_merge_from (from_person_no),
    KEY idx_person_merge_to (to_person_no, merged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='人档合并留痕：合并不可逆';
```

### 2.1 `mbr_setting` —— 主体的会员经营口径

```sql
CREATE TABLE IF NOT EXISTS mbr_setting
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    member_scope VARCHAR(16) NOT NULL DEFAULT 'ENTITY' COMMENT 'ENTITY 按主体（默认）/ STORE 按门店。只改展示与分层口径，不改存储，可随时切',
    auto_join_on_order TINYINT(4) NOT NULL DEFAULT 1 COMMENT '下单即入会。关掉的话只有主动加入的人才算会员',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_setting_entity (tenant_no, entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员经营口径：按主体还是按门店';
```

### 2.2 `mbr_member` —— 关系主表

```sql
CREATE TABLE IF NOT EXISTS mbr_member
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体。会员挂主体 —— 同一个人在三家店不该各算一次',
    person_no VARCHAR(64) NOT NULL COMMENT '平台人档（usr_person）。**不存 user_no、不存手机号** —— 那两样都从人档取',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'LEAD 线索（不可触达、不进受众）/ ACTIVE / BLOCKED 商家拉黑',
    source VARCHAR(16) NOT NULL COMMENT '首次来源 ORDER/SHARE/SCAN/MANUAL/FAVORITE/SEARCH。明细见 mbr_member_source',
    first_store_no VARCHAR(64) DEFAULT NULL COMMENT '从哪家门店进来的。冗余自 source 明细，列表不回表',
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0 COMMENT '近 90 天单数，每日重算。分层与筛选读它，不现算',
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL COMMENT 'NEW/REGULAR/LOYAL/SLEEPING，主体级。按门店经营时展示 mbr_member_store.level',
    reach_opt_out TINYINT(4) NOT NULL DEFAULT 0 COMMENT '买家关掉了这家店的消息。商家看得到状态，看不到原因',
    remark VARCHAR(255) DEFAULT NULL COMMENT '商家备注（「三单元张阿姨」）',
    joined_at BIGINT(20) NOT NULL,
    claimed_at BIGINT(20) DEFAULT NULL COMMENT '线索被本人认领的时刻',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_no (member_no),
    UNIQUE KEY uk_mbr_member_person (tenant_no, entity_no, person_no),
    KEY idx_mbr_member_last (entity_no, last_order_at),
    KEY idx_mbr_member_level (entity_no, level),
    KEY idx_mbr_member_store (entity_no, first_store_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员：一个人与一家主体的关系';
```

**只剩一个唯一键**（`entity_no + person_no`）：一个自然人在一家主体永远只有一行。
线索转正不再是"合并两行"，而是**人档上绑定账号**那一步的自然结果 ——
`mbr_member` 一行都不用动，只把 `status` 从 `LEAD` 改成 `ACTIVE`、记下 `claimed_at`。

> 这是引入 `usr_person` 最直接的收益：把一类需要事后对账的合并逻辑，从根上消掉了。

### 2.3 `mbr_member_store` —— 他在每家店的往来

```sql
CREATE TABLE IF NOT EXISTS mbr_member_store
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) NOT NULL,
    first_order_at BIGINT(20) DEFAULT NULL,
    last_order_at BIGINT(20) DEFAULT NULL,
    order_count INT(11) NOT NULL DEFAULT 0,
    total_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    d90_order_count INT(11) NOT NULL DEFAULT 0,
    d90_spent_minor BIGINT(20) NOT NULL DEFAULT 0,
    level VARCHAR(16) DEFAULT NULL COMMENT '这家店自己的分层。按门店经营时展示的是它',
    is_first_store TINYINT(4) NOT NULL DEFAULT 0 COMMENT '他是从这家店进来的',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_store (tenant_no, member_no, store_no),
    KEY idx_mbr_store_last (entity_no, store_no, last_order_at),
    KEY idx_mbr_store_level (entity_no, store_no, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员在某家门店的往来与分层';
```

### 2.4 `mbr_member_source` —— 每一次来源

```sql
CREATE TABLE IF NOT EXISTS mbr_member_source
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    source_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL COMMENT 'ORDER/SHARE/SCAN/MANUAL/FAVORITE/SEARCH',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '这一次是从哪家门店进来的',
    link_no VARCHAR(64) DEFAULT NULL COMMENT '哪一条分享链接 / 店铺码，可回查落地页',
    inviter_user_no VARCHAR(64) DEFAULT NULL COMMENT '谁发的链接。分享激励结算读它',
    inviter_role VARCHAR(16) DEFAULT NULL COMMENT 'MERCHANT 商家 / STAFF 员工 / CUSTOMER 老客转发',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT 'MANUAL 时哪个员工录的',
    activity_no VARCHAR(64) DEFAULT NULL COMMENT '因哪场活动进来的',
    is_first TINYINT(4) NOT NULL DEFAULT 0,
    occurred_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_source_no (source_no),
    KEY idx_mbr_source_member (member_no, occurred_at),
    KEY idx_mbr_source_inviter (entity_no, inviter_user_no, occurred_at),
    KEY idx_mbr_source_activity (activity_no),
    KEY idx_mbr_source_store (entity_no, store_no, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员来源明细：哪家店、哪条链接、谁发的、谁录的、因哪场活动';
```

### 2.5 `mbr_tag` / `mbr_member_tag` / `mbr_tag_merge_log`

```sql
CREATE TABLE IF NOT EXISTS mbr_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    tag_no VARCHAR(64) NOT NULL COMMENT '不可变。改名改的是 name，关系行一行不动',
    entity_no VARCHAR(64) NOT NULL COMMENT '属主体：跨门店共享',
    name VARCHAR(32) NOT NULL,
    tag_type VARCHAR(8) NOT NULL DEFAULT 'MCH' COMMENT 'SYS 系统算的（不可改名不可合并）/ MCH 商家的',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED 停用（老的还在、新的打不了）/ MERGED 已并入',
    merged_into VARCHAR(64) DEFAULT NULL COMMENT 'MERGED 时指向目标 tag_no，保留不删',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_tag_no (tag_no),
    UNIQUE KEY uk_mbr_tag_name (tenant_no, entity_no, name),
    KEY idx_mbr_tag_entity (entity_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='标签字典：tag_no 不可变、name 可改';

CREATE TABLE IF NOT EXISTS mbr_member_tag
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    tag_no VARCHAR(64) NOT NULL COMMENT '只存号不存文本',
    tag_type VARCHAR(8) NOT NULL,
    tagged_by VARCHAR(64) DEFAULT NULL COMMENT '谁打的。SYS 为空',
    tagged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_member_tag (tenant_no, member_no, tag_no),
    KEY idx_mbr_tag_filter (entity_no, tag_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='会员标签关系';

CREATE TABLE IF NOT EXISTS mbr_tag_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    entity_no VARCHAR(64) NOT NULL,
    from_tag_no VARCHAR(64) NOT NULL,
    to_tag_no VARCHAR(64) NOT NULL,
    affected_count INT(11) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL,
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_mbr_merge_entity (entity_no, merged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='标签合并留痕：合并不可逆';
```

### 2.6 `mbr_segment` —— 一组条件 = 一个人群

```sql
CREATE TABLE IF NOT EXISTS mbr_segment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    segment_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL COMMENT '商家起的名字（「南门店沉睡老客」）',
    scope_store_no VARCHAR(64) DEFAULT NULL COMMENT '限定门店。空 = 全主体',
    rule_json TEXT NOT NULL COMMENT '筛选条件：层级/标签号/来源/末单区间/消费区间。**存号不存文本**',
    last_count INT(11) NOT NULL DEFAULT 0 COMMENT '上次算出多少人。发券与触达前会重算，这里只是展示',
    counted_at BIGINT(20) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_segment_no (segment_no),
    KEY idx_mbr_segment_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='人群：发券、活动受众、触达共用同一份条件';
```

> **人群是"条件"不是"名单快照"**：名单每天都在变（有人昨天刚下单就不再沉睡）。
> 存快照会让商家在两周后按一份过期名单发券。要留痕的是**发放那一刻命中了谁**，
> 那在 `pmt_coupon_issue` 与 `mbr_reach_log` 里。

### 2.7 `mbr_reach_log` —— 触达记录

```sql
CREATE TABLE IF NOT EXISTS mbr_reach_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    reach_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    member_no VARCHAR(64) NOT NULL,
    segment_no VARCHAR(64) DEFAULT NULL COMMENT '这次发给哪一群人',
    task_no VARCHAR(64) DEFAULT NULL COMMENT '一次群发的批次号',
    channel VARCHAR(16) NOT NULL DEFAULT 'PUSH',
    scene VARCHAR(24) NOT NULL COMMENT 'NOTICE 公告 / WAKEUP 唤回 / COUPON 发券通知。频次闸按场景分档',
    sent_at BIGINT(20) NOT NULL,
    opened_at BIGINT(20) DEFAULT NULL,
    ordered_at BIGINT(20) DEFAULT NULL COMMENT '收到后 7 天内是否下单。效果只认这个',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_reach_no (reach_no),
    KEY idx_mbr_reach_gate (entity_no, member_no, scene, sent_at),
    KEY idx_mbr_reach_task (task_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='触达记录：频次闸查它，效果也算它';
```

> `idx_mbr_reach_gate` 的列序就是频次闸的查询形状：
> 「这家店 → 这个人 → 这个场景 → 最近一次」。

---

## 3. 营销域逐表

### 3.1 `pmt_activity` —— 活动 = 触发 × 优惠 × 排期 × 限量

```sql
CREATE TABLE IF NOT EXISTS pmt_activity
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    store_no VARCHAR(64) DEFAULT NULL COMMENT '空 = 全部门店；有值 = 只在这家店生效',
    name VARCHAR(128) NOT NULL,
    goal VARCHAR(16) DEFAULT NULL COMMENT 'ACQUIRE 拉新 / WAKEUP 唤回 / CLEAR 清库存 / BASKET 提客单。只是建的时候的入口与默认值',

    trigger_type VARCHAR(16) NOT NULL COMMENT 'NONE 无条件 / AMOUNT 订单满额 / QTY 买够件数 / GOODS 命中商品',
    trigger_amount_minor BIGINT(20) DEFAULT NULL COMMENT 'AMOUNT 时的门槛',
    trigger_qty INT(11) DEFAULT NULL COMMENT 'QTY 时的件数',

    benefit_type VARCHAR(16) NOT NULL COMMENT 'CUT 减金额 / PRICE 改单价 / GIFT 送商品 / COUPON 发券',
    benefit_amount_minor BIGINT(20) DEFAULT NULL COMMENT 'CUT 减多少 / PRICE 改成多少',
    benefit_qty INT(11) DEFAULT NULL COMMENT 'GIFT 送几件',
    benefit_ref VARCHAR(64) DEFAULT NULL COMMENT 'GIFT 送哪件商品 / COUPON 发哪张券（指向 pmt_coupon.coupon_no）',

    schedule_type VARCHAR(16) NOT NULL DEFAULT 'ONE_OFF' COMMENT 'ONE_OFF 短期 / ALWAYS_ON 长期 / RECURRING 周期',
    start_at BIGINT(20) DEFAULT NULL COMMENT 'ALWAYS_ON 可为空',
    end_at BIGINT(20) DEFAULT NULL,
    schedule_rule VARCHAR(255) DEFAULT NULL COMMENT 'RECURRING：JSON {weekdays:[3],dayOfMonth:null,from:"08:00",to:"20:00"}，按市场时区',

    quota INT(11) DEFAULT NULL COMMENT '限量（件/份）。PRICE 与 GIFT 必填，ALWAYS_ON 一律必填 —— 没有结束时间又没上限就是永久敞口',
    quota_used INT(11) NOT NULL DEFAULT 0,
    budget_minor BIGINT(20) DEFAULT NULL COMMENT '预算上限（分）。与 quota 至少填一个',
    budget_used_minor BIGINT(20) NOT NULL DEFAULT 0,

    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / RUNNING / PAUSED / ENDED（到量或到期）',
    ended_reason VARCHAR(16) DEFAULT NULL COMMENT 'EXPIRED 到期 / QUOTA 到量 / BUDGET 预算用尽 / MANUAL 手动。商家问「怎么停了」要有答案',
    archived_at DATETIME DEFAULT NULL COMMENT '归档：从列表消失，数据保留。与 status 正交',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_activity_no (activity_no),
    KEY idx_pmt_activity_live (entity_no, status, start_at, end_at),
    KEY idx_pmt_activity_store (entity_no, store_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动：触发条件 × 优惠形式 × 排期 × 限量';
```

**四类玩法在这张表里的样子**（旧枚举退场后就是取值组合）：

| 玩法 | trigger_type | benefit_type | 关键字段 |
|---|---|---|---|
| 满减 | `AMOUNT` | `CUT` | `trigger_amount_minor=5000` `benefit_amount_minor=500` |
| 限时特价 | `GOODS` | `PRICE` | `benefit_amount_minor=活动价`，商品在 `pmt_activity_goods` |
| 买 N 送 M | `QTY` | `GIFT` | `trigger_qty=2` `benefit_qty=1` |
| 发券 | `NONE` | `COUPON` | `benefit_ref=券号` |
| 第二件半价（将来） | `QTY` | `PRICE` | 不加表、不加枚举 |
| 满额送券（将来） | `AMOUNT` | `COUPON` | 同上 |

### 3.2 `pmt_activity_audience` / `pmt_activity_goods`

```sql
CREATE TABLE IF NOT EXISTS pmt_activity_audience
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    audience_type VARCHAR(16) NOT NULL COMMENT 'TAG 标签号 / LEVEL 会员层 / SOURCE 来源 / SEGMENT 人群 / NON_MEMBER 非本店会员',
    audience_value VARCHAR(64) NOT NULL COMMENT '标签号 / 层 / 来源 / 人群号。**存号不存文本**',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_audience (tenant_no, activity_no, audience_type, audience_value),
    KEY idx_pmt_audience_activity (activity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动受众：一行都没有 = 对所有人生效';

CREATE TABLE IF NOT EXISTS pmt_activity_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GOODS' COMMENT 'GOODS 指定商品 / CATEGORY 指定类目 / ALL 全店',
    ref_no VARCHAR(64) NOT NULL COMMENT '商品号或类目号；ALL 时填 *',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_activity_goods (tenant_no, activity_no, scope_type, ref_no),
    KEY idx_pmt_goods_ref (entity_no, scope_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='活动作用范围。用表不用 TEXT：要反查「这个商品在哪些活动里」';
```

> `idx_pmt_goods_ref` 就是**冲突提示**要的那条路：建活动时按商品号反查，
> 立刻能说「这件商品已经在『周三特价』里，同类取最优」。
> 旧模型把商品塞在 `goods_nos TEXT` 里，这个问题只能全表扫。

### 3.3 `pmt_coupon` —— 券模板（五段 + 次数）

```sql
CREATE TABLE IF NOT EXISTS pmt_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '商家券的主体；平台券为空',
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM / MERCHANT —— 分账扣谁的钱',
    title VARCHAR(128) NOT NULL,

    benefit_mode VARCHAR(16) NOT NULL COMMENT 'CASH 现金 / PERCENT 折扣 / GIFT 兑换 / FREE_SHIP 免运费',
    benefit_value BIGINT(20) NOT NULL DEFAULT 0 COMMENT 'CASH 面额(分) / PERCENT 万分比(8500=八五折) / 其余 0',
    benefit_cap_minor BIGINT(20) DEFAULT NULL COMMENT '折扣封顶(分)。PERCENT 必填 —— 不封顶的敞口随订单金额无限放大',
    benefit_ref VARCHAR(64) DEFAULT NULL COMMENT 'GIFT 兑换哪件商品',

    min_amount_minor BIGINT(20) DEFAULT NULL COMMENT '金额门槛。空 = 不限',
    min_qty INT(11) DEFAULT NULL COMMENT '件数门槛。空 = 不限',

    scope_type VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT 'ALL 全店 / STORE 指定门店 / CATEGORY 指定类目 / GOODS 指定商品',
    scope_desc VARCHAR(128) DEFAULT NULL COMMENT '展示文案。**规则以 pmt_coupon_scope 为准**，两者不符要在运营端标出来',

    validity_mode VARCHAR(16) NOT NULL DEFAULT 'ABSOLUTE' COMMENT 'ABSOLUTE 固定起止 / RELATIVE 领取后 N 天',
    start_at BIGINT(20) DEFAULT NULL,
    end_at BIGINT(20) DEFAULT NULL,
    valid_days INT(11) DEFAULT NULL COMMENT 'RELATIVE 时的天数',

    issue_mode VARCHAR(16) NOT NULL DEFAULT 'CENTER' COMMENT 'CENTER 领券中心 / TARGETED 定向发 / ACTIVITY 活动发 / CODE 发码',
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER 下单抵扣 / STORE_CODE 到店出示核销 / AUTO 自动生效',
    times_total INT(11) NOT NULL DEFAULT 1 COMMENT '一张能用几次。1 = 一次性；N = 次卡（豆浆 5 杯）',

    total_count INT(11) DEFAULT NULL COMMENT '发行量。空 = 不限（仅 TARGETED 允许）',
    received_count INT(11) NOT NULL DEFAULT 0,
    per_user_limit INT(11) NOT NULL DEFAULT 1,
    budget_minor BIGINT(20) DEFAULT NULL COMMENT '预算。建券时断言 budget >= total_count × 单张最大优惠',

    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED 暂停发放（已领的不受影响）/ ENDED',
    archived_at DATETIME DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_coupon_no (coupon_no),
    KEY idx_pmt_coupon_entity (entity_no, status),
    KEY idx_pmt_coupon_center (status, issue_mode, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='券模板：权益 × 门槛 × 范围 × 有效期 × 发放 × 核销 × 次数';

CREATE TABLE IF NOT EXISTS pmt_coupon_scope
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    coupon_no VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL COMMENT 'STORE / CATEGORY / GOODS',
    ref_no VARCHAR(64) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_coupon_scope (tenant_no, coupon_no, scope_type, ref_no),
    KEY idx_pmt_scope_ref (scope_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='券的适用范围（规则）。scope_desc 只是文案';
```

### 3.4 `pmt_user_coupon`

```sql
CREATE TABLE IF NOT EXISTS pmt_user_coupon
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    user_coupon_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL COMMENT '冗余：券包按店分组、商家看自己发出去多少',
    issue_no VARCHAR(64) DEFAULT NULL COMMENT '哪一批发的',
    status VARCHAR(16) NOT NULL DEFAULT 'UNUSED' COMMENT 'UNUSED / USED（一次性用掉或次卡用满）/ EXPIRED / REVOKED',
    times_used INT(11) NOT NULL DEFAULT 0 COMMENT '次卡已核销几次',
    received_at BIGINT(20) NOT NULL,
    expire_at BIGINT(20) NOT NULL COMMENT '**这一张**的失效时刻。RELATIVE 券在领取时算好落库 —— 现算的话改模板会把已发的券一起改掉',
    redeem_code VARCHAR(32) DEFAULT NULL COMMENT '到店核销码。只有 STORE_CODE 券有',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_user_coupon_no (user_coupon_no),
    UNIQUE KEY uk_pmt_redeem_code (tenant_no, redeem_code),
    KEY idx_pmt_uc_user (user_no, status, expire_at),
    KEY idx_pmt_uc_coupon (coupon_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='用户券：发到某个人手上的那一张，有自己的有效期';

### 3.5 `pmt_coupon_issue` —— 每一批发放

```sql
CREATE TABLE IF NOT EXISTS pmt_coupon_issue
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    issue_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    issue_mode VARCHAR(16) NOT NULL COMMENT 'TARGETED 定向 / ACTIVITY 活动发 / CENTER 领券中心（自领的不记批次）',
    segment_no VARCHAR(64) DEFAULT NULL COMMENT '发给哪一群人',
    activity_no VARCHAR(64) DEFAULT NULL COMMENT '因哪场活动发的',
    rule_snapshot TEXT DEFAULT NULL COMMENT '发放当时的人群条件快照。人群条件后来会改，追责要看当时那一份',
    planned_count INT(11) NOT NULL DEFAULT 0,
    issued_count INT(11) NOT NULL DEFAULT 0,
    skipped_count INT(11) NOT NULL DEFAULT 0 COMMENT '被跳过的（已达每人上限、线索会员、已退订）',
    amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本批最大敞口',
    operator_no VARCHAR(64) DEFAULT NULL,
    issued_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_issue_no (issue_no),
    KEY idx_pmt_issue_coupon (coupon_no, issued_at),
    KEY idx_pmt_issue_segment (segment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='发放批次：发给谁、发了多少、跳过多少、谁发的';
```

> `skipped_count` 与 §UI 的那条规则对应：**不静默少发**。
> 界面要能说出「25 发出、12 跳过（其中 9 人 7 天内收过、3 人是线索）」。

### 3.6 `pmt_apply` —— 优惠发生记录（线上抵扣与线下核销统一在这一张）

原设计里还有一张 `pmt_redeem_log`，与本表**实打实地重叠**：一张券在下单抵扣时两边各写一行，
金额要两处对得上。合并掉之后，**券的每一次使用就是这张表的一行** ——
线上带 `order_no`，线下带 `store_no` + `operator_no`，次卡用 5 次就是 5 行。

```sql
CREATE TABLE IF NOT EXISTS pmt_apply
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    apply_no VARCHAR(64) NOT NULL,
    promo_type VARCHAR(16) NOT NULL COMMENT 'ACTIVITY 活动 / COUPON 券 / POINTS 积分',
    promo_no VARCHAR(64) NOT NULL COMMENT '活动号 / 用户券号 / 积分流水号',
    user_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) DEFAULT NULL,
    store_no VARCHAR(64) DEFAULT NULL COMMENT '线下核销在哪家门店；线上单填出货门店',
    order_no VARCHAR(64) DEFAULT NULL COMMENT '线上抵扣用在哪一单。线下核销为空',
    sub_order_no VARCHAR(64) DEFAULT NULL COMMENT '按商家拆的那一单；跨商家的平台券会有多行',
    redeem_mode VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER 下单抵扣 / STORE_CODE 到店核销 / AUTO 自动生效',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '线下核销时是哪个店员',
    amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '这一次减了多少。兑换类为 0',
    funder VARCHAR(16) NOT NULL DEFAULT 'MERCHANT' COMMENT 'PLATFORM / MERCHANT，与结算拆分同一口径',
    applied_at BIGINT(20) NOT NULL,
    reverted_at BIGINT(20) DEFAULT NULL COMMENT '订单取消/退款时置。**线下核销不可撤销**，那一行恒为空',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_apply_no (apply_no),
    KEY idx_pmt_apply_order (order_no),
    KEY idx_pmt_apply_promo (promo_type, promo_no, applied_at),
    KEY idx_pmt_apply_entity (entity_no, applied_at),
    KEY idx_pmt_apply_store (store_no, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='优惠发生记录：一单命中了什么、一张券被用了几次，线上线下同一张表';
```

**三件事都读它**：活动效果（按 `promo_no` 聚合）、券的核销与对账（按用户券号）、
会员来源归因（因哪场活动第一次下单）。

---

## 4. 对象模型

### 4.1 聚合与边界

```mermaid
classDiagram
    class Person {
        +PersonNo no
        +PhoneHash phoneHash
        +UserNo user
        +PersonNo mergedInto
        +void bindPhone(Phone)   %% A/B/C：多数是一步到位
        +void bindAccount(UserNo)
        +void mergeInto(Person)
    }
    class Member {
        +MemberNo no
        +EntityNo entity
        +PersonNo person
        +MemberStatus status
        +Level level
        +Metrics metrics
        +bool reachable()
        +void claim(UserNo)
        +void applyOrder(SubOrder)
    }
    class MemberStore {
        +StoreNo store
        +Metrics metrics
        +Level level
        +bool isFirstStore
    }
    class MemberSource {
        +SourceType type
        +StoreNo store
        +LinkNo link
        +UserNo inviter
        +OperatorNo operator
        +ActivityNo activity
    }
    class MemberTag {
        +TagNo tag
        +TagType type
    }
    class Tag {
        +TagNo no
        +string name
        +TagStatus status
        +TagNo mergedInto
        +void rename(string)
        +void mergeInto(Tag)
    }
    class Segment {
        +SegmentNo no
        +StoreNo scopeStore
        +Rule rule
        +List~MemberNo~ resolve()
    }
    class ReachLog {
        +Scene scene
        +long sentAt
        +bool openedInWindow()
    }

    class Activity {
        +ActivityNo no
        +Trigger trigger
        +Benefit benefit
        +Schedule schedule
        +Quota quota
        +StoreNo store
        +bool isActiveAt(long)
        +bool covers(Buyer)
        +Money discountFor(Basket)
    }
    class Audience {
        +AudienceType type
        +string value
    }
    class GoodsScope {
        +ScopeType type
        +string refNo
    }
    class Coupon {
        +CouponNo no
        +Benefit benefit
        +Condition condition
        +Scope scope
        +Validity validity
        +IssueMode issue
        +RedeemMode redeem
        +int timesTotal
        +Money discountFor(Money base)
        +UserCoupon issueTo(UserNo, long now)
    }
    class UserCoupon {
        +UserCouponNo no
        +long expireAt
        +int timesUsed
        +Status status
        +bool usableAt(long, Basket)
        +PromotionApply redeem(...)
    }
    class CouponIssue
    class PromotionApply

    Person "1" o-- "0..*" Member
    Member "1" *-- "0..*" MemberStore
    Member "1" *-- "0..*" MemberSource
    Member "1" *-- "0..*" MemberTag
    Tag "1" o-- "0..*" MemberTag
    Member "1" o-- "0..*" ReachLog
    Segment "1" o-- "0..*" ReachLog

    Activity "1" *-- "0..*" Audience
    Activity "1" *-- "0..*" GoodsScope
    Activity "0..1" o-- "0..1" Coupon
    Coupon "1" *-- "0..*" UserCoupon
    Coupon "1" o-- "0..*" CouponIssue
    UserCoupon "1" o-- "0..*" PromotionApply
    Segment "1" o-- "0..*" CouponIssue
```

### 4.2 五个聚合根，各管一件事

| 聚合根 | 边界内 | 不变量（由它自己守） |
|---|---|---|
| **Person**（user 域） | 自己 + 人档合并 | **手机号必填**（没有号就没有人档，也就不是会员）；账号是可空的 1:1；两个已注册账号**不自动合并**；合并后源档保留 |
| **Member** | MemberStore / MemberSource / MemberTag | **一个 person 在一家主体只有一条**；线索不可触达；主体级与门店级指标一起更新 |
| **Tag** | 自己 + 合并关系 | `tag_no` 不可变；SYS 标签不可改名不可合并；MERGED 后不可再被打上 |
| **Segment** | 规则 | 规则里只存号（标签号/门店号），解析出人群时才落到具体 member |
| **Activity** | Audience / GoodsScope | 排期与限量的判断只在 `isActiveAt` 与 `quota`；受众为空即全体 |
| **Coupon** | 自己（模板） | 发出的每一张是 **UserCoupon** 的事，模板改动不影响已发出的券 |
| **UserCoupon** | 自己 + 它在 `pmt_apply` 里的使用记录 | 有效期与次数在自己身上；用一次落一行 apply；线下核销不可撤销 |

> **Coupon 与 UserCoupon 是两个聚合根，不是一个。** 这条是整套模型里最要紧的一句：
> 模板是商家的东西，券是用户的资产。改模板、停发、活动结束，
> 都不许动已经发到人手上的那一张 —— 只有它自己的有效期与核销次数说了算。

### 4.2b 三条入会路径，落到同一个 person

```
① 已登录且**已绑手机号**（下单 / 扫码 / 收藏 / 主动加入）
   user_no ──PersonPort──► 人档（一定存在，手机号是注册时就有的）──► upsert member(entity, person)

② 商家手工录入手机号（本人可能还没注册）
   phone ──hash──► 人档（没有就建，user_no 留空）──► member.status = LEAD

③ 微信登录、没授权手机号
   ──► 只有账号，**没有人档、不入会**。他照常能逛能下自提单，
       点「加入会员」时弹一次手机号授权；拒绝就继续当普通买家

④ 他绑了手机号
   phone ──hash──► A 没有人档：建一份，绑上
                   B 有人档、没绑过账号：**直接绑**（商家录的、或他以前下过单的那份）
                   C 有人档、绑着别的账号：拒绝，走人工
   绑定完成 ──► 这份人档下所有 LEAD 会员一次性转 ACTIVE，记 claimed_at
```

第 ④ 步是这套设计的关键：**一次绑定，三家商家的会员同时转正，而且不需要合并任何东西**。
身份散在各商家 member 行上（最早的 `phone` 列方案）要逐家认领去重；
允许无号入会（上一版）要合并两份人档连同两边的会员关系；
现在这一版只是给一份人档补一个 `user_no`。

### 4.3 跨域端口（沿用现有形状）

| Port | 方向 | 用途 |
|---|---|---|
| `PersonPort` | member → user | 按手机号/账号解析或创建人档；绑定账号；脱敏后四位 |
| `MemberQueryPort` | marketing → member | 「这个买家命中活动受众吗」「这批条件命中哪些人」 |
| `MemberEventPort` | trade → member | 支付成功 → 入会 / 更新指标 |
| `ActivityPort` | trade → promotion | 下单算价：自动优惠、活动价、买赠 |
| `CouponPort` | trade → promotion | 可用券、最优券、抵扣与退回 |
| `PromotionApplyPort` | trade → promotion | 落 `pmt_apply`（同事务，不异步） |
| `SharePort` | member → marketing | 来源里的 `link_no` / `inviter` 回查落地页与激励结算 |

**算价链路上不查会员明细**：受众判断只要 `Set<TagNo> + Level + 是否本店会员`，
一次查询取回，之后全在内存里判。这是为了不让下单多一条跨域强依赖。

---

## 4.4 分几个模块：两个**域包**，不新建 Maven 模块

问的是「会员与营销算一个模块还是两个」。先把这个项目里「模块」的两种含义分开：

| | 是什么 | 现状 |
|---|---|---|
| **Maven 模块** | 物理边界：`shop-base` / `shop-core` / `shop-merchant` / `shop-settle` / `shop-channel` / `shop-notify` / `shop-app` | 7 个，**按分层与部署单元切**，不按业务域切 |
| **域包** | 业务边界：`ai.neargo.shop.{user,merchant,community,product,trade,fulfillment,marketing,settle,message,platform,content,risk}` | 12 个，`ArchitectureTest.svcModulesMustNotDependOnEachOther` 逐对断言**不得互相依赖**，跨域一律走 `spi` 的 Port |

**这个项目的域边界是包 + ArchUnit 守的，不是 Maven 模块。**
`marketing`、`product`、`trade` 全都住在 `shop-core` 里，会员没有理由特殊。

### 结论：`member` 与 `promotion` 两个域包，都落在 `shop-core`

| 方案 | 判断 |
|---|---|
| **A. 两个域包（推荐）** `ai.neargo.shop.member` + `ai.neargo.shop.promotion`，登记进 `DOMAINS`，跨域走 Port | ✅ 与既有 12 个域同构；ArchUnit 立刻开始守方向 |
| B. 合成一个包（`growth`） | ❌ 会员与营销的依赖是**单向**的（营销要问会员「他是不是熟客」，会员不问营销）。合成一个包，ArchUnit 就管不住这个方向，早晚出现会员反向 import 营销 |
| C. 新建 Maven 模块 `shop-member` / `shop-promotion` | ❌ 此刻代价为负：私有父 POM 只存在于开发机的 `~/.m2`（云端 CI 编不了后端），模块越多这条链越长；而收益（编译隔离）在这个体量上感知不到 |
| D. 并进现有 `marketing` 包 | ❌ 那个包已经装着团购、求团、裂变、归因、内容位；再塞会员与新券制，它会变成第二个「什么都有」的地方 |

### 为什么偏偏是两个包，而不是一个

依赖方向是单向的，写下来就清楚：

```
promotion ──MemberQueryPort──► member      受众判断：他是不是熟客/沉睡/带某标签
member    ──(只存 activityNo 字符串)──► ✗   来源里记「因哪场活动进来的」，但不 import 营销的类
trade     ──ActivityPort / CouponPort──► promotion
trade     ──MemberEventPort──► member
```

合成一个包之后，这条方向没有任何东西守着 —— 而它一旦被破坏（会员的分层逻辑里
直接读了活动表），两者就再也拆不开了。**分包的成本是今天写两个 Port，
收益是这条线以后一直是直的。**

### 什么时候才该拆成 Maven 模块

给三条**可测量**的触发条件，满足任意一条再拆，别凭感觉：

1. 营销需要**独立发版节奏**（比如活动改一次要热更，而会员一个月不动）；
2. 会员数据要**独立库/独立扩容**（会员表量级到订单表那个量级）；
3. 出现**第二个消费方**（比如另一个 App 或对外开放接口只要会员这一块）。

在那之前，两个域包 + Port 已经把边界立住了；拆模块只是把同一条边界换个物理形式。

## 5. 旧表退场路径

| 旧表 | 新表 | 怎么退 |
|---|---|---|
| `mkt_coupon` | `pmt_coupon` + `pmt_coupon_scope` | 新服务上线后停止写旧表；观察一周；删表 |
| `mkt_user_coupon` | `pmt_user_coupon`（核销记录进 `pmt_apply`） | 同上。测试数据不迁移 |
| `mkt_campaign` | `pmt_activity` + `_audience` + `_goods` | 同上 |
| `mkt_coupon_issue` | `pmt_coupon_issue` | 同上 |
| `mkt_attribution*` | **保留** | 归因是另一件事（谁带来的流量），会员来源引用它的结论 |
| `mkt_group_*` / `mkt_quote*` / `mkt_request*` / `mkt_fission_*` | **保留** | 团购、求团、裂变不在本次范围 |

**要一起改的代码**（这是真正的工作量，DDL 只是开头）：

- 后端：`CouponServiceImpl` / `CampaignServiceImpl` / `CouponPortImpl` / `CampaignPortImpl`
  → 改为读写 `pmt_*`；`OrderServiceImpl` 的 `Discounts` 接入点保持形状不变。
- b-app：`pages/marketing`（活动）+ 新增券页；ops-web：券模板与发放记录两页。
- 测试：券与活动的既有用例整体重写 —— **金额级断言必须保留**
  （同样的篮子、同样的券，改造前后减出来的钱要一分不差）。

---

## 6. 风险

1. **这是一次替换，不是一次扩展**：券与活动的服务层、三端页面、用例都要重写。
   建议按 §5 的顺序做，任何一步都能独立上线并回退。
2. **算价是最贵的路径**：接入点（`ActivityPort` / `CouponPort` 的签名）保持不变，
   实现换库。这样交易域的用例一行不用改，能直接当回归基线。
3. **两套表并存期不要双写**：双写会产生"两边不一致该信谁"的问题。
   用**只读切换**：新表建好后，先让读走新表（数据由新服务写入），旧表只留作对照。
4. **`pmt_apply` 只记新单**：历史单不回填，效果卡标明统计起始日。
5. **测试数据可删**：库里现在是测试数据，所以这次可以推倒重来 ——
   这也是现在做比以后做便宜得多的原因。

---
确认记录：待用户确认
