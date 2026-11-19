# 网关层分支 · 192.168.3.80 测试环境

状态：2026-11-19 已部署并通过冒烟。分支 `feat/gateway-layer-20261118`，提交 `1ce21cf86`，
应用包 SHA-256 `f32688eb5c9588827f7d1ef5a3e5c5bc68cf5e1f1317dfd263a0a40dcded1276`。

## 一句话

192.168.3.80（`ume-els1`，原先空闲）上起了一套与生产完全隔离的 ai-shop：自己的 MySQL、自己的空库加演示数据、
两个应用实例。没有改动 .166、.61、.201 上的任何东西，也没有连过它们。

## 与主分支对齐

部署前拉取了远端：`origin/main` 相对本分支没有新提交（本分支接在 `6e9392abd` 之上 36 个提交），没有需要合并的内容。

## 机器上有什么

全部在 `/data0/app/ai-shop-test/` 下；另有一个 systemd 单元 `aishop-test-docker.service`。

| 东西 | 位置 | 说明 |
|---|---|---|
| 容器运行时 | `runtime/`，套接字 `/run/aishop-test-docker.sock` | Docker 26.1.4 静态包，自成一体。不建网桥、不动 iptables，容器用主机网络 |
| MySQL 9.7.4 | 容器 `aishop-test-mysql`，`127.0.0.1:3307` | 只绑本机回环。库 `ai_shop_t80`，账号 `shop_test` 只有这一个库的权限 |
| 运营端实例 | 容器 `aishop-test-ops`，端口 18081 | `ops` 面：`/ops/**`、`/pay-ops/**` |
| C 端与 B 端实例 | 容器 `aishop-test-api`，端口 18080 | `api` 面：`/mp/**`、`/biz/**` |
| 起停、冒烟 | `app/run.sh <api\|ops> <start\|stop\|restart\|logs\|status>`、`sh app/smoke.sh` | |
| 密钥 | `secrets/`（权限 700） | 在机器上随机生成，只用于这套测试环境 |

为什么用容器而不是直接装：这台机器是 CentOS 7（glibc 2.17），MySQL 9.7 的通用包要 glibc 2.28，本机也没有 JDK 21。
容器运行时用静态包放在自己的目录里，没有经过 yum，没有碰系统已有的 JDK 8 / JDK 11 与 Jenkins 目录。

## 库是怎么来的

仓库的迁移不能在 MySQL 上直接从 V1 跑（早期迁移有 MariaDB 方言）。做法是在开发机本地的 MySQL 9.7.4 上，
用本分支的包从零重建一个新库 `ai_shop_t80`（596 条主迁移到 V681，支付模块 17 条到 V16），再启动一次让演示数据种进去，
然后导出、导入到测试机。所以测试机上 `V679`、`V680`、`V681` 都已经在库里，不需要 root 另外执行。

重建时遇到一处要记下来的事：分段跑迁移的中间阶段，支付模块自己的那套迁移会被提前触发，在主库表还没建全时失败并留下一条失败记录。
失败没有留下半截改动（核对过目标表上没有那两列）；删掉失败记录后在完整的库上重跑，17 条全部成功。

## 怎么保证不影响生产

- 应用的环境文件里没有任何地址（没有 `192.168.3.*`、没有域名、没有 `http`）；数据源指向本机回环。
- 短信、邮件、推送、微信登录/订阅/发货全部走桩；微信支付关闭；支付是同进程形态，没有配任何渠道与回调地址；图片存本机目录。
- 进销存、定时任务、自动化票据都关着。
- 两个实例的日志里没有出现生产网段或生产域名；机器上已建立的连接只有本机回环、跳板机的 SSH 和云平台的元数据地址。
- 没有配 nginx，公网到不了这两个端口；内网 `192.168.3.*` 可以直接访问 `18080`、`18081`。

## 网关相关的设置

| 设置 | 值 |
|---|---|
| `shop.gateway.ops.precheck`、`shop.gateway.biz.precheck` | `observe`（只记日志，不拦截） |
| `shop.gateway.signing.key` | 已配（随机值） |
| `shop.gateway.<端>.proxy` | 没开 |
| `shop.ops.auth.second-factor` | `off`（测试用） |
| `shop.seed.enabled` | `true`（演示数据与演示账号） |

## 冒烟结果（2026-11-19）

```
1 运营端登录: 成功（令牌前缀 otk_）
2 当前身份: 0 admin
3 接口资源汇总: precheck=observe, proxy=False, total=1096, disabled=0, stale=0, manual=0
  端 ops: 538 条    端 pay-ops: 10 条    端 biz: 548 条
4 菜单: 0 分区数 18
5 未登录访问运营端接口: http 401
6 停用前 GET /ops/app-versions: 0 → 停用: DISABLED → 停用后: http 404 → 恢复: ENABLED → 恢复后: 0
7 B 端未登录: http 401
8 运营端令牌打 B 端: http 401
9 C 端公开接口 /mp/category/tree: http 200
10 api 实例上没有运营端入口: http 404
```

两个实例各有 1 条 ERROR 日志：没有配 `SHIP_TRACK_KEY`，看件令牌停用。与本次无关，测试环境不需要它。

启动用了约 135 秒（两个实例同时起，机器只有 2 核）；本机单独起是 10 秒。

## 没有做的

- **运营端前端（ops-web）、商户 App、小程序都没有部署**，现在只能用接口访问。
- 没有 B 端商家登录的冒烟（要走验证码流程），B 端提前判权只验证到「索引 548 条已入库、未登录 401」。
- 餐饮、美业、商城的插件没有打进这个包（默认构建不带），进销存关着。
- 没有配开机后的应用自启以外的任何监控与备份；容器设了 `unless-stopped`，机器重启后会跟着运行时一起起来，但没有实际重启验证过。

## 怎么连

经 UME 跳板机，用 `~/work/env/ume/ssh.config` 里 `ume-jump` 的同一把密钥，用户 `ume`：

```
Host ume-test80
    HostName 192.168.3.80
    User ume
    IdentityFile ~/work/ume/ume-id_rsa
    IdentitiesOnly yes
    ProxyCommand ssh -F ~/work/env/ume/ssh.config ume-jump -W %h:%p
```

这段没有写进 `~/work/env/ume/ssh.config`（那份文件的主机公钥是逐个核验过的），用的时候自己加或用 `-o` 传。

## 整套删除

```
sudo systemctl disable --now aishop-test-docker
sudo rm /etc/systemd/system/aishop-test-docker.service
sudo rm -rf /data0/app/ai-shop-test /run/aishop-test-docker
```
