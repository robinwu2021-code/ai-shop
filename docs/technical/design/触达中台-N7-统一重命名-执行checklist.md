# N7 · msg_* → notify_* 统一重命名 · 执行 Checklist（待审）

状态：**已执行（2026-08-17，提交 e82647d）** —— 按 owner「按建议执行」落地：前向 RENAME、保留 sys_notify_log、保留 Java 类名
关联：[触达推送中台-模块抽象-设计](./触达推送中台-模块抽象-设计.md) §14 N7

## 0. 关键结论（先看这条，它决定整件事的风险）

**用前向 `ALTER TABLE msg_x RENAME TO notify_x` 迁移，不编辑任何已应用的建表迁移。**

- 与 [V152 那张卡](../../../) 的**本质区别**：V152 是「改了一个已应用迁移的内容」→ Flyway 校验和不匹配 → 要 repair。
  本期是**新增一条 forward 迁移**，旧迁移一个字不改 → **校验和不变，无需 repair**。
- `scripts/lib/ddl.mjs` **已经会重放 `ALTER TABLE … RENAME TO`**（第 115 行）——
  所以 entity-alignment / schema-lineage / ddl-parsable 等守卫会看到重命名后的 `notify_x`，
  **不需要改解析器**。
- 后端数据访问**全部走 `@TableName` + MyBatis-Plus wrapper，没有一处 XML/注解硬编码 SQL 表名**
  （已核对）——所以改 6 个 `@TableName` + 一条 rename 迁移，数据面就齐了。

> 结论：N7 比预想**干净得多**，不碰 Flyway repair。真正的成本是**改动面广**（下面逐一列）+
> **共享工作区协调**（并行会话引用 `msg_*` 的未提交代码会断）。

## 1. 重命名清单（6 张表）

| 现名 | 新名 | 备注 |
| --- | --- | --- |
| `msg_message` | `notify_message` | 三端消息中心在读的核心表，引用面最广（20 文件） |
| `msg_template` | `notify_template` | 引用面最广（24 文件，schema-test 22 处多为种子） |
| `msg_scene_channel` | `notify_scene_channel` | 本轮 N1 刚加的，引用少 |
| `msg_push_token` | `notify_push_token` | **注意与已存在的 `notify_push_task` 不同名，不冲突** |
| `msg_subscribe` | `notify_subscribe` | 订阅额度/频控 |
| `msg_ticket` | `notify_ticket` | 客服工单 |

**两个决策点需 owner 拍板（见 §7）**：`sys_notify_log` 改不改；Java 实体类名改不改。

## 2. 精确改动清单（逐文件）

### 2.1 新增迁移 `V162__rename_msg_to_notify.sql`（唯一的 DB 改动）

```sql
ALTER TABLE msg_message       RENAME TO notify_message;
ALTER TABLE msg_template      RENAME TO notify_template;
ALTER TABLE msg_scene_channel RENAME TO notify_scene_channel;
ALTER TABLE msg_push_token    RENAME TO notify_push_token;
ALTER TABLE msg_subscribe     RENAME TO notify_subscribe;
ALTER TABLE msg_ticket        RENAME TO notify_ticket;
```
> `ALTER TABLE … RENAME TO`（不是 `RENAME TABLE`）：MariaDB 与 `ddl.mjs` 都认这一种，一次对齐。

### 2.2 后端 `@TableName`（6 个实体）

`MsgMessage/MsgTemplate/MsgSceneChannel/MsgPushToken/MsgSubscribe/MsgTicket` 的
`@TableName("msg_x")` → `@TableName("notify_x")`。**Java 类名是否一起改见 §7 决策点。**

### 2.3 后端硬编码表名（唯一一处非 @TableName 引用）

`shop-core/.../media/CoreMediaRefs.java` 第 65–66 行硬编码了 `"msg_template"` / `"msg_ticket"`
（媒体引用登记表，因为这两张表的正文里内嵌图片）→ 改成 `notify_template` / `notify_ticket`。

### 2.4 测试 schema `schema-test.sql`（H2，无 Flyway，必须手改）

H2 不跑迁移，靠这份建表 + 种子。逐表把 `CREATE TABLE` 与所有 `INSERT` 里的表名改掉：

| 表 | 出现次数 |
| --- | --- |
| `msg_template` | 22（种子多） |
| `msg_scene_channel` | 3 |
| `msg_message` / `msg_push_token` / `msg_subscribe` / `msg_ticket` | 各 1 |

> **不要**在 schema-test 里也写 RENAME —— 它不跑迁移，直接把建表/种子改成新名即可。

### 2.5 守卫登记与文档注释

- `packages/shared/tests/schema-lineage.test.ts`：`NAME_COLLISIONS` 的说明文本里写死了
  `msg_message`/`msg_push_token`/`msg_template`（第 194/222/226 行）→ 改新名，否则说明与现实不符。
- `packages/shared/src/contract/enum-registry.ts`、`packages/shared/src/ports/push.ts`、
  `ops-web/app/messages/test-send-drawer.tsx`、`ops-web/lib/types/message.ts`：**注释里**提到
  `msg_push_token.platform` 等「与后端逐字一致」→ 改新名（是注释，不影响运行，但会误导）。
- `docs/api/领域模型对齐清单.md`（生成物）→ 跑生成器 regen。
- 约 10 篇设计/TDD 文档提到 `msg_*` 表名 → 批量替换为新名（文档准确性，非阻塞）。

### 2.6 已应用迁移里的 `msg_*` 引用——**不动**

V20/V141/V156 等已应用迁移里的 `CREATE msg_x` 与 `INSERT INTO msg_x` 是**冻结的历史**：
Flyway 不会重跑它们，改了反而破坏校验和。它们创建/播种的表在 V162 里被改名，最终态正确。

## 3. 守卫影响与验证

- **entity-alignment / schema-lineage / ddl-parsable**：`ddl.mjs` 重放 V162 的 RENAME →
  解析出 `notify_x` → 与新 `@TableName` 对齐。**预期通过**（改完要实跑确认）。
- **执行后必跑**：
  - 后端 `NotifyEndToEndFlowTest`、`M8MessageFlowTest`、`PushNotifyFlowTest`、`SceneChannelSeedTest`、
    `NotifyChannelRegistryTest`、`MerchantChannelTest`、`PushTaskFlowTest`（所有碰这 6 表的链路）；
  - `packages/shared` 的 entity-alignment / schema-lineage / ddl-parsable；
  - `check:api`（前端契约不涉及表名，预期无差异）。

## 4. 共享库与并行会话协调（真正的风险所在）

- **dev 库**：下次后端启动会 apply V162，把现有 `msg_*` 就地改名。应用代码（新 `@TableName`）随之对上。**无需 repair。**
- **⚠️ 并行会话**：这是共享工作区。重命名一落地，**其它会话里任何引用 `msg_*` 的未提交代码
  （@TableName、CoreMediaRefs、schema-test）会断编译或查不到表**。
  执行前须：① 确认无并行会话正在改 message 域；② 或约定一个静默窗口一次性落地。
- 与 V152 卡**可以但不必**同批做：V152 需要 repair、N7 不需要，两者独立。若同一个静默窗口一起做省一次「停服—重启」。

## 5. 执行顺序

1. 建 `V162` 迁移（§2.1）。
2. 改 6 个 `@TableName`（§2.2）+ `CoreMediaRefs`（§2.3）。
3. 改 `schema-test.sql`（§2.4）。
4. 改守卫登记与注释（§2.5）。
5. 全量编译 + 跑 §3 的测试与守卫，全绿。
6. regen 生成物（领域模型对齐清单、角色×端点矩阵若受影响）。
7. 一次性提交（**原子提交**，避免中间态：表已改名但引用没跟上）。
8. 批量改文档表名（可单独一提交）。

## 6. 回滚

未合并前：`git revert` 该提交即可（代码回到 `msg_*`）。dev 库若已 apply V162，需要一条反向
`ALTER … RENAME TO msg_x` 迁移（或手动改回）——所以**落地前确认这一版就是要的**，避免来回改名。

## 7. 待 owner 拍板的两个决策点

1. **`sys_notify_log` 改不改名？**
   - 它是 `sys_` 前缀（与 `sys_outbox`/`sys_job_run`/`sys_media_asset` 同族——系统/运维记录表的约定）。
   - **建议保留 `sys_notify_log`**：`sys_` 表达的是「基础设施记录」而非「触达业务表」，与 msg_→notify_ 的
     业务表重命名不是一回事；改了反而打破 `sys_` 这条更强的约定。若坚持统一，则一并 → `notify_log`。
2. **Java 实体类名改不改（`MsgMessage` → `NotifyMessage`…）？**
   - 只改 `@TableName` 的话，类名仍叫 `MsgMessage` 但映射 `notify_message` 表——有轻微命名歧义，但改动面小。
   - 改类名的话，`MsgMessage`/`MsgTemplate`… 每个类被 20+ 文件引用，是一次**大范围机械重命名**。
   - **建议**：本期只改表名（`@TableName` + 迁移），**类名单列为可选后续**——把「碰核心表名」与
     「碰全域类名引用」两件事分开，各自的爆炸半径不叠加。

---
**审阅要点**：确认 §1 的 6 表新名、§7 的两个决策点。确认后我按 §5 顺序原子执行并逐项验证。
