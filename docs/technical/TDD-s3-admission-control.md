# TDD-S3 准入控制（保证金 / 限品类 / 限额）

状态：已实现
关联需求：`docs/technical/经营模式双轨-产品与技术方案.md` §七之二（7.5 S 轴 / 7.7 准入矩阵）
落地清单：`docs/technical/经营模式双轨-落地清单.md` F-6
创建日期：2026-08-11

## 1. 需求摘要

平台**无仓、不碰货**，「自营」只是资质代持的外壳。因此 §7.7 矩阵里最弱主体
（S3 = `legal_form=MICRO`）**没有「入平台仓」这条出路**，只能由平台**出钱兜底**：

> **保证金 + 限品类 + 限额，三样必须同时生效，缺一样另外两样都失效。**

- 保证金单独存在：不限额则敞口无上限，保证金形同虚设
- 限额单独存在：出事没钱赔
- 限品类单独存在：入口类事故不可逆，赔偿弥补不了

验收标准：
1. S3 商户保证金不足时**不能上架**
2. S3 商户不能经营被禁品类
3. 超单笔限额 / 超日累计限额的订单**不能创建**
4. 保证金每一笔变动都有流水，余额可审计
5. **S1/S2 现有行为不变**（默认策略不限额、不禁品类、免保证金）

## 2. 当前架构分析

| 关注点 | 现状 |
|---|---|
| `legal_form` | ✅ `mch_entity.legal_form` = `MICRO`/`INDIVIDUAL`/`ENTERPRISE`（S 轴已锁定） |
| 上架校验 | ✅ `MerchantGoodsServiceImpl.requireCategoryAuthorized`（资质码 + 资质过期两道） |
| 跨域访问商户 | ✅ `MerchantQueryPort`（`shop-base/spi/user`） |
| 保证金 / 限额 | ❌ **全无**，无表、无字段、无代码 |

**复用机会**：上架校验挂在 `requireCategoryAuthorized` 同一处即可，
下单校验挂在订单创建入口；两者都已有稳定的切入点，不需要新流程。

## 3. 方案设计

### 3.1 方案选型：策略按「档位」配置，不按「商户」配置

| 方案 | 结论 |
|---|---|
| A. 策略挂在 `mch_entity` 上，每个商户配一份 | ❌ 运营要逐个配，且改规则要批量刷数据 |
| **B. 策略挂在 `legal_form` 档位上，商户按档位命中（推荐）** | ✅ **采用**。三档三条记录，改规则改一行；与 S 轴锁定一致 |
| C. 写进 `sys_setting` | ❌ 与已拍板的「费率建独立表」同理：需要历史与生效时间，`sys_setting` 只存当前值 |

> 与判断 10（费率建独立表）保持同一取向：**可运营、可回查的规则不进 KV 配置**。

### 3.2 新增表

**`mch_admission_policy`** — 准入策略，按档位一行

| 列 | 说明 |
|---|---|
| `legal_form` | uk，`MICRO`/`INDIVIDUAL`/`ENTERPRISE` |
| `required_deposit_minor` | 应缴保证金，`0` = 免缴 |
| `single_order_limit_minor` | 单笔限额，`0` = 不限 |
| `daily_amount_limit_minor` | 日累计限额，`0` = 不限 |
| `ban_qualified_category` | `1` = 禁止经营任何「需资质」品类 |
| `banned_category_codes` | 额外禁售类目编码，JSON 数组 |
| `enabled` | 停用则该档位不做任何限制 |

**`mch_deposit`** — 保证金账户，一商户一行（`merchant_no` uk）：
`paid_minor` / `frozen_minor` / `status`。

**`mch_deposit_txn`** — 保证金流水：`PAY`/`REFUND`/`FREEZE`/`UNFREEZE`/`DEDUCT`。

> **流水不是可选项**：只有余额字段的账户不可审计，
> 出现争议时说不清「这笔钱什么时候少的、谁扣的」。

**默认数据**：`ENTERPRISE`/`INDIVIDUAL` 三项全 `0` + `ban_qualified_category=0`
→ **S1/S2 行为与现在完全一致**，满足验收标准 5。

### 3.3 核心接口

新增 SPI `AdmissionPort`（`shop-base/spi/user`）——
`shop-core` 的商品与交易域要问 `shop-merchant` 的策略与保证金，**不能直接依赖实体**：

```java
public interface AdmissionPort {
    /** 上架准入：保证金是否足额 + 该类目是否被本档位禁售。不通过直接抛业务异常。 */
    void requireListingAllowed(String merchantNo, String categoryNo, boolean categoryNeedsQualification);

    /** 下单准入：单笔 + 日累计限额。amountMinor 为本单金额。 */
    void requireOrderAllowed(String merchantNo, long amountMinor);
}
```

### 3.4 挂载点

| 位置 | 加什么 |
|---|---|
| `MerchantGoodsServiceImpl.requireCategoryAuthorized` | 末尾调 `requireListingAllowed` |
| 订单创建入口 | 调 `requireOrderAllowed` |

**两处都是「已有校验链上追加一环」**，不改动既有分支。

### 3.5 错误码

`DEPOSIT_INSUFFICIENT(70008)` / `CATEGORY_BANNED(70009)` /
`ORDER_LIMIT_EXCEEDED(70010)` / `DAILY_LIMIT_EXCEEDED(70011)`，配 zh/en/ar。

### 3.6 端侧接口

- Ops：`GET/PUT /ops/admission/policy`、`GET /ops/deposits`、`POST /ops/deposits/{merchantNo}/txn`
- B 端：`GET /biz/deposit`（自己的保证金与限额，商家要知道自己为什么被拦）

## 4. 测试策略

| 场景 | 期望 |
|---|---|
| S3 保证金不足 → 上架 | `70008` |
| S3 补足保证金 → 上架 | 成功 |
| S3 上架被禁类目 | `70009` |
| S3 单笔超限下单 | `70010` |
| S3 日累计超限（第二单触发） | `70011` |
| **S1/S2 全流程** | **与现状一致，无新增拦截** |
| 保证金变动 | 流水条数与余额一致 |

## 5. 风险与注意事项

- **P6**：碰订单创建（已测）。缓解——策略默认全 `0`，
  S1/S2 与未配置商户走**完全相同的分支**，现有测试不受影响。
- 日累计需按商户+当日汇总订单金额，注意索引。
- 保证金**不走微信资金通道**，是平台自己记的账；实扣实退是 P2，本期只做「够不够」的判定。

## 6. 实现任务

- [x] V27 迁移：三张表 + 默认策略
- [x] 实体 + `AdmissionPort` + 实现
- [x] 两处挂载（上架 / 下单）
- [x] 错误码 70008–70011 + zh/en/ar
- [x] Ops / B 端接口
- [x] 场景测试 `S3AdmissionFlowTest`（10 项全绿）

## 7. 实现中的三处更正

| 更正 | 原因 |
|---|---|
| 种子 SQL 从 `SELECT … WHERE NOT EXISTS` 改成 `VALUES` | 无 `FROM` 的 `SELECT` **在 MySQL 下也不合法**（要 `FROM DUAL`）；且表在本迁移新建、恒为空，防重本就多余 |
| `MchAdmissionPolicy.isEnabled()` → `active()` | 与 Lombok 为 `Integer enabled` 生成的 `getEnabled()` 构成同一属性两种类型，MyBatis 反射直接抛 `ambiguous type` |
| 上架校验插在 `requiredCode` 判定之后、early return 之前 | 原位置会让**无门槛类目完全绕过闸门**，而那恰是弱主体最容易上的一批货 |

---
确认记录：2026-08-11 用户「继续」确认后实施
