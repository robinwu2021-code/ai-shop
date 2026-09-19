# TDD-会员标签与定向营销

状态：已确认（2026-09-19）· 批 A 已上线（待运营开任务）· 批 B 后端已上线（界面待打包）· 批 C 已实现（未上线）
关联需求：[PRD-会员标签与定向营销](../requirements/PRD-会员标签与定向营销.md)（AC-1 … AC-15）
原型：[会员标签与定向营销](https://claude.ai/artifact/8VNNrPTZU8ypwAj3w71Bhc)（m01–m21 · c01 · o01–o02）·
[营销 v2](https://claude.ai/artifact/EeKjhCyJ9P3i5iDVNbhUPt)（s01 · s04–s06 · s16 · s18）
上游设计：[TDD-会员体系](./design/TDD-会员体系.md)（底座；本文不重复其表结构，只写增量）
创建：2026-09-19 · 最后更新：2026-09-19

---

## 一句话

**在已有的会员底座上补四样东西：一个统一的「受众」模型（三处共用）、一个每日分层重算任务、
一张触达批次表（让「发出去的」有东西可列）、两处效果回写（进店、下单）。**
不新建域、不动算价链路、只加一张表和五个列。

---

## §0 对账一 · 需求 → 设计

| AC | 需求原文（一句话） | 落点 |
|---|---|---|
| AC-1 | 详情页打标，列表按标签筛得到；去掉后筛不到 | 复用 `POST /biz/members/tags` + `MemberQuery.tagNos`；b-app m04/m05 接线 |
| AC-2 | 批量打标确认前显示人数，已有的不重复计 | 新 `POST /biz/members/tags/batch`（`confirm=false` 试算）· `MemberTagService#batch` |
| AC-3 | 每人 ≤10、每店 ≤50，超限被拒 | `MemberTagService` 现有上限校验；batch 里超限者计入 `skippedFull` 而不是整批失败 |
| AC-4 | 线索会员能打标但不进受众 | 打标不看 `status`；受众解析 `AudienceResolver` 过滤 `LEAD`，跳过原因 `LEAD` |
| AC-5 | 65 天没下单的常客次日变沉睡；改阈值重算后回来 | `MemberLevelRecomputeJob` + `MemberLevelService#recompute` + `LevelPolicy`（读 `sys_setting`） |
| AC-6 | 重算幂等，连跑两次无一行 `updated_at` 变化 | `recompute` 只更新**分层或 d90 真变了**的行 |
| AC-7 | 重算不写触达、不推送 | `MemberLevelService` 不依赖 `UserPushPort` / `MbrReachLog`（ArchUnit 断言，见 §5） |
| AC-8 | 活动受众选标签：无标签不命中，打上后命中 | `pmt_activity_audience` 已支持 TAG；b-app `activity-edit` 换成 `audience-picker` |
| AC-9 | 活动按**发布时**的人群条件生效 | 新列 `pmt_activity_audience.rule_snapshot`；`audienceHits` 有快照时走 `MemberQueryPort#matchesRule` |
| AC-10 | 覆盖 0 人不让发布 | `POST /biz/member-audience/preview`；`ActivityService#publish` 服务端再校一次 → `MEMBER_AUDIENCE_EMPTY` |
| AC-11 | 发券直接选标签 | `issue` 入参由 `segmentNo` 扩为 `audiences`；新列 `pmt_coupon_issue.audience_json` |
| AC-12 | 发 25 人，3 人进店、2 人下单 → 25/3/2 | 新表 `mbr_reach_task`；进店回写 `opened_at`（`/mp/store/{no}/enter` 带 `reachNo`）；下单回写 `ordered_at`（`onOrderPaid`） |
| AC-13 | 第 8 天下单不计 | 窗口 `member.reach.attribution-days`（默认 7）在回写时判定 |
| AC-14 | 两次触达后下单只归最近一次 | 回写取 `sent_at` 最大且在窗口内的那一行 |
| AC-15 | 运营端看触达次数与跳过率，看不到标签名/人群条件 | 扩 `ReachStatVO`；`GET /ops/members/reach-stats` 只返回计数 |

**孤立项**
- 没落点的 AC：无。
- 挂不上 AC 的设计：
  - `GET /biz/member-tags/{tagNo}/usage`、`GET /biz/member-segments/{segmentNo}`（原型 m08、m11 的「用在哪」）——PRD G1/G3 的延伸，PRD 未单列 AC。**补 AC-16**：「停用或合并被进行中活动引用的标签前，界面列出引用方」。
  - 人群条件新增 `reachTaskNo + reachOutcome`（原型 m20「没来的存人群 / 下单的打标签」）——PRD G5 未写。**补 AC-17**：「从一次触达的效果页，能把『没来的人』存成人群、给『下单的人』批量打标」。
  - 两条补进 PRD §四，见 §6 偏差说明。

---

## §1 现状与影响面

### 1.1 已有、直接复用（2026-09-19 逐条读过代码）

| 能力 | 位置 | 本次怎么用 |
|---|---|---|
| 标签字典 + 关系 + 合并留痕 | `mbr_tag` / `mbr_member_tag` / `mbr_tag_merge_log`（V226）· `MemberTagServiceImpl` | 原样；加批量与引用查询 |
| 给人打/去标签 | `POST /biz/members/tags`（`TagReq(memberNos, add, remove)`） | **原样**，m05 直接调；此前没有任何页面调用 |
| 人群（存条件） | `mbr_segment.rule_json`（V230）· `MemberSegmentServiceImpl`（≤50 个）· `/biz/member-segments/preview` | 原样；`MemberQuery` 加两个条件 |
| 按人群发券、当场重算、预算超出整批拒 | `PmtCouponServiceImpl#issue` → `MemberQueryPort#resolveSegment` | 入参扩为受众项列表 |
| 活动受众五种类型 + 算价命中（多行取或） | `pmt_activity_audience`（V242）· `ActivityPricingServiceImpl#audienceHits` | 原样；SEGMENT 加快照分支 |
| 触达：试算、频次闸（按场景读 `sys_setting`）、跳过分档 | `MemberReachServiceImpl`（`member.reach.min-days.<scene>`） | 原样；加批次头与跳转链接 |
| 买家进店上报 | `POST /mp/store/{merchantNo}/enter` | `EnterReq` 加可选 `reachNo` |
| 支付成功入会钩子（幂等靠 `ref_no`） | `MemberServiceImpl#onOrderPaid` | 末尾加一次触达归因 |
| 定时任务框架 | `JobHandler` + `@Scheduled` + `@SchedulerLock` + `JobSupport`（生产走 `ai-shop-job` worker） | 照 `ChannelMessageRetentionJob` 写 |
| 配置读写 | `SettingPort#get / put` | 分层阈值、归因窗口、上次重算结果 |

### 1.2 缺什么（盘点结论）

1. **分层只在下单时算**：`MemberServiceImpl#levelOf` 只被 `onOrderPaid` 调用；不下单的人永远不会变沉睡。阈值 60 / 6 / 2 写死。`d90_order_count` 只增不减（列注释写着「每日重算」，没有任务）。
2. **受众三处三套**：活动是 `List<AudienceItem>`、发券是单个 `segmentNo`（含 `@SLEEPING` 这类预设键）、发消息是单个 `segmentNo`。
3. **SEGMENT 受众是引用语义**：`judge()` 当场按人群号算，改人群即改了进行中的活动。
4. **触达没有批次头**：`mbr_reach_log` 只有 `task_no`，没有标题、受众、计数，「发出去的」列表无从列起；`opened_at / ordered_at` 无人回写；推送跳转写死 `/pages/index/index`，打开后不知道是哪一条。
5. 运营端没有分层口径的入口。

### 1.3 会被改到的在跑功能

| 功能 | 改动 | 回归关注 |
|---|---|---|
| 下单算价 | `audienceHits` 的 SEGMENT 分支多一个快照判断 | 无快照的存量活动行为逐字节不变 |
| 发券 | 入参从 `segmentNo` 变 `audiences`，旧字段保留一个版本 | 旧版 App 仍传 `segmentNo` 要能发 |
| 发消息 | 同上；推送链接改为店铺页 | 频次闸不动 |
| 支付回调 | `onOrderPaid` 末尾多一次 UPDATE | 必须在同一个 `executeWithoutScope` 内、幂等 |
| 进店上报 | 多一个可选字段 | 不带 `reachNo` 时零变化 |

### 1.4 明确不受影响

订单算价的叠加顺序（活动 → 券 → 积分）· 频次闸口径 · 标签合并已有逻辑 · 人档与认领 · 会员卡（`/ops/marketing/member-cards`，仍是 mock，另一条线）· 运营端推送任务（`notify_push_task`，平台自己的全员推送，不接会员受众）。

---

## §2 方案

### 2.1 领域对象

```
                     ┌──────────── member 域（shop-core/member）────────────┐
 MemberQuery ──条件──▶ Segment(人群)          Tag(标签字典) ◀── MemberTag(关系)
      ▲                    │                        │
      │               rule_json                   tag_no
      │                    ▼                        ▼
 AudienceItem{type,value} ×N  ──AudienceResolver──▶ Resolution{matched, reachable[], skips[]}
      │                                                    │
      │ 快照（发布/发送那一刻）                               ▼
      ├──▶ pmt_activity_audience(+rule_snapshot)      ReachTask(批次头) 1──N ReachLog(每人一行)
      ├──▶ pmt_coupon_issue(+audience_json)                   ▲ opened_at ← 进店
      └──▶ mbr_reach_task.audience_json                        └ ordered_at ← 支付
 LevelPolicy(阈值, 读 sys_setting) ──▶ MemberLevelService ──每日──▶ mbr_member.level / d90
```

| 对象 | 类型 | 归属 | 说明 |
|---|---|---|---|
| `AudienceItem(type, value)` | 值对象 | `shop-base spi/member` | type ∈ `ALL` `LEVEL` `TAG` `SEGMENT` `SOURCE` `NON_MEMBER`。**多项之间取或**；空列表只在活动里合法（= 所有人，向后兼容） |
| `AudienceResolution(matched, reachable, skips, countable)` | 值对象 | `spi/member` | `countable=false` 仅当含 `NON_MEMBER`（覆盖「所有非会员」数不出来） |
| `SkipReason` | 枚举 | `spi/member` | `LEAD` `OPT_OUT` `NO_ACCOUNT` `TOO_SOON`——沿用 `member-reach` 现有四档，不新增 |
| `LevelPolicy(sleepDays, loyalD90, regularD90)` | 值对象 | `member/service` | 从 `SettingPort` 读，带默认值；`onOrderPaid` 与每日重算共用同一份 |
| `ReachTask` | 实体（新） | `member/entity/MbrReachTask` | 一次发消息的批次头：受众快照、标题正文、计数、统计截止时刻 |
| `ReachOutcome` | 枚举 | `member/dto` | `SENT` `OPENED` `ORDERED` `NOT_OPENED`——人群条件与效果页共用 |
| `AudienceRef(kind, refNo, name, status)` | 值对象 | `spi/promotion` | 标签/人群被谁引用（活动、发券批次） |

**两种「多选」口径，必须分开写在界面上**（与原型的偏差，见 §6）：

| 场合 | 语义 | 理由 |
|---|---|---|
| 筛选 / 人群条件里选两个标签（`MemberQuery.tagNos`） | **同时含**（交集），现有实现 | 商家在筛选里点第二个标签是想收窄；取并集人数反而涨，没人看得懂 |
| 选人面板里选两个受众项 | **满足任一**（并集），`pmt_activity_audience` 现有语义 | 「沉睡的 + 爱囤货的都给」是一次发给两拨人 |

要「同时是沉睡又爱囤货」，就先在筛选里存成人群，面板里选这个人群。面板组标题写「满足任一即可」，筛选页写「同时含以下标签」。

### 2.2 契约变更

#### 端点（B 端，前缀 `/biz`，权限码沿用现有两个）

| 方法 | 路径 | 权限 | 新/改 | 原型 |
|---|---|---|---|---|
| POST | `/biz/members/audience-preview` | `biz:customer` | **新**（挂在 members 下，见 §6 批 B） | m12 · m13 · m15 · m16 · m17 · m18 |
| POST | `/biz/members/tags/batch` | `biz:customer` | **新** | m06 · m20 |
| GET | `/biz/member-tags/{tagNo}/usage` | `biz:customer` | **新** | m08 · m09 |
| GET | `/biz/member-segments/{segmentNo}` | `biz:customer` | **新**（详情 + 用在哪） | m11 |
| GET | `/biz/member-reach/task` | `biz:customer` | **新**（单数，见 §6 批 C） | m19 |
| GET | `/biz/member-reach/task/{taskNo}` | `biz:customer` | **新** | m20 |
| GET | `/biz/members` · `/biz/members/{memberNo}` · `/biz/members/stats` | `biz:customer` | 改：VO 加字段 | m01 · m04 |
| POST | `/biz/member-reach/plan` · `/send` | 原样 | 改：`ReachReq` 加 `audiences` | m18 |
| POST | `/biz/coupons/{couponNo}/issue`（现有发券） | 原样 | 改：加 `audiences` | m17 |
| GET | `/biz/coupon-issues` | 原样 | 改：VO 加 `usedCount / usedAmountMinor / audienceDesc` | m19「券」tab |
| POST/PUT | 活动新建 / 编辑 / 发布 | 原样 | 改：SEGMENT 项落快照；发布校验覆盖人数 | m14–m16 |

#### 端点（C 端 / 运营端）

| 方法 | 路径 | 权限 | 新/改 | 原型 |
|---|---|---|---|---|
| POST | `/mp/member-reach/{reachNo}/opened` | 登录买家 | **新**（原设计挂在 `enter` 上，见 §6 批 C） | c01 |
| GET | `/ops/members/reach-stats?days=30` | `member:member:read` | 改：VO 加四列 | o01 |
| GET | `/ops/members/level-policy` | `member:member:read` | **新** | o02 |
| POST | `/ops/members/level-policy` | `system:param:update`（复用，同 `inventory.policy` / `proxy-limit`） | **新** | o02 |
| 「现在重算」 | 复用运营端「定时任务」页的手动触发 | 现有 | — | o02 |

#### 关键请求/响应

```java
// spi/member —— 三处共用
record AudienceItem(String type, String value) {}
record AudienceResolution(int matched, List<Audience> reachable,
                          List<Skip> skips, boolean countable) {}

// POST /biz/member-audience/preview
record AudiencePreviewReq(List<AudienceItem> items, String scene /* 发消息时给，频次闸用 */,
                          boolean forActivity /* true 时不算 reachable */) {}
record AudiencePreviewVO(Integer matched /* countable=false 时为 null */,
                         Integer reachable, List<Skip> skips, String desc /* 「沉睡 · 爱囤货」 */) {}

// POST /biz/members/tags/batch  —— 两种圈人方式二选一；confirm=false 只试算
record BatchTagReq(List<String> memberNos, MemberQuery rule, String scopeStoreNo,
                   String tagNo, String action /* ADD | REMOVE */, boolean confirm) {}
record BatchTagVO(int matched, int alreadyInState, int willChange,
                  int skippedFull /* 已满 10 个 */, boolean applied) {}

// GET /biz/member-reach/tasks/{taskNo}
record ReachTaskVO(String taskNo, String scene, String title, String audienceDesc,
                   long sentAt, long statsUntil, boolean settled,
                   int sent, int skipped, int opened, int ordered, long orderedAmountMinor,
                   List<OrderedMember> orderedMembers /* 最多 50 */, int notOpened) {}
```

`MemberQuery` 追加两个可空字段（存量 `rule_json` 反序列化后为 null，行为不变）：

```java
String reachTaskNo, String reachOutcome   // ReachOutcome：限定「某次触达里 已下单 / 没来 …」的人
```

`MemberVO` 追加 `List<TagBrief> tags`（名单卡片第二行，m01；每页一次批量查询，不逐行）；
`MemberDetailVO` 追加 `LastReach lastReach`（m04「最近触达」）；
`MemberStatsVO` 追加 `Long levelComputedAt`（m01「今天 03:00 重算」）。

#### 库表（一个迁移，号实现时取当时最大号 +1；当前最大 V337）

```sql
-- 1) 触达批次头：「发出去的」列表与效果页读它；计数随回写原子累加
CREATE TABLE IF NOT EXISTS mbr_reach_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_no VARCHAR(64) NOT NULL,                  -- = mbr_reach_log.task_no（已有，BizKey.REACH）
    entity_no VARCHAR(64) NOT NULL,
    scene VARCHAR(24) NOT NULL,                    -- NOTICE / WAKEUP / COUPON
    title VARCHAR(64) NOT NULL,
    body VARCHAR(255) DEFAULT NULL,
    audience_json TEXT NOT NULL,                   -- 发送那一刻的受众项快照
    audience_desc VARCHAR(128) NOT NULL,           -- 「南门店沉睡老客」，列表不回解析
    matched_count INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    skip_detail VARCHAR(255) DEFAULT NULL,         -- LEAD:2,TOO_SOON:3 —— 与 pmt_coupon_issue 同格式
    opened_count INT NOT NULL DEFAULT 0,
    ordered_count INT NOT NULL DEFAULT 0,
    ordered_amount_minor BIGINT NOT NULL DEFAULT 0,
    sent_at BIGINT NOT NULL,
    stats_until BIGINT NOT NULL,                   -- sent_at + 归因窗口；过了即「已统计」
    operator_no VARCHAR(64) DEFAULT NULL,
    -- 审计七列（tenant_no … deleted）同其他 mbr_* 表
    PRIMARY KEY (id),
    UNIQUE KEY uk_mbr_reach_task_no (task_no),
    KEY idx_mbr_reach_task_entity (entity_no, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ... COMMENT='触达批次：发出去的每一次';   -- 收尾单行；排序规则按 SQL 方言闸门的要求写

-- 2) 下单归因：金额与幂等键（同一单不重复计）
ALTER TABLE mbr_reach_log
    ADD COLUMN ordered_amount_minor BIGINT DEFAULT NULL,
    ADD COLUMN ordered_ref VARCHAR(64) DEFAULT NULL;

-- 3) 每日重算按主体扫订单来源
ALTER TABLE mbr_member_source ADD KEY idx_mbr_source_order (entity_no, source_type, occurred_at);

-- 4) 活动人群快照（AC-9）；为空 = 存量行，按人群号当场算（今天的行为）
ALTER TABLE pmt_activity_audience ADD COLUMN rule_snapshot TEXT DEFAULT NULL;

-- 5) 发券受众快照（AC-11）；segment_no 保留：单个人群时照填，存量报表不断
ALTER TABLE pmt_coupon_issue ADD COLUMN audience_json TEXT DEFAULT NULL;
```

实体同步加字段：`MbrReachTask`（新）· `MbrReachLog` +2 · `PmtActivityAudience` +1 · `PmtCouponIssue` +1（`entity-alignment` 守卫会查）。
**`schema-lineage` 预计要登记** `task_no`：`mbr_reach_task` 为主、`mbr_reach_log` 指向它（KEY_OWNERS）；与 `notify_push_task` 若同名则进 NAME_COLLISIONS——以闸门实际输出为准。

#### 配置项（`sys_setting`，代码里只有 key 与默认值，集中在 `MemberPolicyKeys`）

| key | 默认 | 谁读 |
|---|---|---|
| `member.level.policy` | —（JSON：sleepDays / loyalD90Orders / regularD90Orders；不存在时用 `LevelPolicy.DEFAULT` 60 / 6 / 2） | `LevelPolicy`，写法同 `inventory.policy` |
| `member.level.last-run` | —（JSON：at / changed / newlySleeping / tookMs） | 重算任务写，m01 / m21 / o02 读 |
| `member.reach.attribution-days` | 7 | 回写与 `stats_until` |
| `shop.job.member-level-recompute.cron`（yml） | `0 0 3 * * *` | 任务调度，环境变量可覆盖 |

#### 权限码

**不新增**。看口径随 `member:member:read`；改口径复用 `system:param:update`（平台参数），
与 `inventory.policy`、代客下单限额同一把钥匙。登记：`scripts/perm-endpoint-map.mjs` 一条规则 + 重生成角色×端点矩阵。

#### i18n

| 端 | 新增 | 说明 |
|---|---|---|
| b-app | 约 45 条（`audience.*` 选人面板 · `memberTag.usage.*` · `batchTag.*` · `reachTask.*` · `memberDetail.lastReach` · `members.levelComputedAt`） | 三语同步；动态键至少两段前缀 |
| 后端 ErrorCode | `MEMBER_AUDIENCE_REQUIRED` · `MEMBER_AUDIENCE_EMPTY` · `MEMBER_TAG_BATCH_TOO_LARGE` | `BackendI18nParityTest` 三语 + 占位符两向 |
| ops-web | `app/members/copy.ts` 约 15 条 | — |
| c-app | 0 | 只改跳转参数 |

### 2.3 关键实现

**① 每日分层重算**（AC-5/6/7）

```java
// MemberLevelService#recompute(long now) —— 按主体分批，每批 500
for (entityNo : 有会员的主体) {
    Map<memberNo, Integer> d90 = SELECT member_no, COUNT(*) FROM mbr_member_source
        WHERE entity_no=? AND source_type='ORDER' AND occurred_at >= now-90d GROUP BY member_no;  // idx_mbr_source_order
    for (m : 该主体会员，id 游标分页) {
        int n = d90.getOrDefault(m.memberNo, 0);
        String lv = policy.levelOf(n, m.lastOrderAt, now);
        if (n != m.d90OrderCount || !lv.equals(m.level)) update(m.id, n, lv);   // 只改真变了的 → AC-6
    }
    同法重算 mbr_member_store（按 store_no 分组）;
}
settingPort.put("member.level.last-run", {at, changed, newlySleeping, tookMs});
```

- 数据只来自会员域自己的来源台账（每笔支付一行 `ORDER`，`ref_no` 幂等），**不跨域查订单**。
- `levelOf` 从 `MemberServiceImpl` 私有静态方法挪进 `LevelPolicy`，下单即时算与每日重算同一份（「先判沉睡」的顺序保留）。
- 不碰 `d90_spent_minor`：全仓库无读者（只写），见 §4 遗留。
- 任务 `MemberLevelRecomputeJob`：`@ConditionalOnProperty(shop.job.enabled)` + `@SchedulerLock(name="member-level-recompute", lockAtMostFor="PT30M")`，与 `order-auto-close` 同一开关与 worker。

**② 受众解析**（AC-4/8/10/11）——`member/service/impl/AudienceResolver`

```
resolve(entityNo, items, scene?):
  items 为空 → 调用方决定（活动=所有人；发券/消息=MEMBER_AUDIENCE_REQUIRED）
  含 NON_MEMBER → countable=false，其余项必须为空（否则 400）
  matched  = ⋃ 每项 → memberNo 集合（ALL=全部 ACTIVE+LEAD；LEVEL/TAG/SOURCE 直查；SEGMENT=当前条件或快照）
  reachable = matched − LEAD − OPT_OUT − 无账号 − (scene 非空时) 频次闸内
  skips     = 四档计数（沿用 MemberReachServiceImpl#sift 的判定，抽成共用方法，不另写）
```

`MemberQueryPort` 增加 `resolve(entityNo, items, scene)` 与 `matchesRule(entityNo, userNo, ruleJson)`；
`resolveSegment` 保留并改为 `resolve(entityNo, [SEGMENT or 预设], null)` 的薄包装——发券旧入参一个版本内仍可用。

**③ 活动人群快照**（AC-9）
发布/保存时，受众项里每个 `SEGMENT` 把**那一刻的** `rule_json` 抄进 `rule_snapshot`。
`audienceHits` 的 SEGMENT 分支：`rule_snapshot != null ? memberPort.matchesRule(entityNo, userNo, snapshot) : me.segmentNos().contains(no)`。
存量活动没有快照，行为不变。`matchesRule` 单人判定（按 memberNo 加条件查一行），不做全量。

**④ 触达批次与跳转**（AC-12）
`MemberReachServiceImpl#send` 先写 `mbr_reach_task`，再逐人写 `mbr_reach_log`；
推送链接由 `/pages/index/index` 改为 `/pages/store/index?merchantNo={entityNo}&reach={reachNo}`（每人各自的 `reachNo`）。

**⑤ 进店回写**（「来了」）
`/mp/store/{merchantNo}/enter` 收到 `reachNo` → `MemberEventPort#onReachOpened(reachNo, userNo, now)`：
校验这条 `reach_log` 的会员确实是当前买家（member → person → user，防伪造）、`opened_at` 为空、在窗口内 →
`UPDATE mbr_reach_log SET opened_at=? WHERE reach_no=? AND opened_at IS NULL`，**影响 1 行时**才
`UPDATE mbr_reach_task SET opened_count=opened_count+1`。重复进店不重复计。

**⑥ 下单回写**（「成单」，AC-13/14）
`MemberServiceImpl#doOnOrderPaid` 末尾（已在 `executeWithoutScope` 内、已按 `ref_no` 幂等）：

```sql
SELECT id, task_no FROM mbr_reach_log
 WHERE entity_no=? AND member_no=? AND sent_at >= :paidAt - :window AND sent_at <= :paidAt
 ORDER BY sent_at DESC LIMIT 1;                      -- 最近一次 → AC-14；窗口 → AC-13
UPDATE mbr_reach_log SET ordered_at=?, ordered_amount_minor=?, ordered_ref=?
 WHERE id=? AND ordered_at IS NULL;                   -- 只记触达后的第一单
-- 影响 1 行 → mbr_reach_task.ordered_count+1, ordered_amount_minor+=amount
```

**⑦ 引用查询**（m08 / m09 / m11，AC-16）
活动与发券批次在 promotion 域，会员域不能直接读：新 SPI `spi/promotion/AudienceRefPort`，promotion 实现：

```java
List<AudienceRef> activitiesUsing(String entityNo, String type, String value);   // 进行中/未开始
List<AudienceRef> couponIssuesUsing(String entityNo, String segmentNo);
int retargetTag(String entityNo, String fromTagNo, String toTagNo);              // 合并时改指活动受众
```

人群对标签的引用（`rule_json.tagNos`）在会员域内自查，合并时一并改写。

### 2.4 模块设计（实现完用 `git diff --stat` 逐行对这张表）

| 动作 | 路径（前缀省略 `backend/shop-*/src/main/java/ai/neargo/shop/`） | 说明 |
|---|---|---|
| 新增 | `shop-base…/spi/member/AudienceItem.java` 等（或并入 `MemberQueryPort` 内部 record） | 受众值对象 |
| 修改 | `shop-base…/spi/member/MemberQueryPort.java` | `resolve` · `matchesRule` |
| 修改 | `shop-base…/spi/member/MemberEventPort.java` | `onReachOpened` |
| 新增 | `shop-base…/spi/promotion/AudienceRefPort.java` | 引用查询 |
| 新增 | `member/service/MemberLevelService.java` + `impl/MemberLevelServiceImpl.java` | 每日重算 |
| 新增 | `member/service/impl/LevelPolicy.java` · `MemberPolicyKeys.java` | 阈值与 key |
| 新增 | `member/service/impl/AudienceResolver.java` | 受众解析（抽自 `MemberReachServiceImpl#sift`） |
| 新增 | `member/entity/MbrReachTask.java` · mapper 并入 `MemberMappers` | 批次头 |
| 修改 | `member/entity/MbrReachLog.java` | +2 字段 |
| 修改 | `member/service/impl/MemberServiceImpl.java` | `levelOf` 移出；`onOrderPaid` 归因；VO 加标签/最近触达 |
| 修改 | `member/service/impl/MemberTagServiceImpl.java` | `batch` · `usage` · 合并时改写人群与活动 |
| 修改 | `member/service/impl/MemberSegmentServiceImpl.java` | `detail`；`MemberQuery` 两个新条件 |
| 修改 | `member/service/impl/MemberReachServiceImpl.java` | 写批次头；链接；`tasks` / `task` 查询 |
| 修改 | `member/port/MemberQueryPortImpl.java` · `MemberEventPortImpl.java` | 实现新方法 |
| 修改 | `member/dto/MemberVOs.java` | 新增/扩展 VO |
| 修改 | `member/service/impl/OpsMemberServiceImpl.java` | reach-stats 扩列（批 C） |
| 修改 | `promotion/service/impl/ActivityPricingServiceImpl.java` | SEGMENT 快照分支 |
| 修改 | `promotion/service/impl/ActivityServiceImpl.java` | 落快照；发布校验覆盖人数 |
| 修改 | `promotion/service/impl/PmtCouponServiceImpl.java` | `issue` 接受受众项；VO 加已用 |
| 修改 | `promotion/entity/PmtActivityAudience.java` · `PmtCouponIssue.java` | +1 字段各 |
| 新增 | `promotion/port/AudienceRefPortImpl.java` | 引用查询实现 |
| 修改 | `shop-app…/portal/biz/BizMemberController.java` | 6 个新端点、2 个入参扩展 |
| 修改 | `shop-app…/portal/biz/`（发券、活动所在 Controller） | 入参扩展 |
| 修改 | `shop-app…/portal/mp/`（`/mp/store/{no}/enter` 所在 Controller） | `EnterReq.reachNo` |
| 新增 | `shop-app…/portal/ops/OpsMemberLevelPolicyController.java` | level-policy 两个端点（照 `OpsInventoryPolicyController`） |
| 新增 | `shop-app…/job/MemberLevelRecomputeJob.java` | 定时任务 |
| 修改 | `shop-app/src/main/resources/application.yml` | cron 默认值 |
| 新增 | `shop-app/src/main/resources/db/migration/V3xx__member_audience_loop.sql` | §2.2 库表 |
| — | ~~权限种子 / 运营端菜单迁移~~ | 不需要：复用现有码，面板挂在现有「触达健康度」tab |
| 修改 | `common…/ErrorCode.java` + 三语 `messages*.properties` | 3 个码 |
| 新增 | `b-app/src/components/biz/audience-picker.vue` | 选人面板（m12/m13），三页共用 |
| 新增 | `b-app/src/components/biz/batch-tag-sheet.vue` | 批量打标（m06） |
| 新增 | `b-app/src/pages/member-tag/index.vue` | 标签详情（m08/m09） |
| 新增 | `b-app/src/pages/member-segment/index.vue` | 人群详情（m11） |
| 新增 | `b-app/src/pages/reach-tasks/index.vue` · `reach-task/index.vue` | 发出去的 / 效果（m19/m20） |
| 修改 | `b-app/src/pages/customers/index.vue` | m01–m03：标签行、重算时间、「对这批人」 |
| 修改 | `b-app/src/pages/member-detail/index.vue` | m04/m05 |
| 修改 | `b-app/src/pages/member-tags/index.vue` · `member-segments/index.vue` · `member-settings/index.vue` | m07 · m10 · m21 |
| 修改 | `b-app/src/pages/activity-edit/index.vue` · `coupon-send/index.vue` · `member-reach/index.vue` · `marketing/index.vue` | m13–m18 · s01 入口 |
| 修改 | `b-app/src/api/{contract,endpoints,http,mock}.ts` · `scripts/gen-openapi.mjs` · `pages.json` · `shared/nav.ts` · 三语 locale | 契约与登记 |
| 修改 | `c-app/src/pages/store/index.vue`（及 API 层） | 把 `reach` 查询参数带给 enter |
| 修改 | `ops-web/app/members/*` · `ops-web/lib/{types,api}/…member*` · `lib/nav.ts` | o01 扩列、o02 新 tab |
| 修改 | 生成物：三份 openapi · API 清单/详情 · 数据库表清单 · ER 图 · 后端分层清单 · 权限矩阵 · ui-catalog | 跑生成器 |

---

## §3 选型

| 问题 | 方案 | 结论 |
|---|---|---|
| 分层怎么保持新鲜 | A 凌晨全量重算（保留下单即时算） | ✅ 采用：实现简单、`mbr_member` 量级小（千级/主体），结果可审计（last-run） |
| | B 读时懒算（查列表时按 `last_order_at` 现判） | ❌ 筛选、人群、受众、算价四处都要各判一遍，口径必然分叉；且 `d90` 仍然不会降 |
| | C 下单 + 退款事件增量维护 d90 | ❌ 「时间流逝」本身就会改变分层，没有事件能触发它 |
| d90 的数据来源 | A 会员域自己的来源台账（每笔支付一行） | ✅ 采用：不跨域；台账本就按 `ref_no` 幂等 |
| | B 新 SPI 向交易域要近 90 天单数 | ❌ 每日一次对订单大表做全主体聚合，且多一条跨域依赖 |
| 活动引用人群 | A 发布时存条件快照 | ✅ 采用（待拍板 #2）：符合「进行中不能改规则」；存量无快照行为不变 |
| | B 存引用，改人群时提示影响 | ❌ 改人群即改活动；已下单按旧受众算过价，同一活动出现两种人群 |
| 「发出去的」列表 | A 新表 `mbr_reach_task` 做批次头 | ✅ 采用：计数随回写原子累加，列表不扫明细 |
| | B 每次按 `task_no` 聚合 `mbr_reach_log` | ❌ 列表页 N 次聚合；标题、受众描述无处存 |
| 效果「来了」 | A 推送链接带 `reachNo`，进店上报时回写 | ✅ 采用：复用现有 enter 上报；逐人精确 |
| | B 推送通道的点击回执 | ❌ 个推回执不稳定且只覆盖 App；小程序没有 |
| 券的效果口径 | 发出 → 已用 → 用券金额（来自 `pmt_user_coupon`） | ✅ 券不推送，没有「来了」；已用数随券有效期累计，不套 7 天窗口 |

不可逆决策：无（不换存储、不拆服务、迁移只加列加表）。**不开 ADR**；四条待拍板记在 §8。

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 首次重算一次性把大量会员改成沉睡 | 商家看到沉睡数暴涨，以为出错 | o02 显示「其中新变沉睡 N 人」；上线公告说明；任务第一次在低峰手动触发并观察 |
| 重算任务在生产没被调起（`shop.job.enabled` 只在 worker 那一半） | 分层照旧不动，零报错 | 上线后查 `ai-shop-job` 的 `/internal/job/declarations` 里有 `member-level-recompute`，并看 `member.level.last-run` 次日有值——**这是验收项，不是可选** |
| `onOrderPaid` 多一次 UPDATE 拖慢支付回调 | 回调超时重发 | 归因走索引 `idx_mbr_reach_gate`（entity, member, …）；UPDATE 按主键；失败只打 WARN 不抛（归因不能挡住入会） |
| 伪造 `reachNo` 刷「来了」 | 效果数虚高 | 回写前校验该 `reach_log` 的会员 = 当前登录买家 |
| 发券入参从 `segmentNo` 改 `audiences` | 旧 App 发不出券 | 一个版本内两字段都收；`segmentNo` 非空时转成单项 |
| `judge()` 逐个人群判定（≤50），加快照后多一次判定 | 算价变慢 | 只有带快照的 SEGMENT 行才走 `matchesRule`；单人单条件查询 |
| 合并标签改写活动受众 | 进行中活动的受众被改 | 合并本就是「同义词归并」，受众语义不变；`mbr_tag_merge_log` 留痕，确认页列出改指数量（m09） |
| 带域表直查读写皆哑 | 回写静默 0 行 | 全部回写在 `executeWithoutScope` 内；测试断言影响行数（仓库记忆「B端直查带域表读写皆哑」） |

**遗留（本次不做，已知）**：`pmt_coupon_issue.rule_snapshot`（V231 建表时就有，注释「发放当时的人群条件快照」）**实体里没有这个字段、从没人写过** —— 批 B 另加的 `audience_json` 存的是受众项，两列并存；按人群发券时把条件快照写进它，就兑现了它原本的用途（2026-09-19 上线核对时发现）。`d90_spent_minor` 只写不读、不重算；退款不减会员单数与分层；发券不走频次闸（券不推送）。

---

## §5 对账三 · 实现 → 需求（测试）

测试均为 `backend/shop-app/src/test/java/…/scenario/` 下的场景测试（真实库 H2 + Flyway），前端为 vitest / vue-tsc。
「消融」列是**必须做**的：注掉指定那一行，对应测试要变红。

| AC | 测试方法 | 消融验证（注掉 → 必须红） |
|---|---|---|
| AC-1 | `MemberAudienceFlowTest#tagThenFilterFindsMember` | `MemberTagServiceImpl` 写关系行那一句 |
| AC-2 | `…#batchTagPreviewCountsOnlyChanges` | `alreadyInState` 的排除条件 |
| AC-3 | `…#batchTagSkipsMembersAtTenTags` · `…#fiftyFirstTagRejected` | 上限判断 |
| AC-4 | `…#leadCanBeTaggedButNeverReachable` | `AudienceResolver` 过滤 LEAD 那一行 |
| AC-5 | `MemberLevelRecomputeTest#idleRegularBecomesSleepingNextDay` · `…#raisingSleepDaysRestoresLevel` | `recompute` 里 `levelOf` 调用改回旧 `level` |
| AC-6 | `…#recomputeTwiceChangesNoRow`（断言 `updated_at` 与 `version` 全不变） | 去掉「只改真变了的」判断 |
| AC-7 | `ArchitectureTest#memberLevelServiceMustNotTouchReach`（新 ArchUnit 规则）+ `…#recomputeWritesNoReachLog` | 在 `recompute` 里调一次 push（规则应红） |
| AC-8 | `ActivityAudienceFlowTest#tagAudienceGatesPricing` | `audienceHits` TAG 分支返回 true |
| AC-9 | `…#segmentAudienceUsesSnapshotAfterRuleChange` | 落快照那一句 |
| AC-10 | `…#publishRejectedWhenAudienceEmpty` | `publish` 的覆盖人数校验 |
| AC-11 | `CouponIssueAudienceTest#issueByTagSkipsLeads` · `…#legacySegmentNoStillIssues` | `issue` 的受众转换 |
| AC-12 | `ReachEffectFlowTest#sentOpenedOrderedCounts`（25 / 3 / 2） | `onReachOpened` 的 UPDATE、`onOrderPaid` 的归因各消一次 |
| AC-13 | `…#orderOnDayEightNotAttributed` | 窗口下界条件 |
| AC-14 | `…#orderAttributedToLatestReachOnly` | `ORDER BY sent_at DESC` 改为 ASC |
| AC-15 | `OpsMemberReachStatsTest#statsExposeCountsNotTagNames`（断言 JSON 不含任何标签名/规则字段） | VO 里加回 tag 名 |
| AC-16 | `MemberAudienceFlowTest#tagUsageListsActiveActivity` | `AudienceRefPortImpl#activitiesUsing` |
| AC-17 | `ReachEffectFlowTest#saveNotOpenedAsSegment` | `MemberQuery.reachOutcome` 的过滤 |
| 伪造 | `ReachEffectFlowTest#foreignReachNoIgnored` | 买家校验那一句 |
| 前端 | `b-app` `audience-picker` 单测：多选取或文案、`NON_MEMBER` 互斥；`vue-tsc --noEmit` | — |

---

## §6 对账二 · 设计 → 实现（实现完再填）

```
[实现后粘贴 git diff --stat]
```

### 批 A 实现记录（2026-09-19）

| 与设计的差异 | 原因 |
|---|---|
| 口径三个 key 合成一个 JSON 键 `member.level.policy` | 照 `inventory.policy` 的现成写法：一次保存、一次审计、不会出现「改了两个没改第三个」的中间态 |
| 不新增权限码 `member:level:update`，复用 `system:param:update`；不加运营端菜单叶子 | 同类参数（库存对差轮数、代客下单限额）都用这把钥匙；新码要走权限种子、冻结码、菜单库三处登记，换来的只是一个同义的名字 |
| 运营端 o02 不是独立 tab，而是「触达健康度」tab 顶部的一张卡 | 同上，免菜单叶子；它回答的是「商家那边的沉睡是怎么算的」，与触达同一件事的上游 |
| B 端口径经 `GET /biz/member-settings` 带出（`MemberSettingVO` +4 字段），不另开端点 | 会员设置页本来就读它 |
| 迁移 V338 只加索引，不落口径种子 | 默认值在 `LevelPolicy.DEFAULT`，与此前写死的 60 / 6 / 2 逐字一致，不配置时行为不变 |
| 新表按 V332 写法不带 ENGINE/排序规则 | 生产主库已是 MySQL 9.7，`utf8mb4_uca1400_ai_ci` 在那边建不起来 |

测试与消融（`MemberLevelRecomputeTest` 6 条 · `ArchitectureTest#memberLevelServiceMustNotTouchReach` · `MemberEnrollFlowTest` 9 条回归，31/31 绿）：

| 消融 | 变红的 |
|---|---|
| 重算时 `level = m.getLevel()`（不按口径算） | `idleRegularBecomesSleepingNextDay` · `raisingSleepDaysRestoresLevel` · `d90DecaysWhenOrdersLeaveWindow` |
| 去掉「没变就不写」 | `recomputeTwiceChangesNoRow` |
| `MemberLevelServiceImpl` 加一个 `UserPushPort` 字段 | `memberLevelServiceMustNotTouchReach` |

### 批 B 实现记录（2026-09-19）

| 与设计的差异 | 原因 |
|---|---|
| 试算端点 `/biz/members/audience-preview`（设计写 `/biz/member-audience/preview`） | `BizMemberController` 在「一个控制器多个资源」棘轮名单上，新开资源族会让它更胖；挂在已有的 `members` 下 |
| 引用查询 SPI 放在 `spi/marketing/AudienceRefPort`（设计写 `spi/promotion`） | `spi` 下没有 promotion 子包，营销域的 Port 都在 marketing；新开子包要过「顶层包登记」闸 |
| 编排放进新服务 `MemberAudienceService`（设计写在 `MemberTagService`） | 会员 → 标签 → 人群 → 会员会绕出循环依赖；标签服务只加纯标签层面的 `batch` |
| ErrorCode 只加两个（`MEMBER_AUDIENCE_REQUIRED` 70065 · `MEMBER_AUDIENCE_EMPTY` 70066），没加 `MEMBER_TAG_BATCH_TOO_LARGE` | 批量打标不设上限：按条件圈人时后端分块处理，超限者按人跳过计数 |
| 发放记录 `CouponIssueVO` 增加 `audiences` | 设计漏了：按标签发的批次没有人群号，发放记录页会显示成「全部会员」 |
| 名单「对这批人」→ 发券 / 发消息：单个分层或单个标签直接变成一个受众项，否则先存成人群 | 筛选是「且」、受众项之间是「或」—— 把「沉睡 且 爱囤货」拆成两项会发给「沉睡 或 爱囤货」 |
| 受众在「名单 → 券列表 → 发放页」之间用页面内的待带入状态传（10 分钟过期），不走查询串 | 中间隔两跳，查询串每一跳都要转交，漏一跳就静默丢成「全部会员」 |
| 页面：新增 `member-tag`、`member-segment` 两页 + `biz-audience-picker`、`biz-batch-tag-sheet` 两个组件 | 与 §2.4 一致；原型登记表 m08 / m11 挂上路由 |

**顺手修掉的现成缺陷**（都在这条线的路径上，不修批 B 走不通）：

| 缺陷 | 症状 | 处理 |
|---|---|---|
| `pmt_activity_audience` 唯一键不含 `deleted`，保存时逻辑删再插 | 编辑一个带受众的活动、受众不变 → DuplicateKey（商品表 2026-09-18 已修过同一个坑，受众表当时漏了） | 物理删 `hardDeleteByActivity` |
| `mbr_member_tag` 同上 | 去掉一个标签再打回去 → DuplicateKey | 物理删 `hardDelete` / `hardDeleteById` |
| 合并标签只改关系行 | 引用源标签的活动受众与人群条件从此一个人都命中不了，活动照样「进行中」 | 合并时一并改指（`AudienceRefPort#retargetTag` + `MemberSegmentService#retargetTag`） |
| `MemberTagService#tag` 不校验会员归属 | 传别家会员号也能写进一条关系行 | 校验会员须属本店 |
| 发消息筛人不挡「被拉黑」 | 发券挡了、发消息没挡 | 统一进 `AudienceResolver`，多一档跳过原因 `BLOCKED` |
| 发消息 mock 不论选哪个人群都按全部会员算 | 演示时「选沉睡 24 人、能发 118」 | mock 按受众项筛 |
| 会员名单卡片 `.row__main .sh-muted { display:block }` 挂空（那一层没有这个类） | 「¥285.50下过单」几行挤成一行 | 补类名 |

测试：`MemberAudienceFlowTest` 12 条（AC-1/2/3/4/8/9/10/11/16 + 两个撞键 + 别家会员号），
`ActivityAudienceFlowTest` 一条改为先造会员再建活动（AC-10 的有意行为变化）。消融七处各自变红：

| 消融 | 变红的 |
|---|---|
| 人群快照不落 | `segmentAudienceUsesSnapshotAfterRuleChange` |
| 进行中的活动重抄快照 | 同上 |
| 受众改回逻辑删 | `resaveActivityWithSameAudience` · `segmentAudienceUsesSnapshotAfterRuleChange` |
| 去标签改回逻辑删 | `tagThenFilterFindsMember` |
| 去掉 0 人校验 | `publishRejectedWhenAudienceEmpty` |
| 合并不改指活动受众 | `tagUsageAndMergeRetargets` |
| 线索不跳过 | `leadCanBeTaggedButNeverReachable` |

B 端 H5 mock 实测：名单卡片标签行、「对这 2 人…」四个去处、批量打标试算「其中 1 人已有，实际新增 1 人」与确认、
活动选人面板实时「覆盖 2 人」与「非本店会员」互斥、标签详情。发券页、发消息页、人群详情过了 `vue-tsc`，未在浏览器里点。

### 批 C 实现记录（2026-09-19，未上线）

| 与设计的差异 | 原因 |
|---|---|
| 迁移号 V341（设计写「当时最大号 +1」，写时 V340 已被商品仅活动可售占用） | 并行会话撞号，自己让路 |
| 进店回写走新端点 `POST /mp/member-reach/{reachNo}/opened`（设计写 `/mp/store/{no}/enter` 带 `reachNo`） | `enter` 顺带上报一次店铺渠道归因；点老店推送回来的是商家自己的会员，挂在那儿会被记成一次拉新。且 c-app 从没调过 `enter` |
| 列表 / 详情路径为 `/biz/member-reach/task(/{taskNo})`（设计写 `tasks`） | `/biz` 单数约定（`api-path-naming` 闸门） |
| 批次头描述 `audience_desc` 由端上选人面板那句话传入，服务端只在旧版不传时用受众项兜底拼 | 标签名、分层名、来源的译法都在端上词条里；服务端拼只能拼出代码 |
| 效果页「未进店」人数按明细行数（`opened_at IS NULL`），不按「发出 − 进店」 | 推送失败的人也有明细行（先记后推），「未进店的存为人群」按明细筛 —— 两个数同一把尺，按钮上的人数才对得上存出来的人群 |
| 运营端触达健康度改为**按跳过率倒序、同率按退订率**（此前只按退订率） | 原型 o01；跳过率高 = 在反复给同一批人发，是运营找商家谈话的另一半信号 |
| 触达场景加具名类型 `ReachScene`，`mbr_reach_log.scene` 与 `mbr_reach_task.scene` 按字段对账 | 新表的场景列被取值域闸门点名；顺带把明细表那一条从「未判定」清单里判掉（清单减一行） |
| 会员详情 `MemberDetailVO.lastReach` 只带场景与三个时刻，不带标题 | 原型 m04 那一行是「09-12 唤回 · 已下单」，点进去是效果页 |
| 券批次「已用金额」= 这批领券人在发放之后用这张券省下的钱 | 核销记录上没有领券号；同一人从两批各领一张同样的券时两批都算，少数且两批都确实带来了这次使用 |

测试：`ReachEffectFlowTest` 8 条（AC-12/13/14/15/17、推送链接、伪造 reachNo、会员详情最近触达）。
消融五处，四处各自变红：

| 消融 | 变红的 |
|---|---|
| 进店不核本人 | `forgedReachNoIsNotCounted` |
| 归因取最早一次（`ORDER BY sent_at ASC`） | `orderAttributedToLatestReachOnly` |
| 去掉窗口下界 | `orderOnDayEightNotAttributed` |
| 人群条件不按 `reachOutcome` 筛 | `saveNotOpenedAsSegment` |
| 进店 UPDATE 去掉 `opened_at IS NULL` | **不变红** —— 顺序执行时前面的读已挡住重复；这一条守的是两次并发进店，单线程测试够不着 |

B 端 H5 mock 实测：营销首页「发出去的」→ 列表（已统计 / 统计中两档）→ 效果页（三条同尺进度、下单名单、未进店人数）→
「未进店的存为人群」→ 人群详情命中 3 = 未进店 3、条件行显示「来自一次触达 · 未进店的人」；会员详情「最近触达 · 已下单」。
实测时抓到并修掉一处：进度条与底部操作行同用 `.bar` 类，操作行被压成 6px、按钮被裁掉。
c-app 店铺页回写与真机推送 → 进店 → 下单的闭环**未验**（要真机与线上推送通道）。

### 批 C 上线后补丁 · 发出数虚高（2026-09-19）

**现象**：准备真机走「发消息 → 点推送进店 → 下单」时查生产 `notify_push_token`：只有 1 行，是一台商家员工的安卓机，
**买家（`USER`）0 行**。买家都在微信小程序里，而会员消息只走个推（原生 App）。与此同时 `PushSender.send`
在零设备时什么都不做、也不抛，`UserPushPortImpl.pushToUser` 于是恒回 `true` ——
**商家看到「已发出 N 条」，一条都没到**；批 C 的「发出去的」把这个假数字又放大了一遍。
这不是批 C 引入的（P7 起就这样），但批 C 让它可见了。

| 改动 | 说明 |
|---|---|
| `PushSender#notify` 返回交给通道成功的设备数；`UserPushPortImpl#pushToUser` 改为 `> 0` 才算发出 | 零设备或全失败 = 没发出 |
| `UserPushPort#withDevice(userNos)` + `PushSender#withDevice`：一次查这批人谁有设备 | 试算用，不逐人查 |
| `AudienceResolver`：发消息场景（`scene != null`）里其余条件都过了、但没设备的人，记跳过原因 **`NO_CHANNEL`** | 发券、活动不经推送，不判这一档 |
| b-app 词条 `reach.reason.NO_CHANNEL`「{n} 人只用小程序，暂时收不到消息」；mock 按会员号末位演示一部分人 | 试算页、选人面板、发送结果三处共用 |

测试：`MemberReachFlowTest#noDeviceIsSkippedNotSent`（2 人 1 有设备 → 试算 2/1、跳过 NO_CHANNEL、发出 1）；
既有用例的买家夹具补登记设备。消融「试算不判设备」→ 该用例变红。
`pushToUser` 的 `> 0` 没有单独用例：试算已把零设备的人筛掉，它守的是「试算后设备被注销 / 全部推送失败」那条窄路。

**上线后的真实效果**：线上所有买家都会落在 `NO_CHANNEL`，发消息页显示「能收到 0 人」，发送按钮不可点。
这是真话。让买家真的收得到，要接微信小程序订阅消息 —— 见 [PRD-会员消息-小程序订阅消息](../requirements/PRD-会员消息-小程序订阅消息.md)。

### 批 D · 站内信承接会员消息（[PRD-会员消息送达小程序买家](../requirements/PRD-会员消息-小程序订阅消息.md) 批 1，2026-09-19 拍板先做）

**要解决的**：批 C 补丁之后发消息页如实显示「能收到 0 人」—— 买家都在小程序、没有推送设备。
小程序里已经有消息中心（`/mp/message`，「营销」分栏，点开按 `link` 跳转），会员消息应当进这里。

**口径变化（契约）**

| 项 | 之前 | 之后 |
|---|---|---|
| 「能收到」`reachable` | 过了线索 / 拉黑 / 退订 / 未注册 / 频次，**且有推送设备** | 过了前五道即可 —— 消息一定进他的小程序消息列表 |
| 跳过原因 `NO_CHANNEL` | 有（批 C 补丁加的） | **去掉**：没有设备不再是「收不到」，只是「不推送」 |
| 新增 `pushable`（试算）/ `pushed`（结果、批次头、效果页） | — | 其中有推送设备的 / 实际推送成功的 |
| 「发出」`sent` | 推送成功数 | **进了消息列表的人数**（被平台营销日上限挡下的不算） |
| 迁移 V342 | — | `mbr_reach_task.pushed_count INT NOT NULL DEFAULT 0` |

**投递**：新端口 `spi/notify/UserInboxPort#deliverMarketing(userNo, title, body, link, dedupKey)`，由消息域实现：
写一条 `MARKETING` 类站内信，**遵守平台营销日上限**（`notify.quota.dailyPerUser`，默认 5），`dedupKey = reachNo` 防重；
**不套「同模板最小间隔」**—— 那条按模板号算，所有商家共用一个触达模板的话，一家店发了会把别家挡 24 小时。
商家这一侧的频次闸（按场景 3 / 7 / 14 天）照旧在 `AudienceResolver` 里。

**顺序**：逐人「记触达明细 → 投站内信 → 有设备再推送」。站内信被日上限挡下 → 该人不计入发出（明细照记，频次闸仍然算他被打扰过一次的尝试 —— 宁可少发）。

**界面**：发消息页「能收到 N 人 · 其中 M 人会收到推送，其余在小程序的消息列表里看到」；
发送结果「已发出 N 条 · 推送 M」；效果页发出条下加一行同样的拆分。

**测试**：无设备的买家 → 试算能收到、pushable=0、发送后他的站内信有这一条且 link 带 `reach=`；
有设备的 → 站内信 + 推送都有；日上限满的 → 不计入发出；重复发送同一 reachNo 不生成两条站内信。

### 偏差说明（设计阶段已知，写在前面）

| 与谁 | 偏差 | 处理 |
|---|---|---|
| 原型 m02 | 图注写「同组取或」；代码是标签**取交集**，且有理由 | 保留交集；原型改为「标签同时满足 · 各条件同时满足」 |
| 原型 m12 | 面板多选取或，与筛选口径不同，界面上未区分 | 面板组标题加「满足任一即可」（§2.1 表） |
| 原型 m19 | 券卡片画了「11 来了」 | 券不推送，无「来了」；改为「发出 · 已用 · 金额」 |
| PRD §六 | 写「每店每人 7 天 ≤1 · 唤回 30 天 ≤1」（抄自上游） | 现行是按场景读 `sys_setting`：公告 3、发券通知 7、唤回 14 天；PRD 已更新为引用配置 |
| PRD §四 | 缺 AC-16（引用可见）、AC-17（从效果页回到人群/打标） | 已补进 PRD |

---

## §7 开发计划

三批，与 PRD §九 一致；每批独立可上线，**批 A 不上线，批 B 不开工**（B 的所有数字都建立在分层正确之上）。
工作量按一人日估（含测试与生成物），不含等待拍板。

### 批 A · 分层每日重算（纯修复，无新界面）· 约 3 人日

| # | 任务 | 依赖 | 产出 / 验收 |
|---|---|---|---|
| A1 | `LevelPolicy` + `MemberPolicyKeys`，`levelOf` 移出，`onOrderPaid` 改用它 | — | 现有会员测试全绿（行为不变的证明） |
| A2 | 迁移（仅 `idx_mbr_source_order`）+ `MemberLevelService#recompute` | A1 | AC-5 / 6 测试 + 消融 |
| A3 | `MemberLevelRecomputeJob` + yml cron + ArchUnit 规则 | A2 | AC-7；`/internal/job/declarations` 本地可见 |
| A4 | `MemberStatsVO.levelComputedAt`；b-app m01「今天 03:00 重算」、m21 只读口径组 | A2 | vue-tsc；i18n 闸门 |
| A5 | 运营端 o02：`GET/POST /ops/members/level-policy`（复用 `system:param:update`），面板挂「触达健康度」tab | A2 | 权限矩阵闸门；ops-web tsc |
| A6 | 上线：低峰手动触发一次，记录「新变沉睡 N 人」；次日核 `last-run` | A1–A5 | **生产验收**：两项都有值才算完 |

### 批 B · 打标签 + 选人面板 + 三处接入 · 约 8 人日

| # | 任务 | 依赖 | 产出 / 验收 |
|---|---|---|---|
| B1 | SPI：`AudienceItem` / `resolve` / `matchesRule`；`AudienceResolver`（抽 `sift`） | A | AC-4；`member-reach` 现有测试不变红 |
| B2 | `POST /biz/member-audience/preview` | B1 | 七处 /biz 登记；openapi 生成 |
| B3 | `POST /biz/members/tags/batch`；`MemberVO.tags`；`MemberDetailVO` 标签 | B1 | AC-1 / 2 / 3 |
| B4 | `AudienceRefPort` + `/member-tags/{no}/usage` + `/member-segments/{no}`；合并改写人群与活动 | B1 | AC-16；合并现有测试不变红 |
| B5 | 迁移（`rule_snapshot` · `audience_json`）+ 活动落快照 + 发布校验 + `audienceHits` 分支 | B1 | AC-8 / 9 / 10；**算价回归全量** |
| B6 | 发券 `issue` 受众项（兼容 `segmentNo`）；发消息 `ReachReq.audiences` | B1 | AC-11；旧入参测试 |
| B7 | b-app：`audience-picker` + `batch-tag-sheet` 组件 | B2 B3 | 组件单测；vue-tsc |
| B8 | b-app：m01–m11（customers / member-detail / member-tags / member-tag / member-segments / member-segment） | B3 B4 B7 | 界面清单重跑；孤儿页闸门 |
| B9 | b-app：m13–m18（activity-edit / coupon-send / member-reach） | B5 B6 B7 | H5 mock 走一遍 golden path + 0 人分支 |
| B10 | 三语词条、ErrorCode 三语、生成物全量重跑、pre-push 全绿 | B1–B9 | 14 道闸门 |

### 批 C · 效果回看 + 运营端触达视图 · 约 5 人日

| # | 任务 | 依赖 | 产出 / 验收 |
|---|---|---|---|
| C1 | 迁移（`mbr_reach_task` · reach_log 两列）+ 实体；`send` 写批次头、改推送链接 | B | schema-lineage / entity-alignment 闸门 |
| C2 | `onReachOpened`（enter 带 `reachNo`，含买家校验）；c-app store 页透传参数 | C1 | 伪造测试 |
| C3 | `onOrderPaid` 归因（窗口、最近一次、只记首单） | C1 | AC-12 / 13 / 14 |
| C4 | `GET /biz/member-reach/tasks(/{no})`；`MemberQuery.reachTaskNo/reachOutcome`；券发放 VO 加已用 | C1 | AC-17 |
| C5 | b-app：m19 / m20 两页 + 营销首页「发出去的」入口 + m04「最近触达」 | C4 | 界面清单；真机推送 → 进店 → 下单走通一次 |
| C6 | 运营端 o01：`ReachStatVO` 扩列 | C1 | AC-15 |
| C7 | 生成物、闸门、部署；上线后第 8 天核一次「已统计」的批次数字与订单对得上 | C1–C6 | 生产验收 |

### 里程碑与闸门

| 节点 | 条件 |
|---|---|
| 开工 | §8 四条拍板；原型 m02 / m12 / m19 按 §6 改完 |
| 批 A 完成 | 生产 `member.level.last-run` 连续两天有值 |
| 批 B 完成 | pre-push 14 道全绿；H5 mock 走通「筛选 → 打标 → 存人群 → 活动选人 → 覆盖 0 人被拦」 |
| 批 C 完成 | 真机一次完整闭环：发消息 → 点推送进店 → 下单 → 效果页 1/1/1 → 「下单的打标签」 |
| 每批都做 | §5 该批 AC 的消融；`known-*` 棘轮不变长；TDD §6 贴 diff --stat |

---

## §8 已确认（2026-09-19 用户「确认开工」，四条均按本方案的假设定案）

| # | 问题 | 本方案的假设 | 卡住谁 |
|---|---|---|---|
| 1 | 分层重算：凌晨全量 vs 读时懒算 | 凌晨全量 + 保留下单即时算（§3） | 批 A |
| 2 | 活动引用人群：快照 vs 引用 | 快照（`rule_snapshot`） | B5 |
| 3 | 归因窗口 7 天、取最近一次；是否与 `mkt_attribution` 对齐 | 会员触达单独一套，key `member.reach.attribution-days` | C3 |
| 4 | 「非本店会员」与其他受众项互斥 | 互斥，服务端 400 | B1 |

---

## §9 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-09-19 | 方案确认，§8 四条按假设定案；开始批 A |
| 2026-09-19 | 批 A 上线 `aac9cf6a`：V338 成功、索引在；任务进程已登记 `member-level-recompute`，**初始为停**（生产新任务一律等运营打开）——需运营在「定时任务」打开并手动跑一次，`member.level.last-run` 有值才算批 A 验收完 |
| 2026-09-19 | 开始批 B |
| 2026-09-19 | 批 B 后端随 C 端自营标识一起上线 `45d7bdfb`（部署方为「C端地址管理功能」会话；pre-push 全套绿，后端全量 2051 跑 / 0 红）。本会话复核：本分支提交均为其祖先；V339 success=1；health=200；AudienceResolver / MemberAudienceServiceImpl / AudienceRefPortImpl / MemberLevelRecomputeJob 均在运行中的 jar 里。**B 端界面随下一次打 APK 到店主手里；运营端口径卡（批 A）随下一次 ops-web 部署** |
| 2026-09-19 | 草稿；基于代码盘点（`BizMemberController` · `MemberQueryPortImpl` · `ActivityPricingServiceImpl#audienceHits` · `PmtCouponServiceImpl#issue` · `MemberReachServiceImpl` · V224/V226/V230/V242/V243） |
