# TDD-业务编码统一生成

状态：批 1-3 实现完成，全量后端 2597/0/0（待提交）
档位：2（新生成算法 = 架构级准不可逆；跨系统；改所有新码格式 = 对外契约）
关联：[ADR-033 业务编码统一生成](ADR/ADR-033-业务编码统一生成.md)
创建：2026-10-09

## §0 对账一 · 需求 → 设计

用户：「优化订单编号逻辑，梳理其他业务编码，整合到一个工具统一处理。」
拍板（2026-10-09）：订单号目标＝多实例不碰撞 + 不可枚举 + 更短 + 带日期；整合＝换统一算法；随机段 10 位。

| AC | 需求 | 落点 |
|---|---|---|
| AC1 | 一个工具统一生成，格式 `<前缀>yyMMdd<10随机>` | `BizKey.next` 重写 |
| AC2 | 多实例不碰撞 | 无共享计数器（纯随机 + 日期） |
| AC3 | 不可枚举 | 随机段替掉递增 seq |
| AC4 | 更短 | 前缀+21 → 前缀+16 |
| AC5 | 运营可读日期 | yyMMdd 段 |
| AC6 | 前缀全唯一（修 5 对撞车） | 常量改值 + 守卫 |
| AC7 | 旁路收回（员工号不再自己拼） | 改 `OpsServiceImpl:1000` 为 `BizKey.next(STAFF)` |
| AC8 | 禁止将来再 inline 生成 | 守卫 `BizKeyConventionTest`（字面量前缀 `setXxxNo` + 同语句易变源） |
| AC9 | 反解前缀的 parser 不被新格式破坏 | `MpStoreController` 的 `startsWith` 在其输入域仍正确，**无需改** |

**孤立项**：无。

> **复核修正（2026-10-09，批 2 落地时）**：原计划写「2 处旁路」「改 `MpStoreController` 精确化」，核查后收窄：
> - **规格码不是旁路**。`SpecLibraryServiceImpl` 里 `"M"+hash` 是 `setCode`（规格**维度/值的内部局部码**，`M`=商家域、短码、维度内引用），
>   真正的业务键 `valueNo`/`dimNo` 早已走 `BizKey.next(SPEC_TEMPLATE)`。把它收进 BizKey 会把两个概念混为一谈，故不动。
> - **`MpStoreController` 的 `startsWith("M")`/`startsWith("ST")` 不是雷**。该控制器的 `no` 入参只会是商家号或门店号，
>   两者前缀互斥（`M` vs `ST`），判定在此输入域里确定且正确；`startsWith` 本身是读侧耦合、与本次格式改动无关，不改。

## §1 现状与根因

- `BizKey.next(prefix)` = `前缀 + yyyyMMddHHmmss(14) + seq(4,每JVM mod 10000) + rand(3)`，123 处调用。
- 四毛病（见 ADR）：多实例碰撞 / 可枚举 / 长 / 到秒时间戳无用。
- **5 对前缀撞车**，且 `startsWith("ST")` 反解 → 功能雷。
- **1 处旁路**：员工号 `"E"+System.currentTimeMillis()%1e8`（`OpsServiceImpl:1000`）。全仓扫
  `setXxxNo("字面量"+易变源)` 仅此一处（规格码那三处是 `setCode` 的维度内部码，不在列，见 §0 复核修正）。
- 订单号/子单号/支付/结算有唯一索引（随机算法的 DB 兜底成立）。
- **隐性耦合两处**（旧码「前缀+时间戳+递增seq」的字符串序恰好=创建序，被默默当排序用）：
  `OrderServiceImpl` 落店候选、`BizIdentityResolverImpl` 默认店解析。随机段一上字符串序变任意序 → 落到别家、不报错。

## §2 方案（= 实施计划，分三批）

### 契约变更

- **对外 JSON / 库表值**：所有**新生成**的业务码格式变（旧码不动，共存）。
- 端点 / 权限码 / i18n / 配置项：无。

### 前缀撞车修复（留载前缀的、改另一个）

| 前缀 | 留（被用/被反解多的） | 改 |
|---|---|---|
| ST | STORE（3 处 + startsWith 反解） | STAFF（0 处）→ **STF** |
| CM | CAMPAIGN | CHANNEL_MESSAGE → **CHM** |
| MT | MEMBER_TAG | MATERIAL → **MAT** |
| PP | PICKUP_POINT（3 处） | POINTS_POOL → **PPL** |
| SL | CONTENT_SLOT | STAFF_LOG → **SFL** |

新值 STF/CHM/MAT/PPL/SFL 已核不与任何现有前缀相等。存量：被改名的类型旧码保留旧前缀，无按前缀反解它们的代码。

### 分批

| 批 | 内容 | 判据 |
|---|---|---|
| **1** | `BizKey.next` 重写（日期+Crockford随机）+ 修 5 对前缀 + `BizKeyTest`；附带修两处隐性排序耦合（见 §1） | AC1-AC6 |
| **2** | 收 1 处旁路进 `BizKey.next`（员工号 → STF）；`MpStoreController` 复核确认无需改（AC9 转为「验证不受影响」） | AC7 AC9 |
| **3** | 加 `BizKeyConventionTest`（禁止再 inline 生成业务码） | AC8 |

### 明确不做

- 不改存量数据、不加分隔符、不定长前缀（见 ADR）。
- 不动 123 个调用点（它们调 `BizKey.next(BizKey.XXX)`，重写内部即自动生效）。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 跑过 | 消融 |
|---|---|---|---|
| AC1/AC4/AC5 | `BizKeyTest#格式=前缀+yyMMdd+10随机` | ✅ 6/6 | 长度改回 → 红 |
| AC2/AC3 | `BizKeyTest#随机段真随机_不含递增与秒时间戳` | ✅ | 换回 seq → 红 |
| AC3 | `BizKeyTest#Crockford字母表不含ILOU` | ✅ | 放进 I → 红 |
| 碰撞 | `BizKeyTest#十万次无重复` / `#并发5万无重复` | ✅ | 随机段缩到 2 位 → 红 |
| AC6 | `BizKeyTest#任意两个前缀常量不相等`（反射扫 >60 个常量） | ✅ | 留一对撞车 → 红 |
| AC8 | `BizKeyConventionTest#noInlineBusinessKeyGeneration` + `#theDetectorActuallyFires`（自检） | ✅ | 合成坏样本不报 → 自检先红 |

## §6 对账二 · 设计 → 实现

- **批 1**：`BizKey.next` 重写（`FMT=yyMMdd`、`CROCKFORD`、`RAND_LEN=10`、`SecureRandom`，去 `AtomicInteger`）；
  5 对前缀改值（STF/CHM/MAT/PPL/SFL）；`BizKeyTest` 6 条。
  附带两处隐性排序耦合：`OrderServiceImpl`（落店候选去掉 `.sorted()`，用 `own` 自带的 id 升序=创建序）、
  `BizIdentityResolverImpl`（默认店改一条 `is_default DESC, id ASC` 有序查，过滤到授权候选取首个）。
  **全量后端 2597 测试**：改格式先暴露 15F+1E（全 Store 系），修两处耦合 → 2F，再治翻页脆弱 → **0F**。
  - 翻页脆弱（非生产 bug）：`StoreScopedVisibilityFlowTest` / `StoreStockFlowTest` 里查 CM001 目录的用例单独跑绿、全量红，
    且**失败在用例间漂移**（每轮随机 ID 不同，红的那两条就不同）——典型共享库顺序依赖。根因：`/mp/goods` 服务端 `size` 封顶 50，
    CM001 是共享种子社区、全量跑累积大量货；旧单调 ID 下目标货排在前 50，随机段后落到任意一页，「只看第一页」成了随机假红/假绿。
    修法：两类各自的共享 helper `buyerSees` / `catalogRow` 改成**翻页遍历所有页**（按货号精确命中），
    一次性覆盖两类全部调用方、不依赖标题，语义不变。（先试「加唯一 keyword 收窄」只修了当轮那两条，下轮漂到别的用例——
    证明必须治根因、按页遍历。）另 `savingSameSubsetTwiceDoesNotCollide` 与另一用例共用手机号的跨测试污染 → 给独立号 `12600180019`。
- **批 2**：`OpsServiceImpl:1000` 员工号改 `BizKey.next(BizKey.STAFF)`（staffNo 无前缀反解、列 VARCHAR(64) 够长）。
- **批 3**：`BizKeyConventionTest`（arch 包，扫 backend 全模块 main 源码，带自检对照量）。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 用户拍板方向与随机段长度；档位 2；ADR + 本 TDD；开始批 1 |
| 2026-10-09 | 批 1-3 实现；全量后端 2597/0/0；复核收窄 AC7/AC9（规格码非旁路、`MpStoreController` 不改） |
