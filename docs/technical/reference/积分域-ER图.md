# ⚠️ 本文已废弃（2026-08-06）

> 这份手写的 ER 图有三处字段名与实际不符（发现于逐列比对 V17），
> 且模型已改为预付费，其中的 `pts_merchant_quota` 已删除。
>
> **请改看**：
> - [表设计详解](../archive/积分域-表设计详解.md) —— 关系全图 + 逐表说明（**推荐**）
> - [数据库-ER图](./数据库-ER图.md) —— 全库 54 张表，由 `npm run gen:erd` 生成，不会漂移
>
> 保留本文只为留痕：它是「手画的图会漂移且看不出来」这条结论的来源。
> 下方内容**已过期，勿参考**。

---

# 积分域 · ER 图

> 依据 `V17__points_t1.sql`（已落地）· 2026-08-06
> 配套：[需求与数据库设计](../archive/积分域-需求与数据库设计.md) · [接口与核心逻辑](../design/积分域-接口与核心逻辑设计.md)
>
> 图中省略每张表都有的审计列（`tenant_no` / `created_*` / `updated_*` / `version` / `deleted`）。

## 一、全局关系

```text
erDiagram
    usr_user ||--|| pts_user_account : "一人一户"
    usr_user ||--o{ pts_user_ledger : "流水"
    pts_user_account }o..|| pts_user_ledger : "余额由流水派生"

    pts_user_ledger ||--o{ pts_redeem_alloc : "EARN 批次被消耗"
    pts_user_ledger ||--o{ pts_redeem_alloc : "USE 拆到多批次"

    usr_merchant ||--|| pts_merchant_quota : "额度台账"
    usr_merchant ||--o{ pts_merchant_ledger : "商家流水"
    usr_merchant ||--o{ stl_points_bill : "账期单"

    pts_redeem_alloc }o--|| usr_merchant : "issuer 发放方"
    pts_redeem_alloc }o--|| usr_merchant : "acceptor 收单方"
    pts_redeem_alloc ||--o{ pts_merchant_ledger : "兑付产生收/付两条"

    stl_points_bill ||--o{ pts_merchant_ledger : "按 period 聚合"
    stl_points_bill ||--o{ stl_points_pool : "结算走备付池"

    ord_sub_order ||--o{ pts_redeem_alloc : "本单的抵扣分摊"
    ord_sub_order ||--o{ pts_user_ledger : "发放/抵扣来源"

    usr_user {
        varchar user_no PK
    }
    usr_merchant {
        varchar merchant_no PK
        tinyint points_enabled "L3 开关"
        tinyint points_forced "行业强制开"
    }
    ord_sub_order {
        varchar sub_order_no PK
        int points_deduct "抵扣分数"
        bigint points_deduct_minor "抵扣金额-两账勾稽点"
        tinyint points_granted "发放幂等标记"
    }

    pts_user_account {
        varchar user_no UK
        bigint balance "派生-以流水为准"
        bigint total_earn
        bigint total_use
    }

    pts_user_ledger {
        varchar ledger_no UK
        varchar user_no FK
        varchar biz_type "EARN/USE/REFUND/EXPIRE"
        bigint points "带符号"
        bigint balance_after "快照"
        bigint remaining "仅EARN-批次剩余"
        bigint expire_at "仅EARN"
        varchar issuer_merchant_no "仅EARN-发放方"
        varchar sub_order_no FK
    }

    pts_redeem_alloc {
        varchar alloc_no UK
        varchar use_ledger_no FK "用户的USE流水"
        varchar earn_ledger_no FK "被消耗的EARN批次"
        varchar user_no FK
        varchar sub_order_no FK
        varchar issuer_merchant_no FK "发放方"
        varchar acceptor_merchant_no FK "收单方"
        bigint points
        bigint amount_minor "折算金额"
        int rate_snapshot "汇率快照"
        tinyint self_used "自发自用-不进账期单"
        varchar status "PENDING/CONFIRMED/REVERSED"
        varchar period "CONFIRMED时落定"
    }

    pts_merchant_quota {
        varchar merchant_no UK
        bigint credit_limit "授信额度"
        bigint used "已占用-发放即占过期释放"
        tinyint suspended "超授信停发-同时停收"
    }

    pts_merchant_ledger {
        varchar ledger_no UK
        varchar merchant_no FK
        varchar biz_type "ISSUE/REDEEM_IN/REDEEM_OUT/EXPIRE_BACK/REVOKE/SETTLE"
        bigint quota_delta "额度变动"
        bigint amount_delta_minor "金额变动"
        bigint quota_used_after "快照"
        varchar counterparty_no "对手方商家"
        varchar alloc_no FK
        varchar period
    }

    stl_points_bill {
        varchar bill_no UK
        varchar merchant_no FK
        varchar period "账期YYYYMM"
        bigint income_minor "别人的分在我这花掉"
        bigint expense_minor "我的分在别人那花掉"
        bigint net_minor "正=平台付-负=从货款扣"
        varchar status "DRAFT/CONFIRMED/SETTLED"
    }

    stl_points_pool {
        varchar flow_no UK
        varchar direction "IN/OUT"
        varchar pool_type "MERCHANT_PAY/RECOVERY/PENALTY等"
        bigint amount_minor
        bigint balance_after
        varchar ref_no FK "关联账期单/兑付明细"
    }
```

## 二、读这张图的三个要点

### 2.1 账户是派生的，流水才是真源

`pts_user_account` 与 `pts_merchant_quota` 都是**为了锁行与避免全表求和**才存在的，
真相在 `pts_user_ledger` 和 `pts_merchant_ledger` 里。

图上用虚线 `}o..||` 标出这层关系。对账任务每日用流水重算，
**不一致时以流水为准**并告警。

### 2.2 `pts_redeem_alloc` 是整个跨商家清算的枢纽

它同时挂着四条线：

```
用户的 USE 流水  ──┐
被消耗的 EARN 批次 ─┼──▶ pts_redeem_alloc ──▶ 商家流水（收 / 付各一条）
发放方商家       ──┤                      └──▶ 账期单（按 period 聚合）
收单方商家       ──┘
```

**为什么一次使用要拆成多条 alloc**：用户一次用 3000 分，
可能 2000 是 A 发的、1000 是 B 发的——不拆就算不出谁欠谁。

### 2.3 两条平行的账，一个勾稽点

```
资金账：ord_sub_order → stl_bill → 分账/解冻
                │
                └── points_deduct_minor  ← 勾稽点
                │
积分账：pts_redeem_alloc → pts_merchant_ledger → stl_points_bill → stl_points_pool
```

`ord_sub_order.points_deduct_minor` 是唯一同时被两本账引用的字段。
对账要校验：**资金账里商家少收的部分 == 积分账里商家收到的积分金额**。

## 三、双口径：为什么商家流水有两列 delta

`pts_merchant_ledger` 上 `quota_delta`（额度）与 `amount_delta_minor`（钱）
**永远只有一列非零**：

| `biz_type` | `quota_delta` | `amount_delta_minor` | 什么时候 |
|---|---|---|---|
| `ISSUE` | **+** 占用 | 0 | 发放积分给用户 |
| `EXPIRE_BACK` | **−** 释放 | 0 | 用户的分过期 |
| `REVOKE` | **−** 释放 | 0 | 退款收回已发 |
| `REDEEM_IN` | 0 | **+** 收 | 别人的分在我这被花掉 |
| `REDEEM_OUT` | 0 | **−** 付 | 我发的分在别人那被花掉 |
| `SETTLE` | 0 | **±** | 账期结算 |

合成一列就会把「发放」（或有负债，可能永不发生）
和「兑付」（真实资金）混为一谈——这正是 R4.1/R4.2 要防的。
