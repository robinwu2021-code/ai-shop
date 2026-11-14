---
name: run-local
description: >
  在本机（macOS arm64）把 ai-shop 跑起来：后端 shop-app（:8080，api+ops）+ 运营端 ops-web（:3100），连本机 MySQL 9.7。
  用户说「启动项目 / 跑起来 / 本地运行 / 起后端 / 起运营端 / 验证改动在真实应用里生效」时用本篇。
  含一次性环境准备（JDK21/Maven/Node22/Nexus 匿名仓库）、从零建库（MariaDB→MySQL 混合迁移）、启动与验证、已知坑。
---

# 本机运行 ai-shop（后端 + 运营端）

2026-11-14 在一台全新 Mac 上冷启动跑通后整理。命令都可直接执行；脚本在 `scripts/`。

## 0. 一次性环境（缺什么补什么）

| 需要 | 检查 | 本机做法（全部装在用户目录，不要管理员密码） |
|---|---|---|
| git | `git --version` | `xcode-select --install`；弹窗不出来就 `sudo softwareupdate -i "Command Line Tools for Xcode <ver>"`（`softwareupdate -l` 前先 `touch /tmp/.com.apple.dt.CommandLineTools.installondemand.in-progress`，**装完再删**，中途删会报 Error installing updates） |
| JDK 21 | `/usr/libexec/java_home -v 21` | Temurin 21 tar.gz 解到 `~/Library/Java/JavaVirtualMachines/`（enforcer 锁 `[21,22)`） |
| Maven 3.9 | `mvn -v` | apache-maven tar.gz 解到 `~/.local/opt/`，加 PATH |
| Node ≥ 22 | `node -v` | node darwin-arm64 tar.gz 解到 `~/.local/opt/`，加 PATH |
| Docker | `docker info` | 已有即可 |

**Nexus 不需要账号**：公开组 `https://nexus.neargo.ai/nexus/content/groups/public`（Nexus 2 路径；`/repository/...` 是 404）。
`~/.m2/settings.xml` 里写一个默认激活的 profile，repository 与 pluginRepository 都指向它、snapshots 开启。首次构建带 `-U`。

本机私有配置在 `~/work/env/`（**含密钥，只读取不外传**）：`backend/.env.local`、`ops-web/.env.local` 等。

## 1. 构建

```bash
mvn -f backend/pom.xml -U -B -q -DskipTests -pl shop-app -am install   # 首次约数分钟
npm install --no-audit --no-fund                                         # 仓库根，workspaces
git restore package-lock.json   # npm 版本差异只改 libc 元数据，别带进提交
```

## 2. 数据库（一次性）

**不能直接从零迁移**：CORE V1–V574 用 MariaDB 专有排序规则 `utf8mb4_uca1400_ai_ci`（MySQL 报 1273），
V575 起的生成列 `CASE … IN (...)` MariaDB 拒绝（1901，实测 12.3 仍不支持）。生产 2026-09 已切 MySQL 9.7。

```bash
bash .claude/skills/run-local/scripts/bootstrap-db.sh
```

它做：临时 MariaDB 12@3307 跑到 V574 → 导出（排除 V575 半建的 `sv_settlement_bill`、`sv_settlement_bill_item` 与失败记录）
→ `maria-to-mysql.pl` 把 `uca1400` 转 `utf8mb4_unicode_520_ci`、CHECK 名加表哈希前缀（MySQL 要求库内唯一）
→ 导入 MySQL 9.7@3308（`ai-shop-mysql97`，参数对齐 `deploy/tencent/mysql97/my.cnf`，`--skip-log-bin` 免触发器权限问题）
→ 逐表行数对账。已有 `ai-shop-mysql97` 时直接跳过，不覆盖。
与 `docs/technical/餐饮本地MySQL测试环境.md` 同一原则：不改迁移文件/checksum，不 `flyway repair` 伪造成功。

仓库根的 `docker-compose.yml`（mysql:9.1@3306 + redis）**不适用于后端**：空库 V1 就失败。Redis 后端默认不需要（token-store=memory）。

## 3. 启动

```bash
bash .claude/skills/run-local/scripts/start-backend.sh       # 前台；后台跑就 > backend/logs/shop-app-local.log 2>&1 &
npm run dev:p                                                 # ops-web @3100
```

- 首次启动 Flyway 从 V575 跑到最新（2026-11-14 为 V666），另有两条链（v16、v2）在同库初始化；约 30 秒。
- `start-backend.sh` 已设：`SHOP_OPS_SECOND_FACTOR=off`（否则登录要邮箱 6 位码，本机邮件是桩收不到）、
  `shop.seed.enabled=true`（空库灌演示账号）。**两者都只许本机用**。
- ops-web 的 `.env.local`：从 `~/work/env/ops-web/.env.local` 复制，`NEXT_PUBLIC_API_BASE=http://127.0.0.1:8080`、`NEXT_PUBLIC_USE_MOCK=0`。
  只想看界面不起后端：`NEXT_PUBLIC_USE_MOCK=1 npm run dev:p`。

## 4. 验证（要真的驱动，不只是起进程）

```bash
curl -s http://127.0.0.1:8080/actuator/health        # 刚起来几秒是 OUT_OF_SERVICE/503，稍等变 UP
```

浏览器开 **`http://localhost:3100`**（不是 127.0.0.1，见坑①）→ `admin` / `admin123` 登录 → 经营看板。
真后端时顶栏**没有**「Mock 数据」标签，数字为 0；后端日志 `api.access` 能看到 `GET /ops/dashboard/kpi s=200` 等。

## 已知坑

1. **`127.0.0.1:3100` 白屏**：Next dev 对非 localhost 源返回 403、HMR WebSocket 连不上。用 `localhost:3100`。
2. **`next dev` 每次启动都会改写 `ops-web/AGENTS.md`**（`##` → `#`）。提交前 `git restore ops-web/AGENTS.md`。
3. **登录后停在「邮箱验证码」页**：后端没带 `SHOP_OPS_SECOND_FACTOR=off`。
4. **DevSeeder 只在空库种**（判据 `cmt_community` 无数据）；库里已有社区数据就不会种运营账号。
5. **3306 已被 compose 的 MySQL 9.1 占用**，后端务必用 3308 的 URL；`application.yml` 默认指 3306。
6. Flyway 会警告「MySQL 9.7 newer than verified 8.1」、「Integer display width is deprecated」——无害。
