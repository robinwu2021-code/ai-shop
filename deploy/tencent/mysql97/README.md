# MySQL 9.7 LTS · 待机实例

> 状态：**已安装，待机中，没有业务流量** · 2026-09-14
> 用途：将来替代现网 MariaDB 12.3.2。**本目录只管「装上」，不管「切过去」**——
> 切换是换存储，属于开发规范里的 2 档（PRD + TDD + ADR），见文末「迁移前要解决的事」。

## 现状

| 项 | 值 |
|---|---|
| 版本 | 9.7.2（`mysql-9.7-lts` 分支；官方通用二进制 minimal 包） |
| 位置 | 程序 `/opt/mysql/current` → `mysql-9.7.2-linux-glibc2.28-x86_64-minimal` |
| 数据 | `/var/lib/mysql97`（初始 165M） |
| 配置 | `/etc/mysql97/my.cnf`（= 本目录 `my.cnf`） |
| 服务 | `systemctl {status,restart} mysql97`，开机自启 |
| 监听 | **仅** `127.0.0.1:3307`；socket `/run/mysqld97/mysqld.sock`；X 协议关 |
| 内存 | 常驻约 130~160MB；systemd `MemoryHigh=384M` / `MemoryMax=640M` |
| 日志 | `/var/log/mysql97/error.log`，logrotate 已配 |
| root | **只认操作系统身份**（`auth_socket`）：服务器上的 root 直接登，没有任何密码 |

```bash
# 登录（必须带 --defaults-file，否则客户端会去读 MariaDB 的 /etc/mysql/my.cnf）
/opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf -uroot
```

## 为什么这么装

**不走 apt。** 这台机的 `mysql-common` 由 MariaDB 提供（`1:12.3.2+maria`），
`mariadb-server-compat` 也在。apt 装 `mysql-server` 会与之冲突，最坏情况是把 MariaDB 卸掉。
通用二进制包整套落在 `/opt/mysql` 下，**dpkg 数据库里没有它**，与 MariaDB 零交集。

**选 9.7 LTS 而不是字面上最新的创新版 26.7。** 创新版只支持到下一个创新版（约一个季度），
拿来替代生产库等于每季度被迫升级一次。而且 MySQL 的数据字典升级是**单向**的：
LTS → 创新版随时可以，反过来不行。选 LTS 是保留余地的那个方向。

**不用 Docker。** 机器上没装；国内机房连不上 Docker Hub（要另配镜像源）；
多一个常驻的 dockerd 也违背「尽量小」。

**`libaio` 兼容链接放在私有目录。** 通用包链接 `libaio.so.1`，Ubuntu 24.04 改名成了
`libaio.so.1t64`。链接放在 `/opt/mysql/lib-compat`、经 unit 的 `LD_LIBRARY_PATH` 只对本服务生效 ——
不往系统库目录写任何东西。

## 重装 / 改配置

```bash
scp -r deploy/tencent/mysql97 soukmind-tx-root:/opt/mysql/deploy
ssh soukmind-tx-root 'bash /opt/mysql/deploy/install.sh'
```

幂等：数据目录非空就不初始化；会重启本实例（它是待机的，没有流量）。
脚本最后会核对 **MariaDB 的进程号装前装后一致** —— 不一致直接报错。

## 卸载

```bash
systemctl disable --now mysql97
rm -f /etc/systemd/system/mysql97.service /etc/logrotate.d/mysql97 && systemctl daemon-reload
rm -rf /opt/mysql /etc/mysql97 /var/log/mysql97
rm -rf /var/lib/mysql97        # ⚠️ 数据；切主之后就不能这么删了
```

## 迁移前要解决的事（切主之前，一条都不能跳）

1. **排序规则映射不上。** 现网 175 张表是 `utf8mb4_uca1400_ai_ci`（MariaDB 专有的 UCA 14.0），
   另有 1 张 `utf8mb4_unicode_520_ci`。MySQL 没有 uca1400，最接近的是 `utf8mb4_0900_ai_ci`。
   唯一索引、`ORDER BY` 在边缘字符上会有差异 —— **先在副本上比对唯一索引冲突**。
2. **`my.cnf` 里标了【切主前调】的几项**：`performance_schema`、`innodb_buffer_pool_size`、
   `max_connections`，以及 **binlog 必须打开**（按时间点恢复靠它）。
3. **应用侧的库账号**要新建，认证插件是 `caching_sha2_password`（9.x 已去掉 `mysql_native_password`）；
   JDBC 串走 127.0.0.1 不带 TLS 时要 `allowPublicKeyRetrieval=true`。
4. **SQL 方言差异**：本仓库的 H2 测试挡不住 MariaDB 与 MySQL 之间的差异，
   迁移脚本要在 MySQL 9.7 上真跑一遍（现在有了这个实例，这件事第一次可以做了）。
5. **备份脚本**（`deploy/tencent/backup-to-cos.sh`）用的是 `mariadb-dump`、走默认连接（即 MariaDB）——
   切主之后它会继续备份一个已经不用的库，而且不报错。
