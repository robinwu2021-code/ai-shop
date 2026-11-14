# POS 安卓 App · 技术栈与架构

状态：草稿 · 创建 2026-10-24
关联需求：[餐饮系统-需求矩阵](../../requirements/餐饮系统-需求矩阵.md)（C / D / E / F / G / H / I / K 组）
关联方案：[TDD-餐饮POS点餐收银界面](../TDD-餐饮POS点餐收银界面.md) ·
[TDD-支付服务-台湾阿联酋通道与开放API](../TDD-支付服务-台湾阿联酋通道与开放API.md)（§2.8.5 终端 App）·
[TDD-统一试算引擎与餐饮结算](../TDD-统一试算引擎与餐饮结算.md) ·
[ADR-001](../ADR/ADR-001-商家端形态与拆分时机.md)（「蓝牙小票打印、常驻保活时再评估 App」—— POS 正是这个触发条件）

> 本篇是**架构方案**，不动契约。端点、库表、权限码落地时各走各域的 TDD（见 §7 端点依赖）。

---

## §0 一句话

**原生 Kotlin + Jetpack Compose 的独立安卓工程**，按「表现 → 应用 → 领域 → 数据 → 设备」五层组织；
业务上按「场景 → 终端领域能力 → 共享能力」三层切，**金额、库存、支付结果的权威永远在服务端**，
终端只持有「可离线继续营业」所需的最小副本与待同步队列。

---

## §1 定位与边界

### 1.1 它是什么

门店里**常驻、接外设、要断网能撑住**的收银终端：商米 / 联迪类一体机、双屏收银机、带读卡模块的智能 POS、
安卓平板 + 外接打印机。覆盖：点餐收银、桌台、送厨分单打印、收款（码 / 卡 / 现金 / 储值）、当班现金与日结、
刷卡机日结对账。

### 1.2 与现有几样东西的关系

| 现有 | 关系 |
|---|---|
| `b-app` 的 `pages/pos/index`（uni-app，[TDD-餐饮POS点餐收银界面](../TDD-餐饮POS点餐收银界面.md)） | **轻形态**：无外设、在线、手机 / 平板临时收银与管理。POS App 是**重形态**。两者共用同一套服务端契约、i18n 键、设计令牌、交互规格（AC1–AC16 两边都要满足），不共用 UI 代码 |
| `android-shell` | WebView 预览壳，**不是**本工程的起点，也不往里加原生 SDK（见其 README） |
| DCloud 离线打包的「邻里商家」 | 商家管理 App，不承担收银外设与离线 |
| 支付 TDD §2.8.5「刷卡机终端 App（新工程，安卓）」 | **就是本工程的支付子域**。终端身份、激活、设备密钥签名、店长授权码、终端日结按那篇执行，本篇不重复定义 |

### 1.3 为什么不是 WebView / uni-app 套壳（备选与否决理由）

| 方案 | 否决或保留的理由 |
|---|---|
| A · 原生 Kotlin + Compose（**选定**） | 读卡套件、内置打印、副屏、钱箱、扫码头都是原生 AAR；离线库与后台同步需要常驻进程；收银高频操作要稳定 60fps |
| B · 原生内核 + 本地打包的 Vue 页面（WebView） | 能复用 b-app 的 POS 组件，但：国内 POS 机型系统 WebView 常停在旧 Chromium 且不能经商店升级；读卡页面必须原生（PCI 范围）；离线时 TS 侧的购物车规则与 Kotlin 侧的本地权威要两份。**保留为降级方案**：若首批机型全部 Android 10+ 且 WebView ≥ 110，可重新评估 |
| C · uni-app 离线包 + 原生插件 | 原生插件做 Room / 局域网主机 / 读卡套件，插件边界比方案 B 还厚；DCloud 离线 SDK 不在仓库内，CI 不可复现 |

---

## §2 质量约束（决定架构形状的那几条）

| # | 约束 | 来源 | 架构上的后果 |
|---|---|---|---|
| Q1 | 断网可点餐、送厨、现金收款；扫码类不可用 | FNB-K03 · POS TDD AC15 | 本地库 + Outbox + 幂等键；支付方式按「离线可用」打标 |
| Q2 | 恢复后冲突可认领、可重放 | FNB-K04 · FNB-I04 | 每条离线事实带终端号 + 本地序号 + 设备时钟 + 营业日；同步结果有「被拒」状态，进人工认领 |
| Q3 | 我方 App 不出现明文卡号 | 支付 TDD §2.8.5 | 读卡段隔离成独立模块，只吐通道加密数据；该模块不依赖任何业务模块 |
| Q4 | 金额以服务端报价为准，变化要确认 | POS TDD AC2 · 统一试算引擎 | 终端不算权威价；离线时本地估算必须标「估」，上线后重报价 |
| Q5 | 打印失败不阻塞送厨，可补打不重投 | FNB-H01/H05 | 打印是独立任务队列，有回执状态机，与订单状态解耦 |
| Q6 | 敏感操作有授权人与原因 | FNB-G08 · AC28 | 授权码只在服务端校验（离线时按门店预下发的离线授权策略，见 §6.4） |
| Q7 | 三语 + 阿拉伯语 RTL；金额 LTR | POS TDD AC16 | Compose `LayoutDirection` 跟随语言；金额组件强制 LTR |
| Q8 | 多区域（CN / TW / AE）、多通道 | 支付 TDD | 区域差异全部走服务端下发的能力开关，不打多套包 |
| Q9 | 设备会丢、会被拆 | 支付 TDD §2.8.5 | 设备密钥进 Android Keystore，可远程吊销；本地库加密 |

---

## §3 技术栈

| 层面 | 选型 | 说明 |
|---|---|---|
| 语言 / 构建 | Kotlin 2.x · Gradle KTS + Version Catalog · AGP 最新稳定 | `minSdk 26`（Android 8.0），`targetSdk` 跟当年要求；低于 8.0 的老机型不支持 |
| UI | Jetpack Compose + Material 3（自定义主题） | 主题色、间距、圆角由 `packages/shared/src/design/tokens.ts` 的 POS 段**生成** Kotlin 常量（见 §8） |
| 导航 | Navigation Compose（类型安全路由） | 单 Activity；副屏用 `Presentation` |
| 架构模式 | MVVM + 单向数据流（`UiState` / `UiEvent`） | ViewModel 只调用例，不直接碰 Repository |
| 依赖注入 | Hilt | 设备驱动按机型在 DI 层选实现 |
| 并发 | Kotlin Coroutines + Flow | 本地库变更以 Flow 推到 UI |
| 本地存储 | Room（SQLite）+ SQLCipher 加密；DataStore 存轻配置 | 迁移脚本必须有 Room migration test |
| 网络 | OkHttp + Retrofit + kotlinx.serialization | 签名拦截器统一加 `X-Terminal-Sn / X-Timestamp / X-Nonce / X-Signature / X-Operator` |
| 契约 | 从 `docs/api/*.yaml`（OpenAPI）生成 DTO 与接口 | 不手写 DTO；契约改了生成物会 diff |
| 后台任务 | WorkManager（同步、日志上传、升级下载）+ 前台服务（常驻收单 / 局域网主机） | |
| 局域网（P4） | Ktor Server（嵌入式）+ NSD/mDNS 发现 | 一台做「门店主机」，其余终端断外网时连它 |
| 外设 | ESC/POS（USB / 蓝牙 / 网口）· 厂商内置打印 SDK · 扫码头（键盘楔 / 广播）· CameraX + ZXing-cpp · 钱箱（经打印机弹开指令）· 副屏 `Presentation` | 国内机型多无 GMS，**不用 ML Kit / Firebase** |
| 读卡 | 通道认证读卡套件（蓝新 / PayBy 提供，Q12 待定） | 独立模块，见 §6.2 |
| 安全 | Android Keystore（设备 HMAC 密钥）· 证书固定 · Play Integrity / 厂商安全接口（可选）· R8 混淆 | |
| 可观测 | Timber + 本地滚动日志文件 + 定时上传；崩溃上报按区域选（国内 Bugly / 海外 Sentry 自建） | 日志脱敏：手机号、会员号打码，永不记卡数据 |
| 升级 | 自建升级服务（按 `pay_terminal.app_version` 灰度）+ 厂商 MDM（如有） | 强制升级只用于安全修复（支付 TDD） |
| 测试 | JUnit5 · MockK · Turbine · Robolectric · Compose UI Test · Room migration test | 领域层纯 Kotlin，单测不需要安卓环境 |
| 静态检查 | detekt · ktlint · Android Lint · 依赖方向检查（见 §4.3） | |
| 金额 / 时间 | 金额一律 `Long` 最小货币单位（与 POS TDD 的 `unitMinor` 一致）· `java.time` · 营业日由服务端下发 | 禁止 `Double` 表示金额 |

---

## §4 技术分层

### 4.1 五层

```
┌──────────────────────────────────────────────────────────────────────┐
│ 表现层 Presentation                                                  │
│  Compose 屏幕 · 组件库(pos-ui) · ViewModel(UiState/UiEvent) · 副屏    │
├──────────────────────────────────────────────────────────────────────┤
│ 应用层 Application（用例）                                            │
│  SendToKitchen · TakePayment · VoidLine · OpenTable · CloseShift …   │
│  编排领域对象 + 仓储 + 设备；事务边界、授权检查、幂等键在这一层产生      │
├──────────────────────────────────────────────────────────────────────┤
│ 领域层 Domain（纯 Kotlin，无 Android 依赖）                           │
│  实体/值对象：Order · Line · Table · Payment · Shift · Money          │
│  规则：行合并、状态机、离线可用判定、授权需求判定                         │
│  端口接口：OrderRepository · QuoteService · PrinterPort · CardReaderPort│
├──────────────────────────────────────────────────────────────────────┤
│ 数据层 Data                                                          │
│  Repository 实现 · Room DAO · Retrofit API · Outbox · SyncEngine      │
│  本地副本(菜单/桌台/配置快照) · 冲突与回执处理                          │
├──────────────────────────────────────────────────────────────────────┤
│ 设备与平台层 Device / Platform                                        │
│  打印驱动 · 扫码 · 钱箱 · 副屏 · 读卡套件适配 · Keystore · 网络状态       │
│  按机型选实现：Sunmi / Landi / 通用 ESC-POS / 模拟器 Fake               │
└──────────────────────────────────────────────────────────────────────┘
        横切：鉴权签名 · 日志审计 · 配置与能力开关 · i18n · 错误码映射
```

**依赖方向只能向下 + 向领域层的端口**：数据层与设备层**实现**领域层定义的端口接口（依赖倒置），
领域层不知道 Room、Retrofit、哪家打印机。

### 4.2 Gradle 模块

```
pos-android/
├─ app/                       # 组装：Application、DI 图、单 Activity、导航图
├─ core/
│  ├─ core-model/             # Money、Id、营业日、错误码（纯 Kotlin）
│  ├─ core-ui/                # pos-ui 组件库 + 主题（含生成的令牌）+ RTL 工具
│  ├─ core-i18n/              # 生成的 strings（zh-CN / en / ar）
│  ├─ core-network/           # OkHttp、签名拦截器、生成的 OpenAPI 客户端
│  ├─ core-database/          # Room、SQLCipher、迁移
│  ├─ core-sync/              # Outbox、SyncEngine、冲突队列
│  ├─ core-security/          # Keystore、设备激活、授权码提交
│  └─ core-observability/     # 日志、崩溃、指标、脱敏
├─ device/
│  ├─ device-api/             # PrinterPort、ScannerPort、DrawerPort、CustomerDisplayPort
│  ├─ device-sunmi/  device-landi/  device-escpos/  device-fake/
│  └─ device-cardreader/      # 读卡套件适配，只依赖 device-api 与 core-model（Q3）
├─ feature/                   # 业务模块，见 §5；每个 = domain + data + ui 三个子包
│  ├─ feature-terminal/  feature-shift/  feature-menu/  feature-ordering/
│  ├─ feature-table/  feature-kitchen-print/  feature-checkout/  feature-payment/
│  ├─ feature-aftersale/  feature-cash/  feature-member/  feature-settings/
└─ build-logic/               # 约定插件：统一 Kotlin/Compose/detekt 配置、依赖方向规则
```

### 4.3 依赖规则（写成构建检查，不靠自觉）

1. `feature-*` 之间**不直接依赖**；跨业务协作经 `app` 层的用例编排或领域事件（例：收款成功 → 订单置已付 → 触发打印）。
2. `*-domain` 包不得 import `android.*`、`androidx.room.*`、`retrofit2.*`。
3. `device-cardreader` 不得被任何 `feature-*` 以外的模块依赖，且只有 `feature-payment` 能依赖它。
4. UI 只经 ViewModel 调用例；Compose 函数里不出现 Repository。

---

## §5 业务分层

### 5.1 三层

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 场景层（收银员看到的工作台，跨多个领域编排）                                 │
│  开班 → 桌台总览 → 点餐 → 送厨 → 加菜/退菜 → 预结 → 结账 → 交班 → 日结       │
│  快餐/外带流程（无桌） · 刷卡机独立收款（只收款不点餐）                        │
├─────────────────────────────────────────────────────────────────────────┤
│ 终端领域能力层（feature-*，每个有自己的本地状态与同步规则）                    │
│  桌台  点餐  制作与打印  结账  支付  退餐与内控  现金与班次  会员  菜单          │
├─────────────────────────────────────────────────────────────────────────┤
│ 共享能力层（与业务无关、所有领域都用）                                        │
│  终端身份与操作员 · 授权 · 同步 · 打印队列 · 设备 · 配置与能力开关 · 审计日志    │
├─────────────────────────────────────────────────────────────────────────┤
│ 服务端权威域（不在终端，终端只调用）                                          │
│  餐饮核心(订单/桌台/制作) · 统一试算引擎 · 支付服务 /terminal/pay/v1 ·        │
│  会员资产(储值/券/积分) · 共享打印路由 · 日结编排 · 身份与权限                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 业务模块与需求矩阵对照

| 模块 | 负责 | 需求矩阵 | 终端持有的本地状态 | 离线 |
|---|---|---|---|---|
| `feature-terminal` | 激活、心跳、设备自检、版本 | A08 · H07 · 支付 AC28 | 终端号、设备密钥（Keystore）、能力开关快照 | 激活必须在线 |
| `feature-shift` | 收银员登录、开班、交班 | A07 · I01–I02 | 当前班次、操作员 | 可（缓存过的操作员） |
| `feature-menu` | 分类、菜品、规格、沽清 | B 组 · CAT01 | 菜单快照（带版本号） | 读快照；沽清以服务端为准 |
| `feature-table` | 开台、人数、换桌、并台、清台 | C05–C08 | 桌台快照 + 本地操作日志 | 可（主机模式下跨终端一致） |
| `feature-ordering` | 购物车、行合并、送厨批次、转菜、拆合单 | D02–D06 · C07 | 草稿、已送厨行 | 可 |
| `feature-kitchen-print` | 工位分单、厨房票、补打、回执 | H01–H05 | 打印任务队列 + 回执 | 可（局域网打印机） |
| `feature-checkout` | 报价展示、优惠 / 服务费 / 抹零分项、预结单 | F01 · F06/F07 | 最近一次服务端报价 + 版本 | 只出「估算」，不能确认优惠 |
| `feature-payment` | 选方式、现金找零、扫码、刷卡、撤销、查单、终端日结 | F02/F04 · 支付 AC11/AC28/AC29 | 在途收单单、终端当日汇总 | 仅现金 / 获准的刷卡离线；扫码不可用 |
| `feature-aftersale` | 退菜、赠送、整单折扣、退款申请 | G01/G02/G08 · D06 | 待审批申请 | 申请可排队，审批在线 |
| `feature-cash` | 备用金、非销售收支、盲盘、日结预检 | I01–I06 | 钱箱台账 | 可记录，签核在线 |
| `feature-member` | 识别会员、调用储值 / 券 | 会员资产域 | 不缓存余额 | 不可 |
| `feature-settings` | 布局（L1–L6）、皮肤、语言、打印机绑定 | POS TDD AC16 · H01 | 本机设置 | 可 |

### 5.3 权威归属（这张表决定离线能做什么）

| 事实 | 权威 | 终端在离线时的做法 |
|---|---|---|
| 价格、优惠、服务费 | 服务端统一试算引擎 | 本地估算 + 「估」标记；上线重报价，差额走 AC2 确认 |
| 储值余额、券、积分 | 会员资产域 | **不可用**（不承诺余额） |
| 收款结果 | 支付服务 / 通道回执 | 现金：本地记账并同步；卡：以读卡套件回执为准、日结对账兜底；码：不可用 |
| 下单与送厨事实 | 终端先产生，服务端接收 | 本地落库 + Outbox，带幂等键，服务端去重 |
| 桌台占用 | 服务端（在线）/ 门店主机（局域网离线） | 单机离线时只允许本机操作本机开的台 |
| 营业日、班次切点 | 服务端配置 | 用最近快照；跨切点的离线单按设备时钟归属并标记待核 |
| 授权（退菜、赠送、撤销） | 服务端校验授权码 | 见 §6.4 |

---

## §6 关键机制

### 6.1 离线与同步

```
用例 ──▶ 本地事务（业务表 + outbox 同一个 Room 事务）
               │
               ▼
        SyncEngine（WorkManager + 网络恢复触发）
          按 outbox 序号逐条发送，带 Idempotency-Key = 终端号:本地序号
               │
     ┌─────────┼──────────────┐
     ▼         ▼              ▼
   成功      可重试失败        业务拒绝（如桌台已被占、菜已沽清）
  标记已同步  指数退避          进「冲突队列」→ 收银台提示 → 人工认领（FNB-K04）
```

- 同一订单的事件严格保序；不同订单可并行。
- 日结预检（FNB-I04）读冲突队列与未同步数：**有未同步不能正式日结**。

### 6.2 支付

- 终端只调 `/terminal/pay/v1/**`，不持有任何通道密钥；签名规则见支付 TDD §2.8.5。
- 收单单状态机终端侧只做**镜像**：`CREATED → PAYING → (SUCCESS | FAILED | UNKNOWN)`；
  `UNKNOWN` 必须**先查单**，不允许直接关单或换方式重收（支付 TDD AC11「绝不出现终端已扣、我方已关」）。
- 读卡：`CardReaderPort.readAndEncrypt(amount) → EncryptedCardData`，适配层把套件结果转成不透明字节串交给服务端；
  该模块不写日志正文、不进崩溃上报的内存快照。

### 6.3 打印

- 送厨提交成功（或入 Outbox）后，按工位路由生成打印任务；任务内容是**提交时的冻结快照**（FNB-H02）。
- 任务状态：`QUEUED → SENT → (ACKED | FAILED | UNKNOWN)`；`UNKNOWN` 不自动重投，提示人工确认后「补打」（带补打标识，FNB-H05）。
- 打印机故障只点亮系统栏打印入口的警告，不回滚订单（POS TDD AC14）。

### 6.4 授权

- 在线：授权码随请求提交，服务端校验，终端不存。
- 离线：只允许门店配置为「离线可授权」的动作（默认仅退未送厨菜），授权凭证记入 Outbox，上线后服务端补校验，
  校验不过进冲突队列。撤销收款、退款**永远不允许离线**。

### 6.5 安全

- 激活：激活码 + 设备指纹 → 服务端下发设备密钥 → 以 `KeyProtection` 导入 Keystore（不可导出）。
- 挂失后服务端返回特定错误码，终端立即清本地会话、锁屏到「已停用」，本地库保留待运维导出。
- 本地库 SQLCipher 密钥由 Keystore 包裹；导出 / 调试菜单需店长授权并留审计。

### 6.6 升级与配置

- 启动与心跳时上报 `app_version`、`reader_kit`；服务端回「可升级 / 必须升级 / 能力开关快照」。
- 营业中不打断：普通升级在交班或空闲窗口提示，安全升级可强制。

---

## §7 与服务端的端点依赖

POS TDD 列的 E1–E8 是本 App 的同一批依赖，外加终端管理：

| # | 能力 | 现状 | 交付方 |
|---|---|---|---|
| E0 | 终端激活 / 心跳 / 挂失 / 日结 `/terminal/**` | 支付 TDD 已设计，未实现 | 支付服务 |
| E1 | 桌台列表与操作 | 未实现 | 餐饮核心 |
| E2 | 菜单读取（含版本号、增量） | 部分（`/internal/product/dish-offers/batch`） | 商品 / 餐饮 |
| E3 | 草稿与送厨提交（幂等键） | 未实现 | 餐饮核心 |
| E4 | 报价 | 设计中 | 统一试算引擎 |
| E5 | 支付下单与回执 | 设计中 | 支付服务 |
| E6 | 打印路由与回执 | 未实现 | 共享打印 |
| E7 | 主管授权与审计 | 未实现 | 身份审批 |
| E8 | 离线批量同步与冲突回执 | 未实现 | 餐饮核心 |

**E2 与 E3 的契约要求**：菜单要能按版本号取增量；所有写端点必须接受 `Idempotency-Key`，否则离线重放会重复下单。

---

## §8 与本仓库的对接（防止两套真源）

| 共享物 | 真源 | POS App 怎么拿 |
|---|---|---|
| 设计令牌 / POS 皮肤 | `packages/shared/src/design/tokens.ts` 的 POS 段（POS TDD 计划新增） | `gen:skins` 增加 Kotlin 输出目标，生成到 `core-ui` |
| i18n 词条 | `b-app/src/i18n/locale/*.ts` 的 `pos.*` 命名空间 | 脚本导出为 Android `strings.xml`（三语），键名保持一致 |
| API 契约 | `docs/api/*.yaml` | OpenAPI Generator 生成 Kotlin 客户端 |
| 交互规格与验收 | POS TDD 的 AC1–AC16 | 两端各写测试，AC 编号相同，方便对账 |
| 界面清单 | `docs/technical/design/ui-catalog.json` | 新端 `pos`：需给 `gen-ui-catalog.py` 加一个读取 POS App 路由表的来源（落地时单独改） |
| 原型 | `prototypes/food-cashier-tablet.html`（POS TDD 引用的 `pos-workbench.html` 尚未入库） | 视觉真源，App 不再另画 |

工程放置：**独立仓库**（与 `ai-hxkey` 同样的处理），理由是构建链（Gradle / Android SDK / 签名）与本仓库的 Node + Maven 完全不同，
pre-push 闸门不该互相拖累；共享物经上表的生成脚本单向流入。

---

## §9 分期

| 期 | 范围 | 对应 |
|---|---|---|
| P0 骨架 | 工程、五层模块、令牌与 i18n 生成、设备 Fake、终端激活与签名 | E0 |
| P1 在线点餐收银 | 桌台、点餐、送厨打印、报价、现金 + 扫码收款、预结单 | E1–E6 · AC1–AC14 |
| P2 内控与班次 | 授权、退菜赠送、开班交班、钱箱、日结预检 | E7 · I01–I06 |
| P3 刷卡 | 读卡套件接入、撤销退款、终端日结对账 | 支付 AC28/AC29 · 依赖 Q12 |
| P4 离线 | Outbox 全量化、局域网门店主机、冲突认领 | E8 · K03/K04 |

> Outbox 与幂等键从 **P0 就要有**（哪怕 P1 只在线），否则 P4 要回头改每一个写用例。

---

## §10 待决

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| D1 | 首批机型清单（品牌、Android 版本、是否双屏、是否带读卡） | `minSdk`、`device-*` 实现数量、方案 B 是否复活 | 先定 1 款主力机 + 通用平板 |
| D2 | 读卡套件（支付 TDD Q12） | P3 能否开工 | 两家通道商务清单 |
| D3 | 是否需要局域网门店主机，还是单机离线即可 | P4 工作量差一倍 | 看连锁门店规模与断网频率 |
| D4 | 独立仓库还是放进本仓库 `pos-android/` | 闸门、CI、生成脚本路径 | 建议独立仓库（§8） |
| D5 | 离线可授权动作的白名单 | §6.4 | 由店长在商户端配置，默认最小集 |
