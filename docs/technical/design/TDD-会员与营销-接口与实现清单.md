# TDD-会员与营销 · 接口与实现清单

状态：草稿（待确认）
关联：[表结构与对象模型](./TDD-会员与营销-表结构与对象模型.md)（19 张表）·
[券与活动模型](./TDD-券与活动模型.md)（抽象）·
[数据库与UI](./TDD-会员与营销-数据库与UI.md)（界面）·
[需求](../../requirements/会员体系与活动联动-需求.md) / [活动](../../requirements/活动体系-需求.md)
创建日期：2026-08-24

---

## 0. 约定

| | 规则 |
|---|---|
| 路径前缀 | B 端 `/biz/**` · C 端 `/mp/**` · 运营端 `/ops/**` |
| 鉴权 | `Authorization: Bearer`；B 端另带 `X-Store-No`（当前门店，见 `BizContextFilter`） |
| 权限 | B 端用 `@PreAuthorize("@perm.canBiz('<码>')")`；**新端点必须登记进 `BizEndpointPermTest` 的决策表**，否则那条架构用例会红 |
| 金额 | 一律 `Minor`（分），整数；不出现小数 |
| 时间 | epoch 毫秒 |
| 分页 | `page`（1 起）/ `size`，返回 `{records,total}` —— 给数组的话运营端列表会当成空页 |
| 手机号 | **出参一律只给后四位**（`phoneTail`）；入参传完整号，服务端哈希后匹配 |

**权限码沿用既有的，不新增**：会员读写用 `biz:customer`（客户资产），
营销用 `biz:campaign`，券的**核销**用 `biz:verify`（店员站在收银台前，与取货核销同一批人）。

---

## 1. 端点总表

### 1.1 B 端 · 会员（`biz:customer`）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| M1 | GET | `/biz/members` | 会员列表（筛选 + 分页） |
| M2 | GET | `/biz/members/stats` | 四层人数、沉睡、本月新增、未计入 |
| M3 | GET | `/biz/members/{memberNo}` | 会员详情（各店往来 + 来源轨迹） |
| M4 | POST | `/biz/members` | 手工录入（线索） |
| M5 | PATCH | `/biz/members/{memberNo}` | 改备注 / 拉黑 |
| M6 | POST | `/biz/members/tags` | 批量打标 / 去标 |
| M7 | GET | `/biz/member-tags` | 标签字典（含人数） |
| M8 | POST | `/biz/member-tags` | 新建标签 |
| M9 | PATCH | `/biz/member-tags/{tagNo}` | 改名 / 停用 |
| M10 | POST | `/biz/member-tags/{tagNo}/merge` | 合并进另一个标签 |
| M11 | GET/POST | `/biz/member-segments` | 人群：列表 / 保存 |
| M12 | POST | `/biz/member-segments/preview` | 试算命中人数（不落库） |
| M13 | GET/PUT | `/biz/member-settings` | 经营口径（按主体/按门店）、下单自动入会 |

### 1.2 B 端 · 营销（`biz:campaign`；核销 `biz:verify`）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| A1 | GET | `/biz/activities` | 活动列表（按状态/排期分组） |
| A2 | GET | `/biz/activities/{activityNo}` | 详情 + 效果 |
| A3 | POST | `/biz/activities` | 新建（含受众与商品范围） |
| A4 | PUT | `/biz/activities/{activityNo}` | 改（`RUNNING` 后只允许改限量与结束时间） |
| A5 | POST | `/biz/activities/{activityNo}/status` | 启停 / 归档 |
| A6 | POST | `/biz/activities/{activityNo}/clone` | 再来一次（复制成草稿） |
| A7 | GET | `/biz/activities/conflicts` | 这批商品已在哪些活动里 |
| C1 | GET | `/biz/coupons` | 券列表 |
| C2 | POST | `/biz/coupons` | 建券（五段） |
| C3 | PATCH | `/biz/coupons/{couponNo}` | 停发 / 恢复 / 归档 |
| C4 | POST | `/biz/coupons/{couponNo}/issue` | 定向发放给人群 |
| C5 | GET | `/biz/coupons/{couponNo}/issues` | 发放批次与结果 |
| V1 | GET | `/biz/coupon-redeem/{code}` | 按码查券（核销前预览） |
| V2 | POST | `/biz/coupon-redeem` | 到店核销一次 |

### 1.3 C 端（登录即可，除标注外）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| U1 | GET | `/mp/member/mine` | 我的会员卡（各店一张） |
| U2 | GET | `/mp/member/{merchantNo}` | 在这家店的会员状态（店铺页那张卡） |
| U3 | POST | `/mp/member/join` | 主动加入（**要求已绑手机号**，否则 70027） |
| U4 | PUT | `/mp/member/{merchantNo}/reach` | 接收/关闭这家店的消息 |
| U5 | GET | `/mp/coupons` | 我的券（可用/已用/过期，含次卡余次） |
| U6 | GET | `/mp/coupons/{userCouponNo}/code` | 到店券的核销码 |

### 1.4 运营端

| # | 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|---|
| O1 | GET | `/ops/members` | `member:member:read` | 跨商家会员（含归属） |
| O2 | GET | `/ops/persons/{personNo}` | `member:person:read` | 人档：名下会员、账号、合并历史 |
| O3 | POST | `/ops/persons/merge` | `member:person:merge` | 人工合并人档（二次确认 + 留痕） |
| O4 | GET | `/ops/members/reach-stats` | `member:member:read` | 触达量与退订率（按商家排） |
| O5 | GET | `/ops/coupons` | `marketing:coupon:read` | 全平台券（归属、敞口、异常标记） |
| O6 | GET | `/ops/activities` | `marketing:campaign:read` | 全平台活动（归属、受众、限量） |
| O7 | POST | `/ops/activities/{activityNo}/stop` | `marketing:campaign:manage` | 强制停止（必须填原因，商家可见） |

---

## 2. 参数详情

> 只列新端点。字段名与 `packages/shared/src/types` 里的契约一一对应。

### M1 `GET /biz/members`

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `storeNo` | string | 否 | 空 = 按主体口径；按门店经营时**必填**（服务端校验 `mbr_setting`） |
| `level` | string | 否 | `NEW`/`REGULAR`/`LOYAL`/`SLEEPING` |
| `source` | string | 否 | `ORDER`/`SHARE`/`SCAN`/`MANUAL`/`FAVORITE`/`SEARCH` |
| `tagNos` | string[] | 否 | 标签号，多个之间是**或** |
| `status` | string | 否 | `ACTIVE`/`LEAD`/`BLOCKED` |
| `lastOrderBefore` / `lastOrderAfter` | long | 否 | 末单时间区间 |
| `spentMin` / `spentMax` | long | 否 | 累计消费（分） |
| `phone` | string | 否 | **完整手机号**才匹配；给了前缀直接返回空并带提示码 |
| `page` / `size` | int | 否 | 默认 1 / 20，`size` 上限 100 |

**响应** `PageData<MemberVO>`：

```
MemberVO { memberNo, nickname, avatar, phoneTail, status, level, source,
           firstStoreNo, firstStoreName, orderCount, totalSpentMinor,
           d90OrderCount, lastOrderAt, daysSinceLast, tags:[{tagNo,name,type}],
           reachOptOut, remark }
```

### M2 `GET /biz/members/stats`

入参：`storeNo?`。响应：

```
{ levels:{NEW,REGULAR,LOYAL,SLEEPING}, reachable, newThisMonth,
  unlinkedBuyers }   // ← 未绑手机号、因此未计入会员的买家数（界面顶部那行）
```

### M3 `GET /biz/members/{memberNo}`

```
MemberDetailVO {
  ...MemberVO,
  stores: [{ storeNo, storeName, orderCount, totalSpentMinor, lastOrderAt, isFirstStore }],
  sources:[{ sourceType, storeName, inviterName, inviterRole, operatorName,
             activityName, occurredAt, isFirst }],
  recentOrders:[{ subOrderNo, paidAt, amountMinor, fulfillment }]
}
```

### M4 `POST /biz/members`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `phone` | string | 是 | 完整手机号。服务端哈希后找人档，没有就建 |
| `remark` | string | 否 | ≤255 |
| `tagNos` | string[] | 否 | 一并打标 |
| `storeNo` | string | 否 | 记进来源明细（哪家店录的） |

**返回** `MemberVO`。已存在则**返回那一条并把备注/标签并进去**，不报错 ——
店员重复录入是常态，报错只会让他再录一次。

### M6 `POST /biz/members/tags`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `memberNos` | string[] | 是 | ≤500 一批 |
| `add` / `remove` | string[] | 否 | 标签号；两者不能同时为空 |

**约束**：`SYS` 标签不可加不可删（`MEMBER_TAG_SYSTEM_READONLY`）；
超出每人上限拒绝并回传超限的那几个人。

### M10 `POST /biz/member-tags/{tagNo}/merge`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `intoTagNo` | string | 是 | 目标标签 |
| `confirm` | boolean | 是 | `false` = **只试算**，返回影响面不落库 |

响应：`{ affectedMembers, bothTagged, referencedActivities }`。
`confirm=true` 时同一事务执行 §表结构文档 2.3.1 的五步。

### M11/M12 人群

```
POST /biz/member-segments        { name, scopeStoreNo?, rule }
POST /biz/member-segments/preview{ rule }  → { count }
rule = { level?, tagNos?, source?, lastOrderBefore?, spentMin?, spentMax? }
```

**人群存条件不存名单**；`preview` 与发放前都会**当场重算**。

### A3 `POST /biz/activities`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | ≤128 |
| `goal` | string | 否 | `ACQUIRE`/`WAKEUP`/`CLEAR`/`BASKET`，只影响端上默认值 |
| `storeNo` | string | 否 | 空 = 全部门店 |
| `trigger` | object | 是 | `{ type: NONE\|AMOUNT\|QTY\|GOODS, amountMinor?, qty? }` |
| `benefit` | object | 是 | `{ type: CUT\|PRICE\|GIFT\|COUPON, amountMinor?, qty?, ref? }` |
| `goodsScope` | object[] | 否 | `[{ scopeType: ALL\|CATEGORY\|GOODS, refNo }]` |
| `audience` | object[] | 否 | `[{ type: TAG\|LEVEL\|SOURCE\|SEGMENT\|NON_MEMBER, value }]`；**空 = 所有人** |
| `schedule` | object | 是 | `{ type: ONE_OFF\|ALWAYS_ON\|RECURRING, startAt?, endAt?, rule? }` |
| `quota` | int | 条件必填 | `benefit.type` 为 `PRICE`/`GIFT` 时必填；`ALWAYS_ON` 一律必填 |
| `budgetMinor` | long | 否 | 与 `quota` 至少给一个 |

**校验**（每条都对应一个错误码，见 §7）：结束晚于开始；`RECURRING` 的 rule 可解析；
受众命中 0 人拒绝；`GOODS` 触发必须有商品范围。

### C2 `POST /biz/coupons`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | |
| `benefit` | object | 是 | `{ mode: CASH\|PERCENT\|GIFT\|FREE_SHIP, value, capMinor?, ref? }`；`PERCENT` **必须**给 `capMinor` |
| `condition` | object | 否 | `{ minAmountMinor?, minQty? }` |
| `scope` | object | 是 | `{ type: ALL\|STORE\|CATEGORY\|GOODS, refNos? }` |
| `validity` | object | 是 | `{ mode: ABSOLUTE\|RELATIVE, startAt?, endAt?, validDays? }` |
| `issueMode` | string | 是 | `CENTER`/`TARGETED`/`ACTIVITY`/`CODE` |
| `redeemMode` | string | 是 | `ORDER`/`STORE_CODE`/`AUTO` |
| `timesTotal` | int | 否 | 默认 1；>1 即次卡 |
| `totalCount` | int | 条件必填 | 非 `TARGETED` 时必填 |
| `perUserLimit` | int | 否 | 默认 1 |
| `budgetMinor` | long | 否 | 服务端断言 `budget ≥ totalCount × 单张最大优惠` |

### C4 `POST /biz/coupons/{couponNo}/issue`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `segmentNo` | string | 二选一 | 发给某个人群 |
| `rule` | object | 二选一 | 直接给筛选条件（不保存人群） |
| `notify` | boolean | 否 | 是否同时发一条消息（受频次闸） |

**响应**（就是界面上那张结果页）：

```
{ issueNo, plannedCount, issuedCount, skippedCount,
  skipped: { reachWindow, lead, optOut, perUserLimit },
  amountMinor }
```

### V1/V2 到店核销

```
GET  /biz/coupon-redeem/{code}   → { userCouponNo, title, benefit, memberPhoneTail,
                                     storeName, expireAt, timesLeft, redeemable, reason? }
POST /biz/coupon-redeem          { code, storeNo }  → { timesLeft, redeemedAt }
```

**幂等**：同一 `code` 在 3 秒内的重复提交视为同一次（防止店员连点两下扣两次）。
`STORE_CODE` 券**不进下单算价**；核销**不可撤销**。

### U3 `POST /mp/member/join`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `merchantNo` | string | 是 | |
| `storeNo` | string | 否 | 从哪家店加入的，记进来源 |

未绑手机号时返回 `70027 WX_PHONE_UNAVAILABLE`，端上据此弹授权 —— **不是错误提示，是一次引导**。

---

## 3. Controller 清单

| 类 | 位置 | 负责的端点 | 备注 |
|---|---|---|---|
| `BizMemberController` | `portal/biz` | M1–M6 | 全部 `@PreAuthorize(CUSTOMER)`；`X-Store-No` 由 `BizContext` 取 |
| `BizMemberTagController` | `portal/biz` | M7–M10 | 合并是写操作，`@Transactional` 在 service 层 |
| `BizMemberSegmentController` | `portal/biz` | M11–M13 | 设置读写也放这儿（同一屏） |
| `BizActivityController` | `portal/biz` | A1–A7 | `CAMPAIGN` |
| `BizCouponController` | `portal/biz` | C1–C5 | `CAMPAIGN` |
| `BizCouponRedeemController` | `portal/biz` | V1–V2 | **`VERIFY`**，与取货核销同一个码 |
| `MpMemberController` | `portal/mp` | U1–U4 | 登录即可 |
| `MpCouponController` | `portal/mp` | U5–U6 | 同上 |
| `OpsMemberController` | `portal/ops` | O1–O4 | 手机号解密查看要**二次确认 + 审计日志** |
| `OpsPromotionController` | `portal/ops` | O5–O7 | 扩现有营销页，不新开菜单组 |

**每个新端点都要在 `BizEndpointPermTest` 的决策表里登记一行**，否则架构用例会红 ——
这条不是形式，它挡的是「新加的 /biz 端点忘了判权」。

---

## 4. Service 清单

### 4.1 会员域（`shop-core` 的 `member` 包，稳定后再拆模块）

```java
public interface MemberService {
    PageData<MemberVO> list(String entityNo, MemberQuery q);
    MemberStatsVO stats(String entityNo, String storeNo);
    MemberDetailVO detail(String entityNo, String memberNo);

    /** 手工录入。已存在则并入备注与标签，不报错 —— 重复录入是常态 */
    MemberVO enroll(String entityNo, EnrollCommand cmd, String operatorNo);

    /** 支付成功后的入会与指标更新。**同一事务、幂等**（按 subOrderNo 去重） */
    void applyOrder(OrderPaidEvent e);

    /** 人档绑定账号后：把这份人档名下所有 LEAD 会员转 ACTIVE */
    int claimByPerson(String personNo);

    MemberVO patch(String entityNo, String memberNo, PatchCommand cmd);
}

public interface MemberTagService {
    List<TagVO> tags(String entityNo);                 // 含人数（COUNT，不存冗余列）
    TagVO create(String entityNo, String name);
    TagVO rename(String entityNo, String tagNo, String name);
    TagVO disable(String entityNo, String tagNo);
    MergePreviewVO merge(String entityNo, String from, String into, boolean confirm);
    void tag(String entityNo, List<String> memberNos, List<String> add, List<String> remove);
    /** 每日任务：按 sys_setting 的口径重算系统标签与两级 level。幂等 */
    void recomputeSystemTags();
}

public interface MemberSegmentService {
    List<SegmentVO> list(String entityNo);
    SegmentVO save(String entityNo, SegmentCommand cmd);
    long preview(String entityNo, SegmentRule rule);
    /** 解析成 memberNo 列表。发券与触达都走它，**当场算**不吃缓存 */
    List<String> resolve(String entityNo, SegmentRule rule);
}

public interface MemberReachService {
    /** 频次闸：返回可发的人与被跳过的原因分布 */
    ReachPlan plan(String entityNo, List<String> memberNos, String scene);
    void record(String entityNo, List<String> memberNos, String scene, String taskNo);
}
```

### 4.2 营销域（`shop-core` 的 `promotion` 包）

```java
public interface ActivityService {
    PageData<ActivityVO> list(String entityNo, ActivityQuery q);
    ActivityVO detail(String entityNo, String activityNo);   // 含效果（读 pmt_apply）
    ActivityVO create(String entityNo, ActivityCommand cmd);
    ActivityVO update(String entityNo, String activityNo, ActivityCommand cmd);
    ActivityVO setStatus(String entityNo, String activityNo, String status);
    ActivityVO clone(String entityNo, String activityNo);
    List<ConflictVO> conflicts(String entityNo, List<String> goodsNos);
}

public interface CouponService {
    PageData<CouponVO> list(String entityNo, CouponQuery q);
    CouponVO create(String entityNo, CouponCommand cmd);     // 建券时算清敞口
    CouponVO patch(String entityNo, String couponNo, String status);
    IssueResultVO issue(String entityNo, String couponNo, IssueCommand cmd);
    List<IssueVO> issues(String entityNo, String couponNo);
}

public interface CouponRedeemService {
    RedeemPreviewVO preview(String entityNo, String code);
    RedeemResultVO redeem(String entityNo, String code, String storeNo, String operatorNo);
}
```

### 4.3 事务与幂等（逐条写死，别靠默认）

| 操作 | 事务边界 | 幂等键 |
|---|---|---|
| 入会 / 指标更新 | 与订单事件同一事务 | `subOrderNo` |
| 定向发券 | **一批一事务**，失败整批回滚 | `issueNo` |
| 到店核销 | 单条事务 + 乐观锁（`version`） | `code` + 3 秒窗口 |
| 标签合并 | 一事务五步 | `from+into`，重复执行第二次是空操作 |
| 落 `pmt_apply` | **与下单同一事务**（不异步） | `orderNo + promoNo` |

---

## 5. Mapper 清单

沿用现有约定：一个域一个 `XxxMappers` 类，内部嵌套 `interface XxxMapper extends BaseMapper<E>`。

### 5.1 `MemberMappers`

| Mapper | 表 | 除 CRUD 外要写的 |
|---|---|---|
| `MemberMapper` | `mbr_member` | `selectPageByQuery`（多条件 + 标签子查询）、`countByLevel` |
| `MemberStoreMapper` | `mbr_member_store` | `upsertMetrics`（按 member+store）、`countByLevel(storeNo)` |
| `MemberSourceMapper` | `mbr_member_source` | `countByInviter`（分享激励）、`countByActivity` |
| `MemberTagMapper` | `mbr_member_tag` | `countByTag`、`repointTag(from,into)`（合并第一步） |
| `TagMapper` | `mbr_tag` | `selectActive(entityNo)` |
| `TagMergeLogMapper` | `mbr_tag_merge_log` | — |
| `SegmentMapper` | `mbr_segment` | — |
| `ReachLogMapper` | `mbr_reach_log` | `lastSentAt(entity,member,scene)`（频次闸）、`statsByEntity` |
| `SettingMapper` | `mbr_setting` | — |

### 5.2 `PersonMappers`（user 域）

| Mapper | 表 | 关键方法 |
|---|---|---|
| `PersonMapper` | `usr_person` | `selectByPhoneHash`、`selectByUserNo`、`bindUser` |
| `PersonMergeLogMapper` | `usr_person_merge_log` | — |

### 5.3 `PromotionMappers`

| Mapper | 表 | 关键方法 |
|---|---|---|
| `ActivityMapper` | `pmt_activity` | `selectLive(entity,store,now)`（算价读它，走 `idx_pmt_activity_live`） |
| `ActivityAudienceMapper` | `pmt_activity_audience` | `selectByActivity` |
| `ActivityGoodsMapper` | `pmt_activity_goods` | `selectByRef`（**冲突提示**：这个商品在哪些活动里） |
| `CouponMapper` | `pmt_coupon` | `tryIncReceived`（原子扣发行量） |
| `CouponScopeMapper` | `pmt_coupon_scope` | `selectByCoupon` |
| `UserCouponMapper` | `pmt_user_coupon` | `selectUsable(userNo,now)`、`selectByCode`、`tryConsumeOnce`（乐观锁扣次数） |
| `CouponIssueMapper` | `pmt_coupon_issue` | — |
| `ApplyMapper` | `pmt_apply` | `sumByPromo`（活动效果）、`revertByOrder`（取消退回） |

> `tryIncReceived` / `tryConsumeOnce` 都是**一条带条件的 UPDATE**，不是先查后改 ——
> 先查后改在并发下会超发。这两处是本设计里仅有的两个并发点。

---

## 6. 跨域 Port

| Port | 方向 | 方法 |
|---|---|---|
| `PersonPort` | member → user | `resolveByPhone`、`resolveByUser`、`bindPhone`、`phoneTail` |
| `MemberQueryPort` | promotion → member | `profileOf(entityNo,userNo)` → `{isMember, level, tagNos}`（算价用，一次取回） |
| `MemberEventPort` | trade → member | `onOrderPaid(event)` |
| `ActivityPort` | trade → promotion | `autoDiscount`、`flashPrices`、`giftRules`（**签名保持不变**，实现换库） |
| `CouponPort` | trade → promotion | `usable`、`best`、`consume`、`revert` |
| `PromotionApplyPort` | trade → promotion | `record(list)`（同事务） |

---

## 7. 要新增的错误码

沿用 `ErrorCode` 的编号段（当前最大 70029）：

| 码 | 常量 | 何时 |
|---|---|---|
| 70030 | `MEMBER_PHONE_REQUIRED` | 未绑手机号却要加入会员 / 被选进发放 |
| 70031 | `MEMBER_TAG_LIMIT` | 每店或每人标签超限 |
| 70032 | `MEMBER_TAG_SYSTEM_READONLY` | 想改系统标签 |
| 70033 | `SEGMENT_EMPTY` | 受众/人群命中 0 人 |
| 70034 | `ACTIVITY_QUOTA_REQUIRED` | 改价/送商品或长期活动没填限量 |
| 70035 | `COUPON_CAP_REQUIRED` | 折扣券没填封顶 |
| 70036 | `COUPON_BUDGET_INSUFFICIENT` | 预算兜不住 `totalCount × 单张最大优惠` |
| 70037 | `COUPON_NOT_REDEEMABLE` | 到店核销时已过期/已用完/不属于本店 |
| 70038 | `PERSON_PHONE_TAKEN` | 手机号已绑其它账号（走人工） |

---

## 8. 实现顺序（每步都能独立上线）

1. `usr_person` + `PersonPort` + 绑号三分支（不动会员，先把身份立住）
2. `mbr_*` 建表 + 入会链路 + M1/M2/M3 + B 端会员页
3. M4–M10 录入与标签（含合并）
4. M11–M13 人群与口径
5. `pmt_coupon` 系列 + C1–C5 + 券页（**算价接入点签名不变**）
6. `pmt_activity` 系列 + A1–A7 + 活动页
7. V1/V2 到店核销 + C 端 U1–U6
8. 触达（`MemberReachService` + 频次闸）
9. 运营端 O1–O7
10. 旧 `mkt_*` 退场

---
确认记录：待用户确认
