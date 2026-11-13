# TDD-餐饮门店基本信息与时间约定

状态：已确认（2026-11-13，用户「先做 B5/B6」）
档位：1（新增一个只读端点；不动库表、权限码、配置）
关联：POS《后端能力需求清单》B5（时间字段格式）、B6（门店基本信息读接口）；[TDD-餐饮柜台后付结账](./TDD-餐饮柜台后付结账.md)「上线清单」
创建：2026-11-13

> **L1 定位**：POS 上线前要知道「这家店用什么时区、什么币种、今天是哪个营业日」，以及服务端返回的时间字段该怎么解析。今天没有一个接口同时给出前三者（营业策略只有时区和切日分钟、币种无处可读），时间字段的口径也没写下来。

## §0 对账一 · 需求 → 设计

| AC | Given / When / Then | 落点 |
|---|---|---|
| AC1 | Given 终端账号已登录、门店已有营业策略；When `GET /biz/x/food/stores/{storeNo}/profile`；Then 返回门店号、名称、状态、市场、币种与小数位、时区、切日分钟、付款模式、当前营业日及其起止时刻（UTC）、服务器当前时刻 | `BizStoreProfileController` → `FoodStoreProfileAssembler` |
| AC2 | 当前营业日按「门店时区的当前时刻减切日分钟」取日期；起止时刻 = 该日期在门店时区的切日时刻起算 24 小时，以 UTC Instant 返回 | `FoodStoreProfileAssembler#businessDay` |
| AC3 | 门店不属于当前登录主体 / 不是当前门店 → 拒绝（与其他 `/stores/{storeNo}` 读接口同一套范围校验）；门店还没有营业策略 → 10404 | `FoodBizStoreScope#path`、营业策略读 |
| AC4 | 读权限用 `biz:order:view`（店员、终端都有），不新增权限码 | `@PreAuthorize` |
| AC5 | 时间字段约定写成文档：各类型的线上格式与时区口径 | 本文 §2「时间字段约定」 |

## §1 现状与影响面

- 营业策略读 `GET /biz/x/food/stores/{s}/policy?at=` 只给 `timezone`、`cutoverMinute`、`payPolicy`，且要 `biz:store`（店员没有）。
- 币种在平台：主体市场 `MerchantQueryPort#marketOf` → `MarketPort#find`（币种、小数位、市场时区）。这两个端口只在宿主进程，所以组装放在 food-web（拆分部署时 food-web 也跑在宿主里）；营业策略沿用 `StoreOperationService`（嵌入本地、拆分远程）。
- 时间字段：没有全局 Jackson 时间配置，走默认（ISO 字符串）。2026-11-13 本机 8091 实测与查库发现，餐饮代码里 `LocalDateTime` 有两种取值口径：业务时刻（营业日起止、开台、关单、生效时间）按 UTC；审计字段 `created_at` / `updated_at` 来自 `LocalDateTime.now()`（JVM 默认时区）。JVM 不在 UTC 时两者差几小时（本机 +04:00 实测差 4 小时）。

**不受影响**：现有接口的输出格式不变（本 TDD 只把它写下来）。

## §2 方案

### 新端点

`GET /biz/x/food/stores/{storeNo}/profile`，权限 `biz:order:view`，无请求体、无查询参数。

```json
{
  "storeNo": "ST-M0001", "storeName": "海岸店", "entityNo": "M0001", "status": "ACTIVE",
  "market": "CN", "currency": "CNY", "currencyScale": 2,
  "timezone": "Asia/Shanghai", "cutoverMinute": 240, "payPolicy": "POSTPAY",
  "businessDate": "2026-11-13",
  "businessDayStartsAt": "2026-11-12T20:00:00Z", "businessDayEndsAt": "2026-11-13T20:00:00Z",
  "serverTime": "2026-11-13T10:30:12.345Z"
}
```

- `market` / `currency` / `currencyScale` 取主体所在市场（`marketOf` → `MarketPort#find`）。平台约定**不兜底成 CN**：主体没配市场或市场查不到时这三项为 null，其余字段照常返回（POS 仍能拿到时区与营业日，币种按本机配置）。
- `businessDate` 只是按时钟推算的「现在属于哪一天」，与营业日是否已 `:open` 无关（开营业日仍走原接口，幂等）。
- `serverTime` 供 POS 估算本机时钟偏差。

### 时间字段约定（B5）

| Java 类型 | 线上格式 | 时区口径 | 例子 |
|---|---|---|---|
| `Instant` | ISO-8601，带 `Z`；**小数位不固定**（0–9 位，随值的精度，如 `.394Z`、`.930027Z`），按标准 ISO 解析即可 | UTC | 菜单发布 `effectiveAt`：`2026-11-13T02:04:24.394Z`；`serverTime`：`2026-11-13T14:37:35.930027Z` |
| `LocalDateTime`（业务时刻） | ISO-8601，**不带偏移** | **UTC** | 营业日 `startsAt`：`2026-11-12T20:00:00`（= 上海 11-13 04:00） |
| `LocalDate` | `yyyy-MM-dd` | 门店时区的日历日 | `businessDate`：`2026-11-13` |
| `OffsetDateTime` | ISO-8601，带偏移 | 门店市场时区 | 外部记账 `receivedAt`：`2026-11-13T14:22:08+08:00` |
| 审计字段 `createdAt` / `updatedAt` 等 | 同 `LocalDateTime` | **JVM 默认时区** | 不建议客户端依赖 |

- 请求里的 `Instant` 字段（如 `effectiveFrom` / `effectiveAt`）请发带 `Z` 的 ISO 字符串；`OffsetDateTime` 字段（如外部记账 `receivedAt`）发带偏移的 ISO 字符串。
- 数量字段（`qty`）只收 JSON 数字；金额字段为整数分。
- **部署要求**：JVM 默认时区设为 UTC（`-Duser.timezone=UTC` 或 `TZ=UTC`），审计字段才与业务时刻同一口径。已列入柜台结账 TDD「上线清单」。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `food-api/.../dto/StoreProfileVO.java` | 返回体 |
| 新增 | `food-web/.../api/StoreProfileAssembler.java` | 组装：门店卡 + 营业策略 + 市场；营业日推算 |
| 新增 | `food-web/.../api/biz/BizStoreProfileController.java` | 端点 |
| 修改 | `food-web/.../config/FoodWebAutoConfiguration.java` | 登记控制器（`FoodControllerRegistrationTest` 守着） |
| 修改 | `docs/technical/TDD-餐饮柜台后付结账.md` | 上线清单加 JVM 时区 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 结果 |
|---|---|---|
| AC1 | `StoreProfileAssemblerTest#businessDayFollowsStoreTimezoneAndCutover`（名称、时区、切日、付款模式、币种、服务器时刻）；本机 8091 实测见下 | 通过 |
| AC2 | 同上：上海 10:30 → 当日；凌晨 02:00（切日前）→ 前一日；04:00 整 → 次日；起止 UTC | 通过；消融（不减切日分钟）→ 红 |
| AC3 | `#foreignStoreOrMissingPolicyIsNotFound`；范围校验复用 `FoodBizStoreScope#path` | 通过 |
| AC4 | `#readableWithOrderViewWhichClerksAndTerminalsHave` | 通过 |
| AC5 | 本文 §2「时间字段约定」，依据 8091 实测响应与查库 | 已写 |
| 装配 | `FoodControllerRegistrationTest`（新控制器已登记） | 通过 |
| 币种缺省 | `#currencyIsNullWhenMarketIsMissingUnknownOrPortsAbsent` | 通过 |

## §6 对账二 · 设计 → 实现

与 §2 模块设计一致。偏差：组装器放在 `food-web/.../api/StoreProfileAssembler.java`（设计稿同名），门店卡用 `StoreDirectoryPort`、营业策略用 `StoreOperationService#getPolicy(at = 现在)`。市场缺省的处理从「回落 CN」改为「为空」——平台 `marketOf` 明确不兜底，设计稿写错，已改。
