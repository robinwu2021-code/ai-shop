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
