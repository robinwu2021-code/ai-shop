# TDD-接口耗时与慢日志

状态：**已实现**（2026-10-08）
档位：1（新增配置项 `shop.obs.*` = 契约；不动端点、不动库表、不加权限码）
关联需求：用户 2026-10-08「优化日志，记录 api 调用时长，超过 1s 记录到慢日志」
+「要方便将来快速查找问题，定期根据慢日志快速监控系统状态」
创建：2026-10-08 · 最后更新：2026-10-08

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 |
|---|---|---|
| AC1 | 每个 API 请求记一行，含方法、接口、状态码、耗时 | 新 `ApiAccessLogFilter`（全局）→ logger `api.access` INFO |
| AC2 | 耗时 ≥ 阈值（默认 1000ms）的**额外**进慢日志，能单独捞出来 | 同一过滤器 → logger `api.slow` WARN |
| AC3 | 接口用**路径模板**不用原始路径；拿不到模板时对号段脱敏 | `BEST_MATCHING_PATTERN` + 兜底脱敏 |
| AC4 | 不记请求体、查询串、手机号等 | 过滤器只取方法/模板/状态/耗时/号 |
| AC5 | 排除健康探针与 SSE 长连接 | `/actuator/**`、`/ops/stream` 不计 |
| AC6 | 能**定期聚合**出系统状态，不是只能靠人 grep | 新 `scripts/slowlog-report.py`：按接口聚合 count/p50/p95/max |
| AC7 | 阈值可配，不改代码就能调 | `shop.obs.slow-ms`（默认 1000） |

**孤立项**

- 没落点的 AC：无。
- 挂不上 AC 的设计：无。

## §1 现状与影响面

### 现状：全链路**一个耗时数字都没有**

2026-10-08 实测：

| 事实 | 数 |
|---|---|
| 昨天打到后端的请求 | **3352**（/biz 3109 · /mp 243） |
| 应用日志当天到 08:10 | **29 KB** —— 几乎什么都不记 |
| 日志轮转 | 已配：`LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE=20MB` / `MAX_HISTORY=14` |
| 磁盘 | 59G 用 37% |
| nginx `log_format` | **默认 combined，没有 `$request_time`** |
| actuator 暴露 | 只有 `health,info` |

所以这不是「优化日志」，是**从零加一层可观测**：应用、nginx、actuator 三处都拿不到耗时。

**量级不是问题**：3352 条/天 × 约 200 字节 ≈ **670 KB/天**，20MB×14 的轮转装得下。
（这条要写出来——上一次生产事故正是「日志无轮转 + outbox 无上限」把盘写满。）

### 可直接复用

- `BizContext.current()`（ThreadLocal）已有，登录态下能取到 merchantNo / storeNo。
- 轮转已经配好，**本方案不新开日志文件**，于是不需要再配一套轮转（见 §2「明确不做」）。

### 会被改到的

- 新增一个**全局** servlet 过滤器：所有请求都要过它一道。
- `application.yml` 加 `shop.obs.*` 三个键。

### 明确不受影响

端点、库表、权限码、i18n、既有过滤器链、业务代码一行不动。

## §2 方案

### 契约变更

- **端点 / 库表 / 权限码 / i18n**：无。
- **配置项**（新增）：
  - `shop.obs.access-log`（boolean，默认 `true`）—— 全量访问行开关
  - `shop.obs.slow-ms`（long，默认 `1000`）—— 慢日志阈值
  - `shop.obs.exclude`（list，默认 `/actuator/**,/ops/stream`）
  - ⚠️ `@Value` **不做松绑定**，yml 的键名必须与占位符逐字一致（仓库里 `shop.ai.vision.base-url` 是同一写法）。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `shop-app/.../config/obs/ApiAccessLogFilter.java` | 计时 + 两条 logger |
| 新增 | `shop-app/.../config/obs/ObsConfig.java` | `FilterRegistrationBean`，`Ordered.HIGHEST_PRECEDENCE` |
| 修改 | `shop-app/src/main/resources/application.yml` | 三个键 + 两个 logger 级别 |
| 新增 | `scripts/slowlog-report.py` | 按接口聚合的日报 |
| 新增 | `shop-app/.../obs/ApiAccessLogFilterTest.java` | 判据见 §5 |
| 新增 | `scripts/tests/` 或同目录 | 报告脚本的解析自测（见 AC6 的消融） |

### 为什么是**全局过滤器**而不是挂进 SecurityFilterChain

`SecurityConfig` 里有 **5 条** `SecurityFilterChain`（/biz、/mp、/ops…）。
挂进链里就得挂五遍，而且**漏掉不匹配任何一条链的请求** —— 那恰恰是出问题时最想看的那些。
所以用 `FilterRegistrationBean` 全局注册。

**代价说清楚**：`MockMvc.webAppContextSetup` **不装** `FilterRegistrationBean` 注册的过滤器 ——
用 MockMvc 写的用例会全绿而什么都没测到。所以 AC1/AC2/AC5 的测试
**必须起 `RANDOM_PORT` 走真实链路**（仓库里这条坑有记录）。

### 日志格式（机器可解析是硬要求）

AC6 要「定期聚合」，所以格式不能是给人读的散文。固定 `key=value`、顺序固定：

```
api m=GET p=/mp/goods/{goodsNo} s=200 ms=1234 rid=a1b2c3d4
api m=POST p=/biz/goods/save s=200 ms=2310 rid=e5f6a7b8 mch=M2026… store=ST2026…
```

- `p` 用**路径模板**。用原始路径的话，`/mp/goods/G2026…` 会把同一个接口散成几千行，
  聚合出来每行 count=1 —— 等于没聚合。
- 拿不到模板的情况**真实存在**：认证失败的请求到不了 dispatcher，`BEST_MATCHING_PATTERN` 为空。
  这时对号段脱敏（`G2026…`/`M2026…`/`ST2026…` → `*`）再记。
- `rid` 只为把慢日志那行和访问行对上，不进任何业务。

### 明确不做（写在这里，免得下次当待办捡起来）

- **不新开日志文件**。慢日志用 logger 名 `api.slow` 区分，仍写同一个文件。
  理由：上一次生产盘满的根因就是「日志无轮转」，再开一个文件就要再配一套轮转，
  多一个会忘的地方。`grep ' api.slow '` 就能捞，报告脚本也按 logger 名过滤。
  将来真要独立文件，再加 `logback-spring.xml` 并**同时**把轮转写上。
- **不记请求体 / 查询串**。排查价值远低于泄露风险（查询串里有地址、手机号）。
- **不做告警推送**。本轮只到「能查、能定期看」；推送要先定收件人与静默规则，是另一件事。
- **nginx 的 `$request_time` 与 actuator `metrics`**：本 TDD 不含。
  两者都有价值（前者是端到端含排队、后者直接给 P95），但各自要改 nginx 配置 / 暴露面，
  与本方案互补不互斥，单独提。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `ApiAccessLogFilterTest#everyRequestGetsOneLineWithDuration` | ✅ | 见下（与 AC7 合并） |
| AC2 | `ApiAccessLogFilterTest#slowRequestsAlsoGoToSlowLog` | ✅ | 阈值判断改恒假 → **精准红在这一条** ✅ |
| AC3 | `ApiAccessLogFilterTest#unauthenticatedRequestsAreMaskedNotExploded` | ✅ | 直接记原始路径 → **精准红在这一条** ✅ |
| AC4 | `ApiAccessLogFilterTest#queryStringIsNeverLogged` | ✅ | — |
| AC5 | `ApiAccessLogFilterTest#actuatorIsExcluded` | ✅ | 去掉排除 → **精准红在这一条** ✅ |
| AC6 | `scripts/slowlog-report.py` 手工三例（见下） | ✅ | 把 `p=` 改成 `path=` → 非零退出 ✅ |
| AC7 | 由 `@TestPropertySource` 把阈值压到 1ms 承载 | ✅ | 见偏差说明 3 |

```
Tests run: 5, Failures: 0, Errors: 0, Time elapsed: 13.75 s   BUILD SUCCESS（MVN_EXIT=0）

消融①不脱敏          → Failures: 1，红在 unauthenticatedRequestsAreMaskedNotExploded
消融②去掉排除        → Failures: 1，红在 actuatorIsExcluded
消融③不写慢日志      → Failures: 1，红在 slowRequestsAlsoGoToSlowLog
三次各自还原后：5/5 绿、源码无消融残留
```

报告脚本三例（造一份含 access+slow 两种行的样本日志）：

```
正常          → 7 行读入，按 rid 去重后 4 个请求，2 个 ≥1000ms（50.0%），exit=0
格式变了      → 「一条访问日志都没解析到」+ 三条可能原因，exit=2   ← AC6 的要害
只有慢日志    → 正常出表，但多打一行「没有任何低于阈值的请求，占比没有分母」，exit=0
```

**AC6 的那条「解析到 0 行要报错」是本 TDD 最要紧的一条断言。**
按正则读日志的脚本不会报「读不到」，只会读出空 —— 而空在监控里长得和「系统很健康」
一模一样。日志格式哪天变了（或 logger 名改了），报告会每天安静地输出「0 条慢请求」，
而那正是最需要它说话的时候。所以脚本自己要有前置断言：
**总行数为 0 → 非零退出并说明「没解析到任何访问行，格式可能变了」**。

## §6 对账二 · 设计 → 实现（实现完再填）

```
 .../neargo/shop/config/obs/ApiAccessLogFilter.java | 144 +++++++++++++++++++++
 .../java/ai/neargo/shop/config/obs/ObsConfig.java  |  43 ++++++
 .../shop-app/src/main/resources/application.yml    |  13 ++
 .../ai/neargo/shop/obs/ApiAccessLogFilterTest.java | 138 ++++++++++++++++++++
 scripts/slowlog-report.py                          | 105 +++++++++++++++
 5 files changed, 443 insertions(+)
```

| 差异 | 说明 |
|---|---|
| TDD 列了、实际没建：报告脚本的独立测试文件 | 见偏差说明 4 |

### 偏差说明

**1. 差点把 `application.yml` 的全部 `shop.*` 配置整个干掉。**

一开始把 `shop: obs: …` 作为**新的顶层块**加在文件末尾。而 `application.yml` 里
第 92 行早就有一个顶层 `shop:`，两者之间**没有 `---` 文档分隔符** ——
同一个 YAML 文档里重复的顶层键，后一个会把前一个整个覆盖掉。

用解析器一验：`shop` 下**只剩 `obs`** 一个键，`category`/`ai`/`attribution`…
24 个键全没了。肉眼完全看不出来（两块离了 376 行）。
改成合并进已有的 `shop:` 之下，再验：24 个键都在，`obs` 是其中之一。

**判据只能是解析器**。`grep -c "^shop:"` 得到 2 也看不出严不严重 ——
要问的是「加载之后还剩什么」。

**2. 门店号从请求头取，不从 `BizContext` 取。**

原本写的是 `BizContext.current()`。错的：`BizContextFilter` 在链的**更里层**，
而它在自己的 `finally` 里 `clear()` —— **内层的 finally 比外层先跑**，
等本过滤器的 finally 执行时 ThreadLocal 已经空了。

那样写不会报错，只会让 `store=` **永远不出现** —— 而「字段恒缺失」和
「这些请求本来就没带门店」在日志里长得一模一样，不会有人发现。
改成读 `X-Store-No` 请求头（任何时候都读得到，也不让可观测层耦合认证层）。
代价：拿不到 `merchantNo`（它只在 BizContext 里），本版不记。

**3. AC7「阈值可配」没有独立用例。**

它由 `@TestPropertySource` 把 `shop.obs.slow-ms` 压到 1ms 来承载 ——
整个测试类能跑通本身就证明这个键被读到了（默认 1000ms 的话
`slowRequestsAlsoGoToSlowLog` 会因为请求不够慢而随机变绿变红）。
单独为它再写一条「配 9999 则不进慢日志」会更直接，没写，记在这里。

**4. 报告脚本没有自动化测试，只有手工三例。**

它是运维脚本不进 `mvn test`，而仓库里 `scripts/*.py` 也没有既成的测试位。
三例（正常 / 格式变了 / 只有慢日志）是手工跑的，输出贴在 §5。
**AC6 那条「解析不到就非零退出」已经验过**，这是三例里最要紧的一条。

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-08 | 草稿，待确认 |
| 2026-10-08 | 用户确认，开始实现 |
| 2026-10-08 | 已实现。`ApiAccessLogFilterTest` 5/5 绿，三次消融各自精准红；报告脚本三例手工验过。<br>闸门：`mvn -pl shop-app -am test -Dtest=ApiAccessLogFilterTest`（扫 shop-app 测试源码）+ `check-generated-docs --check`（提交前跑，补了文档索引）。 |
