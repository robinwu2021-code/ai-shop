# TDD · 统一鉴权与会话外置（DB + 本地缓存）

状态：**待确认**
关联需求：无独立 PRD —— 这是一条**技术约束驱动**的改造（多实例部署 + 将来的统一鉴权），
不是产品功能。需求来自 2026-08-26 的决定：先解决统一鉴权，运营端优先，
兼容 Redis，但当前没有 Redis，改用 **DB 存取 + Ehcache 同步缓存**。
创建日期：2026-08-26

> **2026-08-26 补：本方案覆盖 C 端 / B 端 / 运营端三套。**
> 基础逻辑一套、装配三次；令牌表与用户表各自独立。见 **第 13 节**。

## 一句话

会话从「进程本地磁盘」搬到「数据库 + 各实例本地内存缓存」，
于是**多实例可以共享登录态**，而 `revokeUser`（停用账号、改角色时踢人）能跨实例生效。
接口一行不改，将来换 Redis 只是改一行配置。

---

## 1. 现状核对（实测，不是回忆）

| 事实 | 来源 | 影响 |
|---|---|---|
| 生产 `SHOP_TOKEN_STORE=ehcache` | 服务器 `shop-app.env` | 会话在**进程本地磁盘**，另一个实例读不到 |
| 服务器上**没有 Redis**（6379 未监听） | `ss -lntp` | 现成的 `RedisTokenStore` 用不上 |
| 生产只有**一个** systemd 单元 | `systemctl` | 今天不出问题，但多副本被这一条锁死 |
| `RedisTokenStore` / `EhcacheTokenStore` / `MemoryTokenStore` 都已实现 | `shop-base/auth/store/` | `TokenStore` 是成型的 SPI，加一个实现即可 |
| **判权是现算的**（`LivePermResolver` 读 `sys_role_point`，整表快照缓存） | 类注释 | 会话只需存角色；perms 快照仅作解析失败时的回落 |
| 权限/角色/停用的写接口都调 `revokeUser` | `TokenStore` 注释 | **踢人是权限新鲜度的主力机制** —— 它必须跨实例生效 |

### 1.1 今天这套还有一个真实伤疤

Ehcache 持久化目录**关不干净就会被整个删掉**（日志里只有一行
"Probably unclean shutdown … deleted root directory"），而会话就存在那里 ——
2026-08-24 线上重现过一次，表现是**全员掉线**。
`deploy-backend.sh` 现在靠「新文件名 + 切软链」绕开触发条件。

> 换成 DB + **纯内存**本地缓存之后，**这个失效模式整个消失**：
> 本地缓存丢了只是回源查库，不再是丢会话。这是本方案顺带的收益，
> 而它比「支持多实例」更早能被感知到。

---

## 2. 方案选型

| 方案 | 多实例 | 活过重启 | 外部依赖 | 撤销传播 | 结论 |
|---|---|---|---|---|---|
| 现状：Ehcache 持久化 | ❌ | 是（但会被误删） | 无 | 只在本进程 | 已是瓶颈 |
| Redis | ✅ | 是 | **要装 Redis** | 天然 | ✅ 长期目标，**今天没有** |
| **DB + 本地内存缓存** | ✅ | 是 | 无（复用现有库） | 靠 TTL + 撤销轮询 | ✅ **本次采用** |
| 无状态 JWT | ✅ | 不适用 | 无 | ❌ **撤销不了** | ❌ 见下 |

**为什么不是 JWT**：这个系统的权限新鲜度靠「改权限就踢人」（`revokeUser`）实现，
而 JWT 签发之后在过期前无法撤销 —— 除非再维护一张黑名单表，
那就绕回来了，且黑名单比会话表更难对账。**能撤销是这套鉴权的硬需求，不是可选项。**

**与 Redis 的关系**：`TokenStore` 接口不变，`DbTokenStore` 是并列的第四种实现。
将来上了 Redis，改 `shop.auth.token-store=redis` 一行即可，
两者可以共存一段时间用于灰度。

---

## 3. 数据模型

新表 `usr_session`（平台库）：

```
id            BIGINT PK
token_hash    CHAR(64)  UK    -- SHA-256(token) 的十六进制。**不存明文**
realm         VARCHAR(16)     -- OPERATOR / CONSUMER
user_no       VARCHAR(32)     -- 主体标识，revokeUser 按它批量撤销
nickname      VARCHAR(64)
roles         VARCHAR(512)    -- JSON 数组。判权现算，这里只存角色
perms         TEXT            -- JSON。**仅作 LivePermResolver 解析失败时的回落**
tenant_no     VARCHAR(32)
scope_json    TEXT            -- DataScopeSpec
issued_at     DATETIME
expires_at    DATETIME        -- 签发 + 30 天，与今天一致
last_seen_at  DATETIME        -- 节流写回，见 5.4
revoked_at    DATETIME NULL   -- **软撤销，不物理删**
revoke_reason VARCHAR(32)     -- LOGOUT / ROLE_CHANGED / DISABLED / PURGED
```

索引：`uk_session_token(token_hash)`、`idx_session_user(user_no, revoked_at)`、
`idx_session_expires(expires_at)`、`idx_session_revoked(revoked_at)`。

### 3.1 三条不显然的决定

**① 存 token 的 SHA-256，不存明文。**
库被拖走 ≠ 所有人的会话被拿走。查询按哈希查，功能上无差别。

> **用 SHA-256 而不是 bcrypt/argon2**：token 是 128 位随机串，不是密码，
> 没有字典攻击面。上 KDF 等于给**每一个请求**加一次几十毫秒的开销 ——
> 那会让这套方案在第一天就被判定为「太慢」。

**② `revoked_at` 软撤销，不物理删。**
删了就分不清「没这行」和「被踢了」，
而「我为什么突然被登出」是运营最常问的问题之一。
物理清理交给定时任务（见 6.3），且只清**已过期很久**的。

**③ 不存 IP / User-Agent。**
它们对排查有用，但那是 PII，且这张表将来要被多个服务读。
真需要时单独做审计表，不要顺手塞进会话。

---

## 4. 读写路径

```
                       ┌──────────────── 实例 A ────────────────┐
请求带 token ──▶ 前缀校验(otk_/ctk_) ──▶ L1 本地缓存(Ehcache 堆内, TTL 60s)
                              │                    │ 命中 ──▶ 放行
                              │ 池不符             │ 未命中
                              ▼                    ▼
                            401              SELECT … WHERE token_hash=? AND revoked_at IS NULL
                          (不查库)                 │
                                                   ├─ 有且未过期 ──▶ 写入 L1 ──▶ 放行
                                                   └─ 无/过期     ──▶ 401（**不做负缓存**，见 5.3）
```

写路径（登录 / 登出 / 踢人）**先写库、后动本地缓存**：库是唯一真源，
本地缓存只是加速。顺序反了的话，写库失败会留下一个「本地认、别的实例不认」的会话。

---

## 5. 一致性设计（本方案的核心）

多实例下唯一真正困难的是：**A 上把人踢了，B 的本地缓存还认。**

### 5.1 两道保证，上界明确

| 机制 | 传播延迟 | 作用 |
|---|---|---|
| L1 TTL（默认 **60 秒**） | ≤ 60s | 兜底，永远成立 |
| **撤销轮询**（默认每 **5 秒**） | ≤ 5s | 主力：读 `WHERE revoked_at > :lastSeen`，只剔除那几条 |

轮询读的是「上次之后新撤销的会话」，**不是全表**，也**不清空整个 L1** ——
清空会在踢一个人时让所有在线用户的下一次请求都回源查库，
把一次撤销放大成一次库上的尖峰。

> **验收标准**：实例 A 调 `revokeUser` 后，实例 B **在 5 秒内**拒绝该 token。
> 这条必须有自动化测试，且撤掉传播机制后它必须变红。

### 5.2 `revokeUser` 变成一条 UPDATE

```sql
UPDATE usr_session SET revoked_at = NOW(), revoke_reason = :why
 WHERE user_no = :userNo AND revoked_at IS NULL
```
返回的行数就是接口约定的「踢掉的会话数」。
今天 Ehcache 实现要遍历本地缓存才能做到，且**只覆盖本进程**。

### 5.3 **不做负缓存**（容易被"优化"掉的一条）

无效 token 查不到就是 401，不把「查不到」也缓存起来。原因：
用户刚在实例 A 登录，若实例 B 之前缓存过「这个 token 不存在」，
登录后的头几秒会**间歇性 401** —— 而这种错「重试一下就好了」，
最容易被归因成网络问题，然后长期存在。

无效 token 的压力由前缀校验（不查库）与登录限流承担，那里本来就有闸门。

### 5.4 `last_seen_at` **节流写回**

每个请求都 UPDATE 一次会把这张表变成整个库写入最频繁的表。
只在「距上次写回超过 1 小时」时才更新。它的用途是「这个会话还活着吗」，
1 小时精度完全够。

### 5.5 时间一律用应用时钟

`expires_at` 的写入与比较都在应用侧完成，不用 `NOW()` 做判断。
多实例 + 数据库时区一旦不一致，症状是「有的实例认为过期了、有的没有」，
而这种不一致在日志里看不出来。

---

## 6. 模块与接口

### 6.1 新模块 `shop-auth-store`（**建议**，也可先不拆）

形状与 `shop-job-store` 一致：**JdbcClient 手写 SQL，不依赖 shop-base**。

```
shop-auth-store/
  SessionRow.java            record
  SessionDao.java            insert / findByHash / revoke / revokeByUser / findRevokedSince / purge
  依赖：spring-boot-starter-jdbc。**没有 MyBatis、没有 shop-base**
```

**为什么值得单独拆**：这正是「统一鉴权」阶段 2 的地基 ——
将来任何服务（含 `shop-job`）要嵌入鉴权 filter，都只需引这个模块 + spring-security-web，
**而不必把 shop-base 连同 MyBatis 一起拖进去**。
不拆的话，`DbTokenStore` 写在 shop-base 里更省事，但阶段 2 要重写一遍。

> 依赖方向：`shop-auth-store` ← `shop-base`。前者不认识后者，
> 所以 shop-base 增加这一条依赖不会形成环。

### 6.2 `DbTokenStore`（在 shop-base，实现现有 `TokenStore`）

```java
class DbTokenStore implements TokenStore {
    // issue()      : INSERT + 写 L1
    // get()        : L1 → 未命中查库 → 回填 L1
    // refresh()    : UPDATE + 刷 L1
    // revoke()     : UPDATE revoked_at + 剔 L1
    // revokeUser() : 一条 UPDATE，返回行数 + 剔本地该用户的条目
}
```
装配开关：`shop.auth.token-store=db`（新值，与 memory/ehcache/redis 并列）。

### 6.3 新定时任务 `session-purge`

清理**过期超过 30 天**的会话行（含已撤销的）。
它天然属于我们正在做的定时任务模块，是那个模块的第一个真实用户之一。

---

## 7. 性能与容量

- 运营端在线会话量级：**十几个**。C 端演示数据，当前平台 0 订单。
- L1 命中率：TTL 60s、请求间隔远小于它 → 稳态命中率接近 1，
  **每个会话每分钟最多一次查库**。
- 撤销轮询：每 5 秒一条带索引的 `WHERE revoked_at > ?`，代价可忽略。
- 结论：**这套方案在当前量级上的库压力，比一次页面加载还小。**
  真正的容量拐点在「会话数 × (60/TTL)」，届时上 Redis 即可，而接口不用动。

---

## 8. 切换与回退

**切换当天所有人重新登录**（Ehcache 里的旧会话不迁移）。
不做双读过渡 —— 双读要求两个 store 同时在线，复杂度不值当：
运营端只有十几个人，C 端目前是演示数据。

> 与定时任务那件事同理：**现在是零数据窗口，正是做这类切换的最好时机。**

回退：`SHOP_TOKEN_STORE=ehcache` 改回去、重启。
新表留着不删（软撤销的行本身是审计资料）。

---

## 9. 测试策略

| # | 场景 | 为什么必须有 |
|---|---|---|
| T1 | 签发 → 取回 → 内容一致 | 基本功 |
| T2 | 过期的取不到 | 用应用时钟判定，不是 SQL |
| T3 | `revoke` 后取不到 | 软撤销要真的挡住 |
| T4 | **★ 两个 store 实例共享一个库、各自本地缓存：A `revokeUser` 后 B 在 5 秒内拒绝** | **这是整个方案的存在理由**，撤掉传播必须变红 |
| T5 | 库里存的是哈希，明文 token 不出现在任何列 | 断言 `SELECT * ` 里找不到明文 |
| T6 | 无效 token 不产生负缓存（B 在 A 登录后立刻能认） | 防止「间歇性 401」这类最难查的缺陷 |
| T7 | `last_seen_at` 一小时内不重复写 | 防止把会话表变成写最频繁的表 |
| T8 | 池前缀不符时**不查库**直接 401 | 现有行为不能退化 |

T4 与 T6 是两条「写下来容易、被违反时无声」的，必须做撤掉修复验证。

---

## 10. 对将来的影响（这才是做它的理由）

```
阶段 1（本 TDD）  会话进库 → **多实例解锁**，踢人跨实例生效
      ↓
阶段 2            抽 shop-auth-filter（spring-security-web + shop-auth-store，
                  零 MyBatis、零 shop-base）→ 任何服务可嵌入鉴权
                  · shop-job 因此**可以自己鉴权**，ops-web 直连 worker 的路才通
      ↓
阶段 3            gateway 统一入口，filter 下沉或保留作纵深防御
```

阶段 2 有一条要提前认清的**代价**：嵌入式 filter 拿不到 `LivePermResolver`
（那个实现在 shop-core，读 `sys_role_point`），只能用会话里的 perms 快照。
这在今天是**被认可的回落路径**（`LivePermResolver.NONE` 就是它），
而它之所以安全，正是因为改权限的写接口都会 `revokeUser` ——
**也就是说，阶段 1 做的跨实例撤销传播，恰好是阶段 2 能用快照判权的前提。**
两件事不是先后关系，是因果关系。

---

## 11. 风险

| 风险 | 缓解 |
|---|---|
| 库挂了 = 全员无法鉴权 | 登录本来就要查库，不是新增单点；L1 让已登录用户在库短暂抖动时仍能通过 |
| 撤销传播被后人"优化"掉 | T4 是硬断言，且报错文案写明后果 |
| 有人加负缓存"优化"性能 | T6 + 代码注释写明「间歇性 401」这个症状 |
| 会话表长成大表 | `session-purge` 定时清理 + 索引 |
| 迁移号撞车 | 取号前看当前最大号（现为 V262）；本仓库撞过 |

---

## 12. 实现任务

- [ ] A1 新模块 `shop-auth-store`（`SessionRow` / `SessionDao`，JdbcClient）
- [ ] A2 迁移：`usr_session` 建表（取当前最大号 +1）+ 重跑 `gen-test-schema.py`
- [ ] A3 `DbTokenStore` + `shop.auth.token-store=db` 装配
- [ ] A4 撤销轮询（`RevocationWatcher`，默认 5 秒）
- [ ] A5 T1–T8 测试，T4 / T6 做撤掉修复验证
- [ ] A6 `session-purge` 定时任务（挂进定时任务模块）
- [ ] A7 生产切换：低峰改 `SHOP_TOKEN_STORE=db` 重启，公告「需重新登录」
- [ ] A8 ADR：记录「为什么不是 JWT、为什么不是现在上 Redis」

---

确认记录：待确认

---

# 13. 三端方案（C 端 / B 端 / 运营端）

## 13.1 现状核对，其中一条是真问题

| | C 端 | B 端（商家） | 运营端 |
|---|---|---|---|
| 用户表 | `usr_account` | `mch_account` | `sys_ops_staff` |
| 主体号段（生产实测） | `U202608181350550001913` | `SF-M0001` | — |
| 令牌前缀 | `ctk_` | **`ctk_`** ⚠️ | `otk_` |
| Realm | `CONSUMER` | **`CONSUMER`** ⚠️ | `OPERATOR` |
| 过滤器 | `ConsumerTokenAuthFilter` | **同一个** ⚠️ | `OperatorTokenAuthFilter` |
| 权限模型 | 无 RBAC，属主鉴权 | `BizContext`（实体/门店归属）+ `BizPerms` | RBAC（角色 + 现算权限） |

**⚠️ 那三格是同一件事：B 端今天没有自己的令牌池。**

`MerchantStaffServiceImpl` 第 90 行签发的是
`LoginUser.consumer(staff.getMchAccountNo(), "")` —— 商家员工拿的是 C 端的 `ctk_` 令牌，
而 `LoginUser.userNo` 这**一个字段**里，C 端塞的是 `usr_account.user_no`、
B 端塞的是 `mch_account.mch_account_no`。

今天不出事，因为号段恰好不撞（`U2026…` vs `SF-…`）。
但**那是约定，不是结构保证**：任何一端改了发号规则，撞上的表现是
「拿商家的令牌能读到某个消费者的数据」，而这种缺陷不会以报错的形式出现。

> `ConsumerTokenAuthFilter` 里已经有一行按 `/biz` 前缀区分 `APP_BIZ` / `APP_C` ——
> 说明「这是两端」这件事在代码里已经被承认了一半，只是没落到令牌层。

**所以三端分池不只是整洁：它把一个靠约定维持的安全边界变成由结构保证的。**

## 13.2 三张表，但**一套代码**

「基础逻辑相同、表不同」的正确落法不是复制三份 DAO，而是
**一个实现 + 三次装配**——表名与载荷映射是参数：

```java
// shop-auth-store：一个类，三个实例
new SessionDao(jdbc, SessionTable.CONSUMER)   // usr_session
new SessionDao(jdbc, SessionTable.MERCHANT)   // mch_session
new SessionDao(jdbc, SessionTable.OPERATOR)   // ops_session

record SessionTable(String table, Realm realm, String tokenPrefix) { }
```

`DbTokenStore` 同理：三个 Bean，各持一个 `SessionDao` 与一份本地缓存。
**缓存也必须分开** —— 共用一份缓存等于把刚分开的边界在内存里又合上。

> 一致性机制（TTL 60s + 撤销轮询 5s、不做负缓存、节流写回、软撤销）
> 三端完全一致，见第 5 节。它们是「基础逻辑」的全部内容。

## 13.3 三张表的差异：**只在载荷列上**

公共列（三张表都有，形状一模一样）：

```
id / token_hash / user_no / issued_at / expires_at / last_seen_at
/ revoked_at / revoke_reason
```

差异列：

| 表 | 额外列 | 为什么 |
|---|---|---|
| `usr_session`（C） | 无 | C 端无 RBAC，会话里除了「你是谁」不需要别的 |
| `mch_session`（B） | 无 | **刻意不存 `store_no` / `entity_no`** —— 见下 |
| `ops_session`（运营） | `roles` / `perms` / `tenant_no` / `scope_json` | RBAC 需要；`perms` 仅作现算失败时的回落（第 1 节） |

**B 端为什么不把门店存进会话**：门店由每个请求的 `X-Store-No` 头决定，
一个店长可以在多个门店之间切换；`BizIdentityResolver.resolve(userNo, storeNo)`
每次现算并校验归属。存进会话就出现第二个真源，
而**过期的那一个会让「切了门店但权限还是上一个店的」**——
这类缺陷在界面上看是「数据串了」，排查方向会完全跑偏。

> 这条与运营端 `perms` 现算是同一个原则：**变动频率高于会话生命期的东西，不进会话。**

## 13.4 撤销的触发点，三端各不相同

同一个 `revokeUser` 接口，但**必须调它的时机**是三套业务规则：

| 端 | 必须踢人的时刻 |
|---|---|
| 运营端 | 改角色、改数据域、停用账号 |
| B 端 | 员工被移出门店/实体、员工被停用、**商家整体被停用或套餐到期收权** |
| C 端 | 主动登出、账号封禁、注销 |

B 端那条今天很可能是缺的（员工被移出后，他手里的令牌到期前照常能用）。
**这是分池之后才好补的**：现在踢一个商家员工要在 C 端的池子里按 `userNo` 找，
而那个 `userNo` 是商家账号号——语义已经错位了。

> 实现任务里为此单列一条：**逐一核对三端的撤销触发点，缺的补上并各配一条测试。**
> 「停用后立即无法操作」是接口契约里已经写着的话，不是新需求。

## 13.5 Realm 扩到三个，前缀成为第一道闸

```java
public enum Realm { CONSUMER, MERCHANT, OPERATOR }

ctk_… → usr_session    btk_… → mch_session    otk_… → ops_session
```

前缀校验**不查库**：拿 C 端令牌打 `/biz/**` 或 `/ops/**`，在过滤器第一行就 401。
这条今天对运营端已经成立（`otk_` 与 `ctk_` 分得开），
分池之后 B 端才第一次拥有它。

过滤器相应从两个变三个：`ConsumerTokenAuthFilter` / **`MerchantTokenAuthFilter`（新）**
/ `OperatorTokenAuthFilter`，各自绑定 `/mp/**`、`/biz/**`、`/ops/**`。
三者共享同一个抽象基类，差别只有「用哪个 store、认哪个前缀」。

## 13.6 切换顺序：**运营端 → B 端 → C 端**

分批，不要一次全切。理由是**故障影响面递增，而发现难度递减**：

| 批 | 影响 | 为什么放这个位置 |
|---|---|---|
| **1. 运营端** | 十几个人重新登录 | 人最少、就在身边、出问题几分钟内就有人说 |
| **2. B 端** | 商家重新登录；**同时是行为变更**（换池） | 要和 b-app 一起验；前端不感知前缀（已确认无硬编码），但 APK 里的旧令牌会全部失效 |
| **3. C 端** | 全部消费者重新登录 | 人最多、最难联系，放最后 |

每批之间**至少隔一天**，并确认上一批的 `revoke` 传播测试在生产上真的成立
（踢一个人，另一个实例 5 秒内拒绝）。

> 当前是零数据窗口：平台 0 订单、6 个商家均为演示数据、运营端从未上线。
> **这类需要全员重新登录的切换，以后不会再有这么便宜的时机。**

## 13.7 一次性做完 vs 分三次做

代码**一次性做完**（一套实现三次装配，分开做反而要写三遍装配与三遍测试），
**上线分三批**。这是本节的核心建议：

```
A1–A6 一次做完 ──▶ 三端代码 + 三张表 + 三套测试全部就位
                      ↓
A7 分三批切换 ──▶ 运营端 → （隔一天）B 端 → （隔一天）C 端
```

## 13.8 实现任务（替换第 12 节的 A1–A8）

- [ ] A1 `shop-auth-store`：`SessionRow` / `SessionDao` / `SessionTable`（JdbcClient，零 MyBatis）
- [ ] A2 迁移：`usr_session` / `mch_session` / `ops_session` 三张表 + 重跑 `gen-test-schema.py`
- [ ] A3 `Realm` 加 `MERCHANT`；`TokenStore.newToken` 支持 `btk_`
- [ ] A4 `DbTokenStore`（一个类）× 三次装配，**各自独立的本地缓存**
- [ ] A5 `MerchantTokenAuthFilter` + 三个过滤器抽公共基类，按路径前缀绑定
- [ ] A6 B 端签发改为 `Realm.MERCHANT`（`MerchantStaffServiceImpl` 第 90 行）
- [ ] A7 **逐一核对三端撤销触发点**，补齐 B 端缺的（移出门店/停用/收权），各配一条测试
- [ ] A8 测试 T1–T8 × 三端；T4（跨实例撤销）与 T6（不做负缓存）做撤掉修复验证
- [ ] A9 `session-purge` 定时任务清三张表
- [ ] A10 生产分三批切换（13.6），每批之间验一次跨实例撤销
- [ ] A11 ADR：为什么不是 JWT、为什么不是现在上 Redis、**为什么三端分池**

## 13.9 三端方案新增的风险

| 风险 | 缓解 |
|---|---|
| B 端换池是**行为变更**，不只是存储变更 | 单独一批上线；APK 需重新登录，发版说明写明 |
| 三张表 → 有人复制三份 DAO | A1 就把 `SessionTable` 做成参数；review 时看有没有出现第二个 `SessionDao` |
| 三份缓存 → 有人"优化"成一份共用 | 代码注释写明「共用等于把刚分开的边界在内存里又合上」，并配一条断言 |
| B 端撤销触发点补漏时改到业务代码 | 那是必要的修复（接口契约里已承诺「停用后立即失效」），但要单独提交、单独说明 |

---

# 14. 三端的表、C 端轻量化、与「将来分库」

## 14.1 九张表，其中三张已经有了

| 端 | 用户表 | 会话表 | 登录日志表 |
|---|---|---|---|
| C | `usr_account` ✅**已有** | `usr_session` 新 | `usr_login_log` 新 |
| B | `mch_account` ✅**已有** | `mch_session` 新 | `mch_login_log` 新 |
| 运营 | `sys_ops_staff` ✅**已有** | `ops_session` 新 | `ops_login_log` 新 |

用户表三张早就是分开的（`V1__baseline.sql`）——**今天混在一起的只有会话**。
所以这件事的本质不是「拆三份」，是**把已经分开的身份边界补齐到会话与审计层**。

**迁移按端分三条写，不合并成一条。** 现在没有区别，将来把某一端搬去独立库时，
「只把这一端的迁移拿过去跑」才成立。合成一条的话，拆库那天要先做一次拆迁移。

## 14.2 一套代码，三份档位：`SessionProfile`

C 端轻量化不靠另写一套，靠**参数**。同一个 `DbTokenStore` 装配三次，各持一份档位：

```java
record SessionProfile(
    Realm   realm,
    String  tokenPrefix,        // ctk_ / btk_ / otk_
    String  sessionTable,
    String  loginLogTable,
    Duration sessionTtl,        // 会话有效期
    Duration cacheTtl,          // L1 本地缓存：**决定回源频率**
    Duration revokePoll,        // 撤销轮询：**决定撤销延迟**
    Duration lastSeenThrottle,
    boolean  asyncLoginLog)
```

### 关键：**回源频率与撤销延迟是两个旋钮，可以分开拧**

这条是 C 端能轻量化而不牺牲安全的全部原因：

- `cacheTtl` 大 → 查库少（轻）
- `revokePoll` 小 → 踢人快（安全）

两者互不影响。**轻量化调的是前者，撤销保证由后者给**——
把它们当成一个旋钮（「缓存久 = 踢人慢」）是最常见的误解，
而按那个误解设计，C 端就只能在「贵」和「不安全」之间选。

| 参数 | C 端（轻） | B 端 | 运营端 | 说明 |
|---|---|---|---|---|
| `cacheTtl` | **5 分钟** | 60 秒 | 60 秒 | C 端会话量可能高三个数量级，回源频率直接决定库压力 |
| `revokePoll` | **10 秒** | 5 秒 | 5 秒 | 封禁必须生效；C 端稍宽是因为撤销事件本身稀少 |
| `lastSeenThrottle` | **24 小时** | 1 小时 | 1 小时 | C 端这一列只用于「这个会话还活着吗」，24 小时精度足够 |
| `asyncLoginLog` | **是** | 是 | 否 | 运营端登录稀少且审计要求最高，同步写更可靠 |
| 会话载荷列 | **无额外列** | 无额外列 | roles/perms/tenant/scope | 见 13.3 |
| 过滤器链 | **最短**：无 RBAC、无 `BizContext` 解析 | + `BizIdentityResolver` | + `LivePermResolver` | C 端本来就不需要那两步 |

> C 端的「轻」是四件事叠出来的：**列最少、回源最少、写回最少、过滤器链最短**。
> 没有一件是砍掉安全保证。

## 14.3 登录日志表

三张同构，只有表名与保留期不同：

```
id / at / event / user_no / result / reason
/ client_ip / user_agent / realm_extra
```

`event`：`LOGIN` / `LOGOUT` / `LOGIN_FAILED` / `REVOKED`（被踢，带 `revoke_reason`）。

### 三条决定

**① IP 与 UA 记在这里，不记在会话表。**
第 3.1 节说过会话表不存 PII，因为那张表将来要被多个服务读；
而登录日志是**审计**，它就该有这些，且访问面窄、保留期短。

**② 日志表不是控制平面。**
若将来要「失败 N 次锁定账号」，那个计数单独放（现有 `RateLimiter` 就是这么做的：
密码尝试限流是同一手机号 15 分钟 10 次，与任何日志表无关）。
理由是审计可以丢、可以异步、可以采样，而控制平面不行 ——
**把两者合在一张表上，等于让安全策略依赖一条允许丢失的写入。**

**③ 成功登录异步写，失败登录同步写。**
登录接口是最容易被刷的接口之一；成功日志异步（有界队列，满了丢并计数）不影响响应，
而失败日志正是被刷时最该留下的证据，不能丢。
运营端两者都同步——它的登录量一天不到一百次。

保留期：C 端 90 天，B 端 180 天，运营端 **730 天**（运营操作要能追溯到两年前）。
清理由 `session-purge` 一并做。

## 14.4 「将来分库」的四条硬约束（现在就要遵守，成本为零）

1. **三组表之间零 join、零外键。** 会话表只存 `user_no` 字符串，
   不做指向用户表的外键 —— 外键会在拆库那天变成第一个要拆的东西。
2. **每个 Realm 的 DAO 只认自己的 `JdbcClient`**（构造参数注入）。
   换库 = 换一个 bean，代码一行不动。这与 `shop-job-store` 是同一手法。
3. **登录路径只查本端的用户表。** C 端查 `usr_account`、B 端查 `mch_account`、
   运营端查 `sys_ops_staff` —— 这条今天已经成立，不要在新代码里破坏它。
4. **跨界的两处必须显式登记**（见 14.5），不许再增加第三处。

## 14.5 三端拆库的难易，恰好与轻量化同序

| 端 | 拆库时还需要什么 | 难度 |
|---|---|---|
| **C 端** | 登录查 `usr_account`、会话查 `usr_session`、日志写 `usr_login_log` —— **闭环** | ✅ **最容易，可以先拆** |
| B 端 | 还要 `BizIdentityResolver` 读 `mch_entity` / `mch_store` / `mch_staff` | ⚠️ 与商户域同库，或改走 API |
| 运营端 | 还要 `LivePermResolver` 读 `sys_role_point`，菜单读 `sys_function*` | ⚠️ 与平台配置同库，或改走 API |

> **C 端最轻、最独立、最容易先拆出去 —— 这三件事是同一件事。**
> 而它恰好也是量最大、最先需要拆的那一端。这不是巧合：
> 它的轻正来自「没有 RBAC、没有实体/门店归属」，而那两样正是另外两端拆不动的原因。

拆库之后 `session-purge` 变成三份（每库一份）——
定时任务模块的 `job_definition.target` 已经是为这种情况准备的，不用改设计。

## 14.6 任务增补（接 13.8）

- [ ] A2′ 迁移**按端分三条**：`usr_session`+`usr_login_log` / `mch_*` / `ops_*`
- [ ] A4′ `SessionProfile` 三份档位（表 14.2 的数值写成配置，不写死在代码里）
- [ ] A12 登录日志写入：成功异步（有界队列 + 丢弃计数）、失败同步
- [ ] A13 一条架构测试：**会话表/日志表与其它表之间不得出现 join 或外键**
      —— 分库就绪不是一次性检查，是要守住的性质
- [ ] A14 `session-purge` 按端清理，保留期各不相同
- [ ] A15 ADR 增补：为什么三端分池、为什么 C 端轻、为什么两个旋钮要分开

## 14.7 新增风险

| 风险 | 缓解 |
|---|---|
| 有人把 `cacheTtl` 与 `revokePoll` 当一个旋钮，"顺手"把 C 端撤销也放宽 | 14.2 那段写明理由；跨实例撤销测试对三端各跑一次 |
| C 端日志表增长最快，异步写掩盖问题 | 丢弃要计数并暴露成指标，**丢了要看得见**；否则「日志少了」永远查不出来 |
| 有人给会话表加外键"保证一致性" | A13 架构测试直接拦 |
| 分三条迁移后取号更容易撞 | 一次取三个连号，取前看当前最大号（现为 V262） |
