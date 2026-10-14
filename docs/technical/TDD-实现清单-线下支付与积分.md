# TDD 实现清单：线下支付 · 支付方式四层 · 类目积分 · 端开关

状态：**草稿 · 待确认**
上位方案：[TDD-支付与积分总体方案](TDD-支付与积分总体方案.md)（设计与理由都在那里，本文只列**要写什么**）
分册：[TDD-线下支付与预约排期](TDD-线下支付与预约排期.md) · [TDD-积分发放维度与线下支付的交叉](TDD-积分发放维度与线下支付的交叉.md)
创建：2026-08-25

> 本文是**施工清单**，不重复论证。每一条都写清：放哪个模块、叫什么、为什么在这一层。
> 遵循仓库既有约定：Mapper 收在 `XxxMappers` 里、端点按 `/mp` `/biz` `/ops` 分、
> 每个 `/biz` 端点必须有 `@PreAuthorize`（`BizEndpointPermTest` 会拦）。

---

## 1. 数据库清单

### 1.1 新建表（4 张）

| 表 | 归属域 | 锚点 | 说明 |
|---|---|---|---|
| `prd_category_pay_mode` | product | `category_no` | ① 平台 × 类目：这类商品准不准某支付方式。**没有行 = 放行** |
| `prd_category_points` | product | `category_no` | 类目积分规则。与 `prd_category_spec` 同构 |
| `prd_appointment_slot` | product | `entity_no` + `store_no` | 可约时段 + 容量 |
| `prd_appointment_exception` | product | `slot_id` / 日期 | 某日停约或加开 |

```sql
CREATE TABLE IF NOT EXISTS prd_category_pay_mode
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    category_no  VARCHAR(64)  NOT NULL,
    pay_mode     VARCHAR(16)  NOT NULL,          -- PayModes 取值域
    allowed      TINYINT      NOT NULL DEFAULT 1,
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at   DATETIME     NOT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cat_pay_mode UNIQUE (tenant_no, category_no, pay_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='类目 × 支付方式：没有行即放行';

CREATE TABLE IF NOT EXISTS prd_category_points
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    category_no   VARCHAR(64) NOT NULL,
    earn_mode     VARCHAR(16) NOT NULL,           -- FIXED 定额 / RATIO 按成交额比例
    earn_value    BIGINT      NOT NULL,           -- FIXED=分；RATIO=万分比。**不用浮点**
    tenant_no     VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME    NOT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at    DATETIME    NOT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version       BIGINT      NOT NULL DEFAULT 0,
    deleted       TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_cat_points UNIQUE (tenant_no, category_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='类目积分规则：平台统一按类目管理';
```

> **`earn_value` 用整数不用浮点**：比例存万分比（千分之一 = 10）。
> 金额与比例一旦用 double，对账时的分位差没人说得清 —— 与 `commission_rate` 同一条规矩。

`prd_appointment_slot` / `prd_appointment_exception` 的列见分册 §3.3，本文不重复。

### 1.2 加列（5 处）

| 表 | 列 | 说明 |
|---|---|---|
| `prd_goods` | `pay_modes VARCHAR(128) NOT NULL DEFAULT '["ONLINE"]'` | ④ 商品层。取值域受 `PayModes` 约束 |
| `mch_store` | `offline_pay_enabled TINYINT NOT NULL DEFAULT 0` | ③ 门店层。**默认关** |
| `mch_store` | `cod_enabled TINYINT NOT NULL DEFAULT 0` | 货到付款单独开关（总纲 §3，风险最高的一格） |
| `ord_order` | `offline_confirmed_by VARCHAR(64)` / `offline_confirmed_at BIGINT` | 线下收款留痕 |
| `stl_bill` | `waived_commission_minor BIGINT NOT NULL DEFAULT 0` | 让掉的佣金，只记不扣 |

### 1.3 主数据（2 条 INSERT）

```sql
INSERT INTO sys_pay_channel (pay_channel, name, enabled,
    supports_subsidy, supports_split, supports_payout, ...)
VALUES ('OFFLINE', '线下收款', 1, 0, 0, 0, ...);
```

第二条是端开关。**不新建表** —— 端的允许集合是两个小集合，
放 `sys_config` 一类的现成配置里即可（`points.earn.clients` / `points.redeem.clients`），
默认全允许。为四个字符串建一张表不划算。

### 1.4 **不动**的东西（明确列出，防止顺手改）

- `prd_goods.points_config` —— 不删列、不给商家入口，只留运营例外（总纲 §5.2）
- `prd_sku.presale_quota` / `cutoff_at` / `arrive_at` —— 预售那套原样留着，不做入口
- `ord_order.pay_scene` —— **列已存在**（V1 baseline），只补写入，不加新列
- `mch_qualification` —— 只读，不改结构

### 1.5 迁移号

**从 V244 起**（工作区里已有别人未提交到 V243）。落号前跑一次：

```bash
ls backend/shop-app/src/main/resources/db/migration/ | sed 's/^V\([0-9]*\)__.*/\1/' | sort -n | tail -3
```

---

## 2. 取值域常量（shop-base）

| 类 | 路径 | 内容 |
|---|---|---|
| `PayModes` | `shop-base/common/PayModes.java` | `ONLINE` / `OFFLINE` + `ALL` + `isValid` |
| `PayScenes` | `shop-base/common/PayScenes.java` | `MP_WECHAT` / `MP_ALIPAY` / `IOS` / `ANDROID` / `H5`。**取值直接抄 `pay_scene` 的列注释**，不另起一套词 |

> 放 `shop-base` 而不是留在某个域：商品域、交易域、结算域都要用它，
> **取值域属于这三者之上的公共语言** —— 与 `Fulfillments` 同一条理由。

---

## 3. 实体与 Mapper

### 3.1 新增实体（4 个）

| 实体 | 路径 | `@TableName` |
|---|---|---|
| `PrdCategoryPayMode` | `shop-core/product/entity/` | `prd_category_pay_mode` |
| `PrdCategoryPoints` | `shop-core/product/entity/` | `prd_category_points` |
| `PrdAppointmentSlot` | `shop-core/product/entity/` | `prd_appointment_slot` |
| `PrdAppointmentException` | `shop-core/product/entity/` | `prd_appointment_exception` |

均 `extends BaseEntity`（它已提供 id/tenantNo/createdAt/…/deleted，实体不再声明）。

### 3.2 修改实体（3 个）

| 实体 | 加字段 |
|---|---|
| `PrdGoods` | `payModes` |
| `MchStore` | `offlinePayEnabled` / `codEnabled` |
| `OrdOrder` | `offlineConfirmedBy` / `offlineConfirmedAt` / **`payScene`（列早就有，实体没有）** |
| `StlBill` | `waivedCommissionMinor` |

> ⚠️ **加列必须同时补实体字段**，否则那一列永远读出 null 且不报错。
> 写完立刻跑：`npx vitest run packages/shared/tests/entity-alignment.test.ts`
> （它在 JS 那边，`mvn test` 看不到它 —— 今天刚因此漏掉过一次）。

### 3.3 Mapper（收进现有集合类，不新建文件）

`ProductMappers` 里加四个嵌套接口：

```java
public interface CategoryPayModeMapper extends BaseMapper<PrdCategoryPayMode> { }
public interface CategoryPointsMapper  extends BaseMapper<PrdCategoryPoints> { }
public interface AppointmentSlotMapper extends BaseMapper<PrdAppointmentSlot> {
    /** 占用一个名额。**带条件的 UPDATE**，影响 0 行即已约满 —— 与库存锁定同一条口径 */
    @Update("UPDATE prd_appointment_slot SET booked = booked + 1 "
          + "WHERE id = #{id} AND booked < capacity AND deleted = 0")
    int tryBook(@Param("id") long id);

    @Update("UPDATE prd_appointment_slot SET booked = booked - 1 "
          + "WHERE id = #{id} AND booked > 0 AND deleted = 0")
    int release(@Param("id") long id);
}
public interface AppointmentExceptionMapper extends BaseMapper<PrdAppointmentException> { }
```

> **`tryBook` 必须是带条件的 UPDATE，不能「先查再改」。** 先查再改在并发下必然超约 ——
> 与到店核销扣次数（`5969d869`）、与库存锁定是同一个做法，不引入新的并发模型。

---

## 4. Service / Port

### 4.1 新增（4 个）

| 类型 | 名称 | 模块 | 职责 |
|---|---|---|---|
| Service | `PayModeService` | `shop-core/product/service/` | **`availablePayModes(goodsNo, storeNo)` 四层取交集的唯一入口** |
| Service | `AppointmentSlotService` | `shop-core/product/service/` | 排期维护、查可约、占用/释放 |
| Service | `PointsRuleResolver` | `shop-settle/impl/` | 发放规则取值：商品例外 → 类目 → 平台兜底。**唯一入口** |
| Port | `QualificationPort` | `shop-base/spi/merchant/` | 给 product 域用：`hasValidQualification(entityNo, qualType)` |

**为什么资质要走 Port**：`mch_qualification` 在 merchant 域，而判定发生在 product 域。
直接依赖会造成 product → merchant 的域间耦合，而今天已经有人因为
`merchant → StoreShelfPort → MerchantGoodsService → GoodsService → merchant` 撞出过构造环。
**跨域一律走 `shop-base/spi` 的 Port。**

```java
/** 此刻有没有一张有效的证。**按 expire_at 现算**，不读「审核时写死的那串码」。 */
boolean hasValidQualification(String entityNo, String qualType);
```

> 两条理由都写进注释：① `MchQualification` 的类注释记着「证过期了 `category_codes`
> 不会变，商家照样上架」；② **生产没有 worker，定时任务不跑**，
> 所以不能依赖 `status=EXPIRED` 被置上。

### 4.2 修改（5 个）

| 类 | 改什么 |
|---|---|
| `OrderServiceImpl#create` | ① 校验支付方式（调 `PayModeService`）② 组合合法性 ③ 平台券 × 线下 拒绝 ④ **写 `payScene`** ⑤ 线下落 `WAIT_OFFLINE_PAY` ⑥ 预约占时段 |
| `OrderServiceImpl#markPaid` | **签名不变**。仅接受 `payChannel=OFFLINE` |
| `OrderStateMachine` | 主单加 `WAIT_OFFLINE_PAY` 及两条出边；子单不动 |
| `SettleServiceImpl` | 线下单落 `OFFLINE_SETTLED`、记 `waivedCommissionMinor`、带 `payScene` |
| `PointsServiceImpl` | 发放改走 `PointsRuleResolver`；加 `canEarn(orderNo)` / `canRedeem(...)` |

---

## 5. API 清单

### 5.1 C 端 `/mp`（1 改 1 新）

| 方法 | 路径 | 变更 | 说明 |
|---|---|---|---|
| POST | `/mp/order` | **改** | 请求体加 `payMode`；`X-Client` 头 → `payScene` |
| GET | `/mp/goods/{goodsNo}/pay-modes` | 新 | 详情页要显示「支持货到付款」。**与结算页同一个判定入口**，否则两处会说不一样的话 |
| GET | `/mp/appointment/slots?goodsNo=&date=` | 新 | 可约时段（只返回未满的） |

### 5.2 B 端 `/biz`（3 新）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/biz/order/{subOrderNo}/confirm-offline-pay` | `BizPerms.RECEIVE` | 确认收款。**带操作人留痕** |
| GET | `/biz/appointment/slots` | `BizPerms.GOODS` | 排期列表 |
| POST | `/biz/appointment/slots` | `BizPerms.GOODS` | 整份替换排期（与 `/ops/category-specs/{no}` 同一形态：有序的一组，逐条 diff 无收益） |

> **权限选 `RECEIVE` 不选 `ORDER_VIEW`**：确认收款是「收了钱」这个动作，
> 与核销/收货同类；`ORDER_VIEW` 是只读权限，配送员也有它（`BizPerms` 里 `COURIER` 持有）。
> 给只读角色一个能推进订单状态的动作是越权。

⚠️ 每个 `/biz` 端点都要 `@PreAuthorize("@perm.canBiz('…')")` ——
`BizEndpointPermTest` 会扫，漏了直接红。

### 5.3 运营端 `/ops`（6 新，全部 `@Profile("ops")`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/ops/category-pay-modes` | `PRODUCT_CATEGORY_READ` | 类目 × 支付方式 一览 |
| POST | `/ops/category-pay-modes/{categoryNo}` | `PRODUCT_SPEC_UPDATE` | 整份替换 |
| GET | `/ops/category-points` | `PRODUCT_CATEGORY_READ` | 类目积分一览 |
| POST | `/ops/category-points/{categoryNo}` | `PRODUCT_SPEC_UPDATE` | 设置类目积分规则 |
| GET | `/ops/points/client-switches` | `PRODUCT_SPEC_UPDATE` | 端 × 发放/核销 开关 |
| POST | `/ops/points/client-switches` | `PRODUCT_SPEC_UPDATE` | 同上，整份替换 |

⚠️ **必须标 `@Profile("ops")`**：`/ops/**` 与 `/mp,/biz` 的控制器互斥
（S8 部署隔离）。标错的症状是**端点在运营端实例上根本不注册、返回 404**，
而单测看不出来（测试上下文两个 profile 都在）。`ControllerProfileTest` 会拦。

### 5.4 端点登记（前端）

三端各自的 `endpoints.ts` 要登记新端点，**注释别夹在 `{` 与 `method:` 之间**
—— 夹在那里端点会静默不进 spec（这个坑记录在案）。

---

## 6. 错误码（新增 5 个，80011 起）

| 码 | 名称 | 何时抛 |
|---|---|---|
| 80011 | `PAY_MODE_NOT_SUPPORTED` | 所选支付方式该商品/门店/类目不支持 |
| 80012 | `OFFLINE_PAY_NOT_QUALIFIED` | 主体没有有效资质 |
| 80013 | `PLATFORM_COUPON_OFFLINE_FORBIDDEN` | 线下支付选了平台券 |
| 80014 | `APPOINTMENT_SLOT_FULL` | 时段已约满（`tryBook` 影响 0 行） |
| 80015 | `APPOINTMENT_SLOT_UNAVAILABLE` | 时段未开放或已停约 |

> 每个都要配**三份 i18n**（`messages` / `_en` / `_ar`），`BackendI18nParityTest` 会拦。
> 不复用 `BAD_REQUEST`：运营/商家看到「参数有误」会去查格式，而真正的原因是资质或已约满。

---

## 7. 前端清单

| 端 | 页面 | 变更 |
|---|---|---|
| c-app | 结算页 | 支付方式选择；线下时平台券置灰并说明；积分抵扣照常 |
| c-app | 商品详情 | 「支持货到付款」标 |
| c-app | 预约商品 | 时段选择器（替代自由填时间） |
| b-app | 订单列表 | 新增「待收款」分组 |
| b-app | 订单详情 | **确认收款**按钮 + 弹窗：**大字应收金额** + 「已抵扣 X 元」+「平台不代收此款」 |
| b-app | 排期维护页 | 星期 × 时段 × 容量，默认值取 `mch_store.open_hours` |
| ops-web | 类目 × 支付方式 | 照搬「类目 × 规格」那一页的形态 |
| ops-web | 类目 × 积分 | 同上 |
| ops-web | 端开关 | 发放/核销两列；配出「能核销不能发放」时给校验提示（不硬禁） |

> 改了页面**必须重跑界面清单生成器**并把 JSON 一起提交，否则 pre-push 挡住：
> `python3 scripts/gen-ui-catalog.py`

---

## 8. 施工顺序

| 步 | 内容 | 可并行 |
|---|---|---|
| 1 | 迁移 + 实体 + Mapper + `PayModes`/`PayScenes` + 错误码与三份 i18n | — |
| 2 | `QualificationPort` + `PayModeService`（四层判定） | 与 3 并行 |
| 3 | `PointsRuleResolver` + `PointsServiceImpl` 改造 | 与 2 并行 |
| 4 | `OrderStateMachine` + `create` + `confirm-offline-pay` | 依赖 2 |
| 5 | `SettleServiceImpl` 终态与两列 | 依赖 4 |
| 6 | `AppointmentSlotService` + 排期端点 | 独立，随时 |
| 7 | 三端界面 + 端点登记 + 界面清单 | 依赖 1~6 的接口 |
| 8 | 全量测试 → 与 `known-failures.txt` 比对，**新增失败为 0** | 最后 |

---

## 9. 每一步都要跑的守卫

```bash
python3 backend/scripts/gen-test-schema.py                       # 改了迁移就要重跑
npx vitest run packages/shared/tests/entity-alignment.test.ts     # 加列必跑（JS 那边）
python3 scripts/gen-ui-catalog.py                                 # 改了页面必跑
bash scripts/check-head-compiles.sh                               # 推送前（含全量测试，约 3 分钟）
```

---

确认记录：待确认
