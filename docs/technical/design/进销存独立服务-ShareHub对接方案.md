# 进销存独立服务 × ShareHub（powerbank）对接方案 · 服务侧

> 状态：**草稿 · 待评审**（2026-10-29）· 上游：[TDD-进销存独立服务与多业态](./TDD-进销存独立服务与多业态.md) · [ADR-046](../ADR/ADR-046-进销存独立为服务与业态档案化.md) · [执行方案](./进销存独立服务-执行方案.md) · [设备套上线与回滚手册](../进销存独立服务-设备套上线与回滚手册.md)
>
> **姊妹篇**：宿主侧对接方案在 powerbank 仓库 `docs/technical/库存微服务对接方案.md`（同日草稿）。那一篇写 ShareHub 怎么调用、本地怎么投影、G1 ~ G8 差距与切换步骤；**本篇只写服务这一侧**：ShareHub 实际用到的契约、开户流程、服务欠宿主的事、两边要一起拍板的事。两篇冲突处，以代码为准，并回头改文档。
>
> 证据来自两仓代码阅读（ai-shop 工作区含他人未提交改动，powerbank 截至 `4e8a7a51`），未重跑测试。

---

## 一、一句话

ShareHub 已经用**自写 HTTP 客户端**接上了本服务的内核 + `SERIAL` + `DISPATCH_POOL` 三块（28 个端点，一个业主凭证，系统码 `SHAREHUB`），本机联调 44 项通过；**离生产还差的不在接口本身，在三处缝**：写入不可恢复（宿主侧 G1）、迁移口径两边各做一套、设备套与凭证还没开。服务侧要补的是：给宿主的恢复手段讲清楚、导入接口补一类「已出库待签收」、配置与文档的名字对齐、仓库映射改按 `external_ref`。

---

## 二、为什么是这个接法

| 选项 | 结论 | 理由 |
|---|---|---|
| A. 宿主引用 `inventory-client` | **暂不** | 客户端里只有预留 / 系统级 / 控制台三类类型化接口，**没有内核、`SERIAL`、`DISPATCH_POOL`、事件的接口**；配置键写死 `shop.inventory.service.*`；也还没发 Nexus（TDD §7.2 Q5）。宿主用它反而要透传通用 `InventoryConsole` |
| B. 宿主自写 HTTP 客户端 + 内存 Fake（现状） | **采用到 M8 之后** | 只覆盖宿主用到的端点，契约漂移靠本机联调脚本发现。代价：服务改契约不会让宿主编译失败 |
| C. 宿主经 nginx 走公网 | 否 | 服务只绑回环（`SERVER_ADDRESS=127.0.0.1`），凭证是 `Bearer key:secret` 明文，不是签名 —— **ShareHub 必须与设备套同机部署** |

防住什么：选 B 但不补契约测试，等于把「接口变了」推迟到联调才发现。所以 §五 任务 S6 要求服务侧把 ShareHub 用到的 28 个端点固化成一组契约用例。

---

## 三、结构

### 3.1 部署与身份

| 项 | 测试 / 本机 | 生产 |
|---|---|---|
| 实例 | `TEST`，8086，放行 `SHOP,SHAREHUB,CHARGING`（本机现有一份只放行 `SHAREHUB`） | **设备套** `DEVICE`，`127.0.0.1:8087`，放行 `SHAREHUB,CHARGING` |
| 库 | `inventory_test` | `neargo_inv_device`（新库，不碰电商库） |
| 系统码 | `SHAREHUB`（`V16__service_platform.sql` 种入） | 同左 |
| 业主 | 一个：运营方，`externalRef=powerbank-<环境>` | 一个；宿主侧租户目前写死 `MAIN`，多运营方不在本期 |
| 凭证 | **业主凭证**（绑业主，不需要 `X-Inventory-Owner-Ref`） | 同左，只在设备套库里签发 |
| 写入方 | 新业主缺省 `SVC`，无需翻转 | 同左 |

请求头：`Authorization: Bearer <appKey>:<appSecret>` · `X-Inventory-Actor: STAFF:<userNo>` 或 `SYSTEM` · 写请求带 `Idempotency-Key`（≤ 64）· 可选 `X-Trace-Id`（宿主未发）。

### 3.2 业态档案（`SHAREHUB`）

真源是测试夹具 `inventory-domain/src/test/resources/profiles/sharehub.json`，开户时由平台运维 PUT 进去：

- 能力包：`SERIAL`、`DISPATCH_POOL`；**不开 `RESERVATION`**（调 `/reservations*` 得 403 `inventory.capability.disabled`）
- 目标类型：`MERCHANT`、`SITE`（上级 `MERCHANT`）、`PERSON`；宿主的 `onboard.sh` 另加了 `SUPPLIER`（退供应商、处置用）—— **夹具里没有，要补**（§五 S4）
- 入库用途：`PURCHASE`、`OPENING`（须依据与附件）、`RETURN`、`CORRECTION`、`COUNT_GAIN`
- 出库用途：`ISSUE`（须 `MERCHANT` + `PERSON`，`SITE` 可选，交接方式 `POOL`）、`SCRAP`、`RETURN_SUPPLIER`、`COUNT_LOSS`
- 放行凭证类型 `QC`；盘点须复核且复核人≠提交人

逐台 / 数量按**物料**定（`trackingMode`），不按档案：柜、宝为 `SERIAL`；宿主的耗材物料为 `QUANTITY`。

### 3.3 ShareHub 用到的端点

前缀 `/api/inventory/v1`，对应宿主 `HttpInventoryApi`：

| 能力 | 端点 | 宿主功能 |
|---|---|---|
| 主数据 | `PUT /items/by-ref/{ref}` · `PUT /external-targets/{type}/{ref}` · `GET/POST /warehouses` | 型号、物料、商家 / 站点 / 接收人 / 供应商、仓库（懒同步） |
| 入库 | `POST /inbounds` · `/{no}/post` · `/{no}/business-clearance` | 到仓待检入库、回仓入库与解冻、物料入库、迁移 |
| 出库 | `POST /outbounds` · `/{no}/post` | 发货 `ISSUE`、物料出库、处置、迁移 |
| 出库池 | `GET /dispatch-pool` · `POST /dispatch-pool/claims` · `/claims/{no}/release` · `/{id}/redirect` · `/{id}/revoke` | 签收、出库调整、出库单退回 |
| 查询 | `GET /balances` · `/serials` · `/ledger` · `/reports/period-summary` | 物料库存、预检、流水、库存报表 |
| 盘点 | `POST /counts` · `GET /counts/{no}` · `/scans` · `/submit` · `/approve` · `/reject` · `/post` · `/cancel` | 盘点页透传 |
| 事件 | `GET /events` · `POST /event-acks` | 每分钟拉取，只投影 `DispatchRedirected` / `DispatchRevoked` |

**服务有、宿主该用而没用的**：`GET /commands/{requestId}`、`GET /dispatch-pool/claims/by-request/{requestId}`（恢复用，§4.1）· `POST /serials/{sn}/assemble|disassemble`（柜挂宝，§4.4）· `POST /admin/v1/owners/{id}/imports`（迁移，§4.2）· `GET /in-transit` · `GET /actuator/info`（启动自检实例代号）。

---

## 四、详细设计

### 4.1 宿主写入的恢复手段（对应宿主侧 G1）

宿主现在**在本地事务里同步调服务，幂等键每次随机**（`RemoteInventoryPort.key()` 忽略业务引用、拼 UUID）。服务这边的幂等是按 `(system_code, owner_id, request_id)` 唯一、与写入同事务：同键同参回放原结果，同键异参 409 `inventory.command.conflict`。所以只要宿主**用稳定的命令号做键**，以下手段已经可用，服务侧不需要新接口：

| 场景 | 宿主做法 | 服务侧保证 |
|---|---|---|
| 回包超时 / 进程崩溃 | 用同一个键重发，或 `GET /commands/{requestId}` 查结果 | 已提交的命令回放原结果，不会重复记账 |
| 签收超时 | `GET /dispatch-pool/claims/by-request/{requestId}` | 返回原领取 |
| 远端已过账、本地回滚 | 补偿任务查 `/commands`，按结果补本地或调反向动作（`revoke`、`cancel`） | 反向动作本身也是幂等命令 |

服务侧要补的只有一条：**命令记录的保留期写进契约**。现状 `inv_command` 没有清理任务，等于永久保留；写明「≥ 90 天，清理前出公告」，宿主补偿窗口按它定（宿主侧 Q-5）。

防住什么：宿主改成「先落命令、再调用」后，服务这头如果哪天加了命令清理而不告知，补偿任务查不到结果会误判为「没执行」再执行一次 —— 回放保护失效。

### 4.2 迁移：两条路合成一条（对应宿主侧 G3 / Q-1）

| | 宿主 `InventoryMigration`（现状） | 服务 `imports?apply=` |
|---|---|---|
| 入库用途 | `PURCHASE` —— 让切换当期采购量虚增 | `OPENING` + 依据 `MIGRATION`，报表单列 |
| 已出库待签收的柜 | 先入默认仓再 `ISSUE` 进出库池 | **不导**（「已投放、不在仓的设备不导入」） |
| 冲突检测 / 空跑 / 重跑为空 | 有试跑，无冲突检测 | 有，任一冲突整批 409 |
| 仓库对应 | 按**名称**认领已有仓 | 按 `inv_location.external_ref` = 宿主仓号 |

建议：**用服务的导入接口**，服务侧补两件：

1. 批次加一类「在出库池」：`Pool(serialNo, warehouseRef, merchantRef, siteRef, personRef, issuedAt)`，导入时以 `OPENING` 入库后立即 `ISSUE` 进池，不领取。否则「已发货、商家未签收」的柜在服务里没有记录，切换后签收会 404。
2. 宿主的 `wh:` 映射改按仓号认领（`external_ref`），不按名称 —— 名称可改、可重名，导入建的仓与宿主懒建的仓会变成两个。

放行凭证：逐台设备由宿主在批次里给真实质检单号（TDD §7.2 Q3）；没有质检单的存量用 `MIGRATION:<批次号>`。

### 4.3 设备套开户与配置（对应宿主侧 G4）

手册第 1 ~ 3 步由**服务侧运维**执行，产出一份凭证交宿主：

1. 建库 `neargo_inv_device`、账号、**同一步加进备份清单**；装 jar、`inventory.env`、`neargo-inventory@device`；`/actuator/info` 的 `code=DEVICE`。
2. `POST /admin/v1/owners`（`systemCode=SHAREHUB`）→ `PUT /owners/{id}/profile`（§3.2，含 `SUPPLIER`）→ `POST /owners/{id}/credentials`；secret 只显示一次，直接写进宿主环境文件（600），不过聊天、不进仓库。
3. 共用任务服务登记目标 `INVENTORY_DEVICE`，密钥两边一致。

配置名字对不上的地方（**会让第 2 步当场失败**）：

| 项 | 文档写的 | 代码读的 | 后果 |
|---|---|---|---|
| 管理口令 | 执行方案 §二 有 `INVENTORY_ADMIN_TOKEN` | 读 `inventory.admin.token`，宽松绑定能吃到；但 `deploy/inventory.env.example` **没有这一行** | 照样例部署，管理端点全部 404，看上去像「端点不存在」 |
| 任务口令 | `INVENTORY_JOB_WORKER_TOKEN` | `INVENTORY_JOB_TOKEN` | 任务服务调用 401 |
| `INVENTORY_JOB_TARGET` / `_SERIAL_MAX_PER_COMMAND` / `_RESERVATION_DEFAULT_TTL` | 执行方案列了 | 代码不读（2000 写死） | 改了不生效 |
| 幂等冲突错误码 | 手册 §四：`inventory.idempotency_conflict` | `inventory.command.conflict` | 告警规则按文档写会漏 |

### 4.4 柜挂宝（对应宿主侧 G5 / Q-2）

服务按 TDD D3 已支持：柜、宝都是 `SERIAL`，`assemble` 把宝挂到柜下，扫柜码展开为柜 + 宝，出库池以柜为主、宝随柜调整 / 撤回 / 签收。宿主现在宝**只入库、不出库、不组装**，结果是宝在服务里永远「在仓」。

服务侧立场：两种都能接，**但不接受半套** —— 要么入库时按质检单附的柜内宝清单 `assemble`、随柜出库；要么一期宝不进服务（宿主不调 `items/by-ref/POWERBANK`）。由业务定，服务侧不需要改代码。

### 4.5 服务这一侧暂时接不住的

| 宿主动作 | 现状 | 方案 |
|---|---|---|
| 仓间调拨（`TransferOpsServiceImpl`） | 宿主不调服务，两边账开始分叉 | 内核已有调拨单（控制台 `docs/transfers` 在用）；给宿主开 `POST /transfers` + `ship` / `receive`，任务 S5 |
| 撤回到非原出库仓 | 服务冲回原仓，宿主只打警告 | 宿主撤回后补一张调拨；依赖 S5 |
| 仓内良品转次品库位 | 无宿主接口（TDD §7.3） | 同 S5，次品作库位 |

---

## 五、取舍记录与任务

### 5.1 取舍

| 冲突 | 让了谁 | 为什么 |
|---|---|---|
| 宿主要即时反馈 vs 写入可恢复 | 保留同步调用，补命令号 + 补偿 | 全异步（宿主早期 D 草稿）界面要多一个「处理中」状态；而服务的幂等回放已经够做恢复 |
| 系统码 `POWERBANK`（powerbank 仓 B 文档）vs `SHAREHUB`（代码、种子、联调） | `SHAREHUB` | 已种进库、联调与档案都按它；改的是 B 文档 |
| 宿主自写迁移 vs 服务导入 | 服务导入 + 补「在出库池」一类 | 冲突检测与「重跑为空」这个完成判据只有服务那边有 |

### 5.2 服务侧任务

| # | 任务 | 优先级 |
|---|---|---|
| S1 | `inventory.env.example` 补 `INVENTORY_ADMIN_TOKEN`；执行方案 §二 的 `JOB_WORKER_TOKEN` 改为 `JOB_TOKEN`，删掉代码不读的三项（或让代码读）；手册 §四 错误码改 `inventory.command.conflict` | **P0** |
| S2 | 契约写明 `inv_command` 保留期 ≥ 90 天 | **P0** |
| S3 | 导入批次加「在出库池」一类，带空跑与冲突 | **P0** |
| S4 | `sharehub.json` 夹具补 `SUPPLIER` 目标类型，与宿主 `onboard.sh` 一致 | P1 |
| S5 | 宿主侧调拨接口 `POST /transfers` + `ship` / `receive` | P1 |
| S6 | 把 §3.3 的 28 个端点固化成服务侧契约用例（类比 `ReservationClientContractTest`） | P1 |
| S7 | 生产形态演练、设备套建库上线（手册第 1 ~ 3 步） | **P0**，排在 S1 之后 |

宿主侧任务见姊妹篇 §十（命令先落库、每日对账、迁移改走导入、生产配置与实例自检等）。顺带一处宿主脚本缺陷：`backend/scripts/inventory-local/onboard.sh` 末行读 `inv-cred.json` 而非 `$OUT`，回显的 appKey 是旧文件里的。

---

## 六、待确认

| # | 事项 | 建议 | 卡住谁 |
|---|---|---|---|
| J1 | 迁移走服务导入接口（含 S3） | 走 | 宿主任务 3、上线第 3 步 |
| J2 | 宝：组装随柜，还是一期不进服务 | 业务定 | 宿主任务 5 |
| J3 | 客户端：自写到 M8 之后，再评估 `inventory-client` 补内核接口 | 同意 | 无（S6 先兜契约） |
| J4 | 设备套上线时间与执行人（S7） | 排在生产形态演练之后 | 宿主切 `SERVICE` |
| J5 | 系统码统一为 `SHAREHUB`，powerbank B 文档随改 | 同意 | 开户 |
| J6 | 调拨接口（S5）进不进上线前 | 进；否则切换后第一次调拨就分叉，只能靠盘点纠正 | 宿主调拨功能 |
