# SSH 加固

安装位置：`/etc/ssh/sshd_config.d/10-hardening.conf`（600 root）。

```bash
sudo install -m 600 -o root -g root 10-hardening.conf /etc/ssh/sshd_config.d/
sudo sshd -t && sudo systemctl reload ssh
```

## 为什么文件名要以 `10-` 开头

`/etc/ssh/sshd_config` 第 12 行先 `Include /etc/ssh/sshd_config.d/*.conf`，
而 **sshd 以先读到的值为准**。目录里按文件名排序，所以：

- `10-hardening.conf` 先读 → 它的值生效
- `50-cloud-init.conf`（cloud-init 装的，写着 `PasswordAuthentication yes`）后读 → 被忽略
- 主配置第 33 行 `PermitRootLogin yes` / 第 123 行 `PasswordAuthentication yes` 更靠后 → 也被忽略

**只改主配置那两行什么都不会变** —— 这是这台机器上最容易踩的一处。

## 改完怎么验（三条都要，缺一条就不算验过）

```bash
# ① 公钥还能进（两个入口都要试，全新连接）
ssh soukmind-tx      'echo OK-$(whoami)'   # → OK-deploy
ssh soukmind-tx-root 'echo OK-$(whoami)'   # → OK-root

# ② 消融：把公钥关掉，必须被拒 —— 证明口令那条路真的没了
ssh -o PubkeyAuthentication=no -o NumberOfPasswordPrompts=0 root@<IP> true
#   → Permission denied (publickey).        ← 括号里只剩 publickey

# ③ 服务端还提供哪些认证方式（改前是 publickey,password）
ssh nosuchuser@<IP> true
#   → Permission denied (publickey).
```

`sshd -T | grep passwordauthentication` 只说明**配置**对了，
说明不了**新连接**真的进得来 —— ① 才是。

## 改之前先证明「这一刀切不到任何人」

```bash
# 只认 sshd 自己写的行。裸 grep 'Accepted password' 会把 sudo 审计里
# 你自己那条查询命令算成命中（2026-09-16 实测数出 6 条假命中）
for k in "Accepted password for" "Accepted publickey for" "Failed password for"; do
  printf '%-26s %s\n' "$k" \
    "$(sudo grep -hEc "sshd\[[0-9]+\]: $k" /var/log/auth.log /var/log/auth.log.1)"
done
```

2026-09-16 实测：口令成功 **0** · 公钥成功 137 · 口令失败 **11988**。

## 回滚

```bash
sudo rm -f /etc/ssh/sshd_config.d/10-hardening.conf && sudo systemctl reload ssh
```

**改之前先装一个自动回滚**，别指望「另开一个终端别关」这种运气：

```bash
sudo systemd-run --unit=ssh-autorevert --on-active=10min \
  /bin/sh -c 'rm -f /etc/ssh/sshd_config.d/10-hardening.conf; systemctl reload ssh'
# 验过三条之后再撤，撤完要回读 list-timers 确认真撤掉了
sudo systemctl stop ssh-autorevert.timer && sudo systemctl reset-failed ssh-autorevert.timer
systemctl list-timers --all | grep -c ssh-autorevert   # → 0
```

最坏的退路是腾讯云控制台 VNC。

---

# 抗扫描洪水（2026-09-16 下午）

**关掉口令登录之后攻击没停，只是换了形态。** 加固后 126 分钟实测：

| | |
|---|---|
| 连接尝试 | **2776 次 / 32 个 IP**，96% 来自 4 个 |
| 形态 | 708 次拿 `root`、592 次拿 `ubuntu` 试公钥；1193 次 `kex_exchange_identification`（连上 TCP 就断的纯扫描） |
| 口令类 | `Failed password` **0**、`Accepted password` **0** —— 那条路在协议层已经不存在 |
| 成功登录 | 12 次，**全部 `Accepted publickey`、全部来自我们自己的出口** |

**所以这不是「会被攻破」，是「被打扰」。** 但它已经造成可用性损害：

```
error: beginning MaxStartups throttling
drop connection #10 from [82.156.126.92]:51144 past MaxStartups
exited MaxStartups throttling after 00:00:33, 3 connections dropped
```

`MaxStartups 10:30:100` 是**全局**额度，单个 IP 一家就占了 8 条并发未认证连接，
把额度吃光；**而丢弃是随机的，包括我们自己的登录**。

## 三层处置

### ① `PerSourceMaxStartups 3`（20-flood.conf）—— 止血

把额度按来源 IP 分，一家吃不光全局。不装任何软件，秒级生效、秒级回退。
`NetBlockSize 32` = 按单个 IPv4 算，不合并网段（合并会误伤同出口的其他人）。

验证：改后 5 分钟 **0 次 drop、0 次 throttling**（改前 126 分钟 14 次）；
未认证 sshd 子进程稳定在 0–1（上限 3）。

> ⚠️ 量并发**不能用 `ss state all`** —— 它把 `TIME_WAIT`/`FIN_WAIT` 也算进去，
> 会看到「6 条并发」而实际未认证的只有 1 条。判据是 `ps -eo args | grep 'sshd: \[net\]'`。

### ② fail2ban（`fail2ban-jail.conf` → 装成 `/etc/fail2ban/jail.local`）—— 治本

```bash
sudo install -m 644 fail2ban-jail.conf /etc/fail2ban/jail.local
sudo fail2ban-client -t && sudo systemctl restart fail2ban
```

> 仓库里叫 `.conf` 不叫 `.local`：`.gitignore` 第 10 行有 `*.local`，
> 直接放 `jail.local` 会被**静默忽略** —— `git add <目录>` 不报错，文件就是没进去。


**`mode = aggressive` 不是可选项。** 默认的 `normal` 只匹配认证失败，
而这次攻击的大头是 `kex_exchange_identification` 和 `Connection closed ... [preauth]`，
用 normal 一条都抓不到 —— **装了等于没装**。

`bantime` 先给 1 小时不给永久：规则写错误伤了人，一小时后自己好。
`ignoreip` 必须先写好再谈封禁，顺序反了就是把自己关在门外。

验证（每一条都要，缺一条都可能是装饰）：
- `iptables -S INPUT` 里**有跳到 `f2b-sshd` 的规则** —— 没人跳的链等于装饰；
- `iptables -L f2b-sshd -n -v` 的 REJECT 行**包计数非 0** —— 真挡下了流量；
- 被封 IP 在 journal 里**封禁后 2 分钟 0 行**（封禁前 30 分钟 98 行）；
- 对照：**没被封的 IP 仍在出现** —— 否则可能是量具坏了而不是封禁生效。

> 踩过的两个坑：`fail2ban-client reload` **不重建 iptables 规则**，改端口要 `restart`；
> 而 `restart` 又会留下旧的 INPUT 规则，要手工 `iptables -D` 清掉重复的那条。

### ③ 换端口（ssh.socket.d/10-ports.conf）—— 对无差别扫描立竿见影

**⚠️ 端口写在 `ssh.socket`，不是 `sshd_config`。**
Ubuntu 24.04 用 socket 激活：`ssh.socket` 持有监听套接字再交给 sshd，
于是 `sshd_config` 里的 `Port` **被完全忽略** —— 实测 `sshd -T` 报
`port 22 port 57022`，而 `ss -ltnp` 里只有 22。
**「配置读进去了」不等于「端口在听」，判据只能是 `ss`。**

空的 `ListenStream=` 是必需的：不清空则新值**追加**到单元自带的 22 上而不是替换。

**云防火墙才是决定可达性的那一道**，它在控制台里、不在这台机器上。
实测对照（三个端口同一条命令）：

| 端口 | 外部表现 | 含义 |
|---|---|---|
| 22 | `Permission denied (publickey)` | 到了 sshd |
| 57022 | `Connection closed` | 被云防火墙 RST |
| 51999（无人监听） | `Connection closed` | **与 57022 完全一致** |

所以**换端口的顺序不能错**：

1. 控制台放行新端口 ← **只有人能做**
2. 这台机器同时听两个端口（已做）
3. 从外面验新端口通
4. 改本机 `~/.ssh/config`
5. 从 `ssh.socket.d/10-ports.conf` 里删掉 `ListenStream=22`
6. 控制台关掉 22

**第 2 步先做、第 5 步最后做**：过渡期两个端口都在听，任何一步出错都还有 22 兜着。
