# TDD · 统一鉴权与会话外置（DB + 本地缓存）

状态：**待确认**
关联需求：无独立 PRD —— 这是一条**技术约束驱动**的改造（多实例部署 + 将来的统一鉴权），
不是产品功能。需求来自 2026-08-26 的决定：先解决统一鉴权，运营端优先，
兼容 Redis，但当前没有 Redis，改用 **DB 存取 + Ehcache 同步缓存**。
创建日期：2026-08-26

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
