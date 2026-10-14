# TDD-订单号序列化（短期号段方案）

状态：实施中（2026-10-10）
档位：3（动库表 sys_sequence + 改订单号这个对外业务键 + 新 ErrorCode 兜底）
关联：[ADR-035 订单号序列化](ADR/ADR-035-订单号序列化-短期号段方案.md) · [ADR-033 业务编码统一生成](ADR/ADR-033-业务编码统一生成.md)
背景来源：用户 2026-10-10「订单号不展示字母、结尾数字、好记；头+日期时+序列；号段从 DB 拿 10 个到内存」

## §1 现状与根因

订单主单号 `BizKey.next(ORDER)` = `SO` + `yyMMdd` + 10 位 Crockford 随机段（如 `SO261010A7K3M9QF2X`），
子单号同理。随机段防枚举、防多实例碰撞（ADR-033），但**难记难报** —— 客服问号、小票印号、
买家口报都卡在那 10 位随机字母数字上。用户要可读的序列格式。

## §2 实施计划

### T0 迁移：`sys_sequence` 表
`backend/shop-app/src/main/resources/db/migration/V399__sys_sequence.sql`（号确认未撞再定）
```
CREATE TABLE sys_sequence (
  seq_name    VARCHAR(32)  NOT NULL,
  next_value  BIGINT       NOT NULL DEFAULT 0,  -- 下一个要发的序列值的「前一位」；领段 += step
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (seq_name)
) ...;
INSERT INTO sys_sequence(seq_name, next_value) VALUES ('ord_main', 0);
```

### T1 `SeqMapper`：原子领段
`backend/shop-core/.../trade/mapper/SeqMapper.java`
- `int touch(name)`：`INSERT IGNORE`（种子兜底，迁移已插则无副作用）。
- `long claim(name, step)`：`UPDATE sys_sequence SET next_value = next_value + #{step} WHERE seq_name=#{name}`
  然后 `SELECT next_value` —— **同一事务 + 行锁**，返回领段后的上界 `hi`，段 = `[hi-step+1, hi]`。
  用「UPDATE 再 SELECT」而不是 `RETURNING`（MySQL 9.7 对 UPDATE…RETURNING 支持要确认，稳妥起见两句）。

### T2 `OrderNoGenerator`（号段分发，含格式化）
`backend/shop-core/.../trade/support/OrderNoGenerator.java`
- 持有 `AtomicReference<long[]>`（当前段 `[cursor, hi]`，volatile 语义）或 `synchronized` 领段。
- `nextMainSeq()`：段内 `cursor++`；到 `hi` 则调 `seqMapper.claim("ord_main", 10)` 领新段。**领段加锁**，
  段内自增用 `AtomicLong`，避免每次取号都进锁。
- `mainOrderNo(at)`：`"O" + yyMMddHH(at) + fmt4(seq)`，`fmt4(seq) = (seq-1) % 9999 + 1` 补零 4 位。
- `subOrderNo(mainNo, idx)`：`mainNo + "-" + idx`（idx 1-based）。
- 格式常量集中在这里，BizKey 不动。

### T3 单元测试（号段性质 —— 容易静默重号，必须钉死）
`backend/shop-app/src/test/.../OrderNoGeneratorTest.java`（连真库 / @SpringBootTest，因为要 DB 游标）
- AC2 连续取号序列递增、跨段连续（取 25 个 → 序列 1..25，中间跨 2 次领段无断裂）。
- AC3 一段 10 个只领一次库：取 10 个只有 1 次 `claim`；第 11 个再领（用 spy / 计数）。
- AC4 **重启不重号**：取 3 个（段 [1,10]，游标到 3），**新建一个 generator 实例**（模拟重启）→
  它领新段 [11,20]，不回退到 1 —— 老段剩余 4..10 作废（浪费），但不重号。
- AC5 展示回绕：把序列推到 9999、10000 → 展示 `9999` 然后 `0001`（DB value 继续涨）。
- AC6 **多实例不重叠**：两个 generator 实例交替取号，收集所有号 → 无重复（号段天然隔离）。
- AC7 **并发无重复无丢失**：单实例 N 线程各取 M 个 → 去重后恰好 N*M 个，无缺号。
- AC9 子单后缀：`subOrderNo("O26101014"+"0001", 2)` == `"O261010140001-2"`。

### T4 接订单创建
`OrderServiceImpl`：`BizKey.next(ORDER)` → `orderNoGenerator.mainOrderNo(now)`；
子单循环 `BizKey.next(SUB_ORDER)` → `orderNoGenerator.subOrderNo(orderNo, ++idx)`。
下单时刻 `at` 用 `now`（已有 `System.currentTimeMillis()`）。

### T5 唯一兜底
生成后若该号已占（撞 `uk_order_no`，号段正常不撞）：`mainOrderNo` 带 `Predicate<String> taken` 回调，
撞了跳过续发，限 3 次。**超限抛 `IllegalStateException`，不新增 ErrorCode** —— 调整自原计划：
超限意味着「同一小时内 > 9999 单」，是不该发生的内部不变量违反（系统级 500 + 告警），
不是买家能理解/处理的业务错误；为一个永不触发的情形新增 ErrorCode（要登记四处）不划算。
接入点 `OrderServiceImpl` 的 `taken` 查 `ord_order` 是否已有该 order_no。

### T6 场景测试（端到端）
`StoreScoped...` 或既有下单测试补：下单后 `order_no` 匹配 `^O\d{8}\d{4}$`、子单 `^O\d{12}-\d+$`、唯一、能查到。
消融：把 generator 换回 `BizKey.next` → 格式断言红。

### T7 对账 + ADR 收尾
三份产物（数据库表清单 +1、静态常量、ErrorCode 分段）重新生成；ADR-035 翻「已实施」；本 TDD §5/§6 填。

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 | 消融 |
|---|---|---|
| 格式（主单 O+yyMMddHH+4位、子单 主单号+后缀） | `OrderNoGeneratorTest#format`；端到端 `BizDashboardAndReviewFlowTest`（子单号 `^O\d{12}-\d+$`） | — |
| 展示回绕（9999→0001，不为 0000） | `#displayWraps` | — |
| 连续取号严格递增、无重复 | `#monotonicAcrossSegments` | — |
| **重启不重号** | `#restartDoesNotReuse`（新建实例领新段、不回退） | — |
| **多实例不重叠** | `#twoInstancesDoNotOverlap` | — |
| **并发无重复无丢失** | `#concurrentNoDupNoLoss` | 去掉 `synchronized` → 重复号（已验证红） |
| 一段 10 个 / 撞号续发 | `#probeSkipsTakenNumbers` | — |
| 接入不破 | `ProxyOrderFlowTest` / `OfflineSettleFlowTest` / `BizDashboardAndReviewFlowTest` 共 59 条绿 | — |

## §6 对账二 · 设计 → 实现

| 设计（ADR-035） | 落点 |
|---|---|
| 号段表 | `V399__sys_sequence.sql`（`sys_sequence(seq_name, next_value)` + `ord_main` 种子） |
| 原子领段（bump→peek 同事务行锁） | `TradeMappers.SeqMapper` + `SeqSegmentSource#claim`（`@Transactional`，拆出来避开自调用不走代理） |
| 号段分发 + 格式化 + 子单后缀 | `OrderNoGenerator`（`synchronized nextMainSeq` / `display` 回绕 / `mainOrderNo` 兜底 / `subOrderNo`） |
| 接订单创建 | `OrderServiceImpl`：主单 `mainOrderNo(now, taken)`、子单 `subOrderNo(orderNo, ++idx)` |
| BizKey 不动 | 其他业务码仍走随机段（ADR-033 不受影响） |
| 存量不迁移 | 新旧并存，无格式解析代码（全仓搜过无 substring/split/startsWith 解析） |

## §6 对账二 · 设计 → 实现
[实施后填]
