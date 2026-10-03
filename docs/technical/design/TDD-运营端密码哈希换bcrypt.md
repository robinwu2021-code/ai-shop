# TDD-运营端密码哈希换 bcrypt

状态：**已实现**
关联需求：[三端角色权限功能对齐清单](../../requirements/三端角色权限功能对齐清单.md) §三
关联设计：[TDD-backend](TDD-backend.md) §5（认证与越权防线）
创建日期：2026-08-12

---

## 1. 需求摘要

运营端密码当前用 `Integer.toHexString(("shop$" + raw).hashCode())` 存储。
代码注释写着「一期占位哈希，**接 auth-core 时换 bcrypt**」。

验收标准：

1. 新设/改过的密码一律 bcrypt 存储
2. **存量账号不需要任何人重置密码** —— 用一次自然升级
3. 升级过程可观测：能查出还有多少账号是旧格式
4. 旧哈希函数不再被任何登录路径调用

---

## 2. 当前架构分析

### 2.1 这个哈希有多弱

```java
Integer.toHexString(("shop$" + raw).hashCode())
```

- **32 位输出**，8 个 hex 字符。生日碰撞在 2^16 量级 —— 几万次尝试即可撞上
- **无盐**：同密码同哈希，一张彩虹表通吃全部账号
- **零计算成本**：`String.hashCode` 是线性乘加，离线爆破速度以每秒十亿计
- `hashCode` 的算法是 JDK 规范的一部分，公开可逆推

它不是「弱一点的哈希」，是**基本等价于明文**。唯一的缓解是运营端只在内网可达。

### 2.2 影响面（已逐个确认）

| 位置 | 行为 |
|---|---|
| `OpsServiceImpl.login` | `hash(password).equals(staff.getPassword())` |
| `OpsServiceImpl.createStaff` | 建号时 `hash(randomPassword())`，并置 `mustChangePassword` |
| `OpsServiceImpl.changeOwnPassword` | 先验旧密码，再 `hash(new)` |
| `OpsServiceImpl.hash` | 静态方法，**`DevSeeder` 也在调** |
| `DevSeeder` | 播种账号 |

**B 端与 C 端不涉及**：`/biz/auth/*` 走验证码，没有密码。

### 2.3 两个有利条件

- `password VARCHAR(128)`，bcrypt 的 60 字符放得下 —— **不需要改表**
- `spring-security-crypto` 已在 classpath（`BCryptPasswordEncoder` 拿来即用）—— **不加依赖**

---

## 3. 方案设计

### 3.1 方案选型

| 方案 | 做法 | 结论 |
|---|---|---|
| A 一刀切 | 换 bcrypt，存量哈希全部作废，所有人重置密码 | ❌ 运营端十几个账号也要逐个通知，且没必要 |
| **B 双格式共存 + 登录时升级**（推荐） | 验证时按格式分派；旧格式验过之后就地重写成 bcrypt | ✅ 采用 |
| C 等 auth-core | 一直不换 | ❌ 「等」没有期限，而这是 🔴 |

### 3.2 核心设计：格式自描述，不加字段

bcrypt 串以 `$2a$` / `$2b$` 开头，旧哈希是 8 位 hex —— **看串本身就知道是哪种**，
不需要在表上加 `password_algo` 列。少一个字段就少一处「忘了一起改」。

```java
boolean matches(String raw, String stored) {
    return stored.startsWith("$2")
            ? encoder.matches(raw, stored)
            : legacyHash(raw).equals(stored);
}
```

**升级发生在登录成功之后**（唯一能拿到明文的时刻）：

```java
if (ok && !stored.startsWith("$2")) {
    staff.setPassword(encoder.encode(raw));
    staffMapper.updateById(staff);
}
```

> ⚠️ 只在**验证通过**之后升级。验证失败也重写等于把错误密码写进去。

### 3.3 模块设计

**新增**：`ai.neargo.shop.auth.PasswordHasher`（shop-base）

放 shop-base 而不是留在 `OpsServiceImpl`：它是**安全原语**，
将来 B 端/其它端要用密码时不该再抄一份。当前只有运营端调用。

```java
@Component
public class PasswordHasher {
    /** 新密码一律 bcrypt。 */
    public String encode(String raw);
    /** 验证。旧格式（一期占位哈希）仍可通过，供平滑升级。 */
    public boolean matches(String raw, String stored);
    /** 这个串是不是还停在旧格式 —— 调用方据此决定要不要就地升级。 */
    public boolean needsUpgrade(String stored);
    /** 一期占位哈希。**只保留给验证存量用**，不再产出新值。 */
    static String legacyHash(String raw);
}
```

**修改**

| 位置 | 变更 |
|---|---|
| `OpsServiceImpl.login` | 用 `hasher.matches`；通过且 `needsUpgrade` 则就地重写 |
| `OpsServiceImpl.createStaff` | `hasher.encode(initial)` |
| `OpsServiceImpl.changeOwnPassword` | 验用 `matches`，写用 `encode` |
| `OpsServiceImpl.hash` | **删掉**，调用方改注入 `PasswordHasher`（`DevSeeder` 同） |

### 3.4 可观测性

`GET /ops/staffs` 的 VO 加一个 `legacyPassword: boolean`？—— **不加**。
那是把安全内部状态暴露到接口上，而运营看了也不知道该做什么。

改为**启动时打一条 WARN**：还有 N 个账号是旧格式。
数字降到 0 就不再打 —— 一条会自己消失的日志，比一个永远在那里的字段有用。

### 3.5 配置项

无。bcrypt 强度用 `BCryptPasswordEncoder` 的默认值（cost 10）。
不做成可配：可配的安全参数迟早会被调低，而这里没有需要调的理由。

---

## 4. 测试策略

| # | 场景 | 断言 |
|---|---|---|
| 1 | 旧格式密码能登录 | ★★★ 存量账号不被锁在门外 |
| 2 | 登录成功后库里变成 `$2` 开头，**且能再次登录** | ★★★ 升级不能把人锁死 |
| 3 | 旧格式**密码错误**时不升级、不写库 | ★★★ 否则把错密码写进去 |
| 4 | 新建账号直接是 bcrypt | ★★ |
| 5 | 改密后是 bcrypt，且旧密码不再能用 | ★★ |
| 6 | 同一明文两次 `encode` 结果不同（有盐） | ★★ 这条直接证伪旧实现 |
| 7 | `legacyHash` 不再被产出路径调用 | ★ 架构守卫：全仓 grep |

场景 2、3 是这次改造真正的风险点 —— **升级逻辑写错会把人锁在门外**，
而那种故障发生在「用户输对了密码」的时刻，最难让人相信是系统的问题。

---

## 5. 风险与注意事项

| 风险 | 处置 |
|---|---|
| **升级时机写错，把错误密码写进库** | 只在 `matches == true` 之后升级；场景 3 专门钉这条 |
| bcrypt 比旧哈希慢约 100ms | 登录路径可接受；**不要**把它用在高频判权上（判权走 `LivePermResolver`，与密码无关） |
| `DevSeeder` 播种的账号 | 改用 `encode`，播出来就是 bcrypt |
| E2E / 集成测试里硬编码的密码 | 明文不变（`admin123` 等），只是存储格式变了，测试不受影响 |
| 存量账号一直不登录，永远停在旧格式 | 启动 WARN 报数；真要清干净可后续加一条「N 天未升级则强制重置」，不在本方案内 |
| 旧 `hash()` 是 `public static`，可能有别处在用 | 删除时全仓 grep；`DevSeeder` 是已知的唯一外部调用方 |

---

## 6. 实现任务

- [x] T1 `PasswordHasher`（shop-base）+ 单测（场景 6：同明文两次编码不同）
- [x] T2 `OpsServiceImpl` 三处改造 + 删 `hash()`；`DevSeeder` 改注入
- [x] T3 场景 1–5 的集成测试（`M9aOpsFlowTest` 或新建 `OpsPasswordFlowTest`）
- [x] T4 启动 WARN：还有 N 个账号是旧格式
- [x] T5 架构守卫：`legacyHash` 只出现在 `PasswordHasher` 内部
- [x] T6 全量回归：`mvn test` + `mvn verify -Pe2e`（登录是每条 e2e 旅程的第一步）

---

确认记录：2026-08-12 用户指示「启动 P0-2」


## 7. 实施记录

**结果**：`PasswordHasherTest` 5/5、`OpsPasswordFlowTest` 4/4、后端全量 **705/705**。
e2e 里 J3/J4 全绿、J1/J2 仍卡在既有的保证金门槛（登录全程正常，与本次无关）。

### 验红暴露的后果比预想的严重

TDD §5 把「验证前就升级」的风险写成「把错误密码写进库」。
实际验红时测试卡在**「密码错就该拒绝」**那一行 —— 因为提前升级会把
**攻击者输入的那串**编码成新密码存进去，于是错误密码当场登录成功。

不是「写脏一条数据」，是**一个认证绕过**。

### 一处连带：H2 夹具回放不了 SELECT 式迁移

全量跑时 `OpsPermConfigFlowTest` 红两条，追下去与密码无关：
V75 的重新授权写成 `INSERT … SELECT … JOIN`，真库正确，
但 H2 夹具（`gen-test-schema.py` 从迁移回放）**回放不了 SELECT 式插入，
却能回放 DELETE** —— 夹具停在半应用状态，FINANCE 丢了 `merchant:admission:read`。

补 V76 把那条授权写成显式 VALUES（NOT EXISTS 幂等，真库上空操作），
重新生成夹具后恢复。教训写进 V76 注释：**迁移里凡是要被夹具回放的，尽量用显式 VALUES**。
与 [TDD-权限种子一致性守卫](TDD-权限种子一致性守卫.md) §3.1 否决「内存回放」是同一件事的两面。
