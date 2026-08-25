# TDD-会员体系与活动联动

状态：草稿（待确认）
关联需求：[会员体系与活动联动 · 需求](../../requirements/会员体系与活动联动-需求.md)
创建日期：2026-08-24

---

## 1. 需求摘要

把「在某家店买过/扫过/被录入过的人」沉淀成一份带**来源**与**标签**的名单，
商家能按标签或手机号筛出来，把这份名单接到**活动**与**触达**上。
一期不做付费会员、不做店铺积分、不改既有算价逻辑。

## 2. 当前架构分析

### 2.1 已有、可直接复用

| 能力 | 位置 | 复用方式 |
|---|---|---|
| 订单里的商家维度 | `ord_sub_order(user_no, entity_no, traffic_source, 金额)` | 入会事件源 + 存量回填 |
| 客户列表（谁来过/沉默/来源） | `BizDashboardController#/biz/customers` → `MerchantOrderService.customers` · b-app `pages/customers` | **一期直接升级它**，不新建页面 |
| 归因（谁分享的、渠道、窗口） | `mkt_attribution` / `_log` / `_rule` | `SHARE`/`SCAN` 来源与 `sourceDetail` |
| 收藏本店 | `usr_store_favorite` | `FAVORITE` 来源 |
| 商家券与发放 | `mkt_coupon(funder=MERCHANT, entity_no)` / `mkt_user_coupon` / `mkt_coupon_issue(target, target_desc)` | 定向发券 |
| 活动 | `mkt_campaign`（COUPON/FULL_CUT/FLASH/BUY_GIFT）+ 下单算价 `Discounts` | 加受众，不动算价主干 |
| 推送通道与群发任务 | `notify_push_token` / `notify_push_task` / 个推 | 触达执行 |
| 平台积分 | `pts_*`（ADR-006：商家发分即付费） | **不动**，会员不引入第二套积分 |

### 2.2 缺什么

- 没有「会员」这个实体：现在的客户列表是**每次按订单实时聚合**的，无法挂标签、无法记来源明细、无法承载线索态。
- 没有标签表、没有筛选、没有手机号查找。
- 活动没有人群维度；发券没有商家侧入口。
- 没有触达记录与频次闸。

### 2.3 影响范围

- 新增后端域 `member`（`mbr_*`）；`marketing` 域只增受众表与一处受众判断。
- b-app：`pages/customers` 升级为会员（路由保留，避免深链失效）。
- c-app：店铺页会员卡与「接收消息」开关（批次五之前只读展示）。
- 运营端：标签口径与频次上限配置、触达监控。

## 3. 方案设计

### 3.1 方案选型：会员放哪儿

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A. 新建 `member` 域，活动留在 `marketing`，跨域走 spi Port**（推荐） | 关系/身份与价格/规则分开，生命周期不同；下单算价不新增强依赖；符合既有 ArchUnit 域边界 | 多一个域、多一层 Port | ✅ 采用 |
| B. 会员并入 `marketing` | 少一个域 | 算价链路会依赖会员查询；营销域已经是最大的域，继续堆会更难拆 | ❌ |
| C. 会员并入 `user` 域 | 与账号同源 | 会员是**商家与人的关系**，不是账号属性；user 域会被迫认识 entity_no | ❌ |
| D. 不建实体，继续按订单实时聚合 | 零迁移 | 挂不了标签、存不了来源与线索、筛选要全表扫 | ❌ |

> 独立模块的边界（回答「会员/活动是否独立模块」）：
> **后端两个域**（`member` / `marketing`）**，前端两个入口**（会员 / 营销），
> 但**界面互相可达**：会员筛完能直接发券建活动，活动详情能看覆盖多少会员。
> 不合并的理由是生命周期：会员长期存在，活动有起止；合并后活动表会被会员查询拖住。

### 3.2 数据模型

```sql
mbr_member                     -- 一个人 × 一家店 = 一行
  member_no, entity_no, user_no(可空), phone(可空,线索用), 
  status: LEAD | ACTIVE | BLOCKED,
  source, source_detail(JSON: inviterNo/shareCode/campaignNo/operatorNo),
  first_order_at, last_order_at, order_count, total_spent_minor,
  d90_order_count, d90_spent_minor, level(算出来的),
  reach_opt_out(买家关的), claimed_at(线索被认领的时刻),
  UNIQUE(tenant_no, entity_no, user_no) / UNIQUE(tenant_no, entity_no, phone)

mbr_member_tag                 -- 标签是行不是 JSON：要按它筛人、统计人数
  entity_no, member_no, tag_type: SYS|MCH, tag, created_by
  UNIQUE(tenant_no, member_no, tag)
  -- ★ 商家标签按**主体**存（entity_no），不按门店：同一个人在两家店买东西
  --   仍是同一个「爱囤货」的人。门店维度只出现在筛选条件里

mbr_tag_dict                   -- 商家标签字典（限量、可改名、可停用）
  entity_no, tag, usage_count, enabled

mbr_reach_log                  -- 触达记录：频次闸与效果都靠它
  entity_no, member_no, channel, task_no, sent_at, opened_at, ordered_at

mkt_campaign_audience          -- 活动受众（marketing 域，批次四）
  campaign_no, audience_type: ALL|TAG|LEVEL|SOURCE, audience_value
```

**为什么 `phone` 也放唯一键**：线索会员没有 `user_no`，只能靠手机号去重；
本人注册后按手机号**认领**同一行，历史备注与标签因此不丢。

### 3.3 模块设计

- **新增** `backend/shop-member/`（或 `shop-core/.../member` 子域，见 §5 风险）
  - `MemberService`：入会、认领、打标、筛选、统计
  - `MemberTagService`：系统标签重算（每日任务）+ 商家标签增删
  - `MemberReachService`：频次闸 + 触达记录（批次五）
  - `spi/member/MemberQueryPort`：给 marketing 域「这批筛选条件命中哪些 user_no」
- **修改**
  - `MerchantOrderService.customers` → 改为读 `mbr_member`（保留同名端点与响应结构，**先兼容后扩展**）
  - 支付成功事件处 → 发「入会/更新」领域事件（不在算价链路上同步查会员）
  - `mkt_campaign` 保存/生效判断 → 多一处受众判断，**空受众 = 全部**（老活动零影响）
- **复用**：`mkt_attribution`（来源明细）、`mkt_coupon_issue`（发券）、`notify_push_task`（触达）

### 3.4 核心接口（B 端）

```
GET  /biz/members?source=&tag=&level=&silentDays=&spentMin=&phone=&page=&size=
GET  /biz/members/{memberNo}
POST /biz/members            { phone, remark, tags[] }        // 手工录入 → LEAD
POST /biz/members/tags       { memberNos[], add[], remove[] } // 批量打标
GET  /biz/member-tags                                          // 标签字典 + 各标签人数
POST /biz/member-coupons     { filter, couponNo }              // 按筛选发券
GET  /biz/member-stats                                         // 四层人数、沉睡人数、本月新增
```

C 端（批次一只读）：`GET /mp/member/mine`（顺带补上三端唯一断裂的 `/mp/card/mine`）。

### 3.5 系统标签口径（配置在 `sys_setting`，不硬编码）

| 标签 | 口径（默认值，可配） |
|---|---|
| 新客 | 累计 1 单 |
| 常客 | 近 90 天 2–5 单 |
| 熟客 | 近 90 天 ≥6 单 |
| 沉睡 | 曾下单且 `daysSinceLast > 60`（2026-08-24 拍板取 60 天，可配） |
| 高客单 | 近 90 天客单价 ≥ 本店中位数 ×1.5 |

### 3.6 配置项（P4 零硬编码）

`member.level.rules`、`member.reach.window-days`、`member.reach.max-per-window`、
`member.tag.max-per-merchant`、`member.tag.max-per-member` 一律入 `sys_setting`，
运营端可改；代码里只有 key 常量。

## 4. 测试策略

- `MemberEnrollFlowTest`：支付成功入会（来源/首单时间）、同店重复下单不新建行、多来源保留首次来源。
- `MemberLeadClaimFlowTest`：录入线索 → 不可触达 / 不进受众；同号注册后自动认领，标签与备注保留。
- `MemberTagFlowTest`：系统标签重算幂等；商家标签超限被拒；批量打标只影响选中的人。
- `MemberFilterFlowTest`：手机号**必须完整**才返回；跨主体查不到别家会员（数据域）。
- `CampaignAudienceFlowTest`：空受众 = 全部（老活动行为不变）；带受众时非目标用户算价不享受。
- `MemberReachFlowTest`（批次五）：7 天内第二条被闸拦下；退订后不再发。

## 5. 风险与注意事项

1. **合规是硬边界**：线索会员不可触达；手机号只出后四位；不提供导出；按号查找必须完整匹配。
   这几条写进测试，不只写进文档。
2. **存量回填**：`ord_sub_order` 全表聚合一次写入 `mbr_member`。要分批 + 幂等，别在启动时同步跑。
3. **不要把会员查询塞进下单算价**：受众判断只读会员标签集合（可缓存），下单链路不新增跨域强依赖。
4. **新建 Maven 模块的代价**：本仓库已有 `shop-base/core/merchant/settle/channel/app`。
   若不想再加模块，可先落在 `shop-core` 的 `member` 包内、通过 `spi` 暴露，
   等它稳定再拆模块（**建议先包内、后拆模块**）。
5. **端点契约兼容**：`/biz/customers` 保留（内部改读会员表），新端点走 `/biz/members`，
   b-app 路由 `pages/customers` 保留避免深链失效。

## 6. 实现任务（批次一）

- [ ] 迁移：`mbr_member` / `mbr_member_tag` / `mbr_tag_dict`（+ 索引）
- [ ] 入会：支付成功事件 → upsert 会员；扫码/收藏/**主动加入**三条来源接入
- [ ] C 端店铺页「加入会员」（`POST /mp/member/join`）
- [ ] 存量回填任务（幂等、分批）
- [ ] 系统标签每日重算任务 + 口径入 `sys_setting`
- [ ] `/biz/members`（筛选 + 手机号精确）、`/biz/member-stats`
- [ ] b-app：`pages/customers` 升级为会员页（四层数字 + 筛选 + 详情）
- [ ] 测试：`MemberEnrollFlowTest` / `MemberFilterFlowTest`
- [ ] 文档：本 TDD 状态改「已实现」，并回填实际口径

---
确认记录：
- 2026-08-24 用户确认三条：沉睡 60 天 · 商家标签跨门店共享 · `SEARCH` 主动加入并入一期
- 活动侧产品方案另见 [活动体系 · 产品方案](../../requirements/活动体系-需求.md)（同批评审）
