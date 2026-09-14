# MariaDB → MySQL 切换执行手册（M4）

> **别在没拿到"切"的决定之前跑这份。** 方案与验证见
> [运维-MariaDB切MySQL方案.md](../../../../docs/technical/design/运维-MariaDB切MySQL方案.md)。
> 这份是把那份方案的 M4 落成一条按顺序、每步能证伪、随时可回滚的清单。
>
> **回滚基石**：全程 **MariaDB 不停、不改**，继续在 3306 跑。任何一步不对，把三个数据源指回 3306、重启即回到迁移前。
> 唯一不可逆的是「切换后写进 MySQL 的新数据」——所以 T3 的停机窗口内**不放业务流量**。

预计停机 **10~15 分钟**（三库 ~323MB，导+灌+建索引分钟级 + 对账 + 冒烟）。

---

## T-1 · 切换前（不停机，提前做完并逐条打勾）

- [ ] **决定已拍板**：切、时间窗口、观察期多久、这期间是否只读（见方案 L4 待决）。
- [ ] **PAD/尾随空格的目标排序规则已定**（方案 §M1补）：`0900_ai_ci`+输入端 trim，还是 `unicode_520_ci`。
      定的值写进 `lib.sh` 的 `TARGET_COLL`。**不定就别切**——这决定判等语义。
- [ ] **MySQL my.cnf 调生产档**（`mysql97/README` 标了【切主前调】的项）：`performance_schema=ON`、
      `innodb_buffer_pool_size` 给到内存一半、`max_connections ≥ 应用池上限×实例数+余量`、**`log-bin` 打开**。改完重启待机实例，确认还能连、库还在。
- [ ] **binlog 磁盘预算**：开 binlog 后与[日志方案](../../../../docs/technical/design/运维-目录与日志方案.md)一起算，别再写满盘。
- [ ] **建应用账号**（MySQL 上，`caching_sha2_password`）：用户名/密码要和将来 env 里填的一致；
      `GRANT` 到三个库；密码走仓库外 env，**不写进本文、不用 root 连**。
      验：`mysql -h127.0.0.1 -P3307 -u<应用用户> -p<...> -e "SELECT 1"` 通。
- [ ] **env 草稿备好**（先不生效）：把 `SPRING_DATASOURCE_URL`、`SHOP_INVENTORY_DATASOURCE_URL`、
      `SHOP_JOB_DATASOURCE_URL` 及各自 user/password 指向 3307 的三个库，串带 `allowPublicKeyRetrieval=true`。
- [ ] **预演一次**：`SUFFIX=_pre bash convert.sh` 迁到 `*_pre`，跑通、对账过，再 DROP 掉。确认工具在当前数据上没问题。

---

## T0 · 停业务

- [ ] 公告 / 挂维护页（有真实用户时）。
- [ ] `sudo systemctl stop ai-shop ai-shop-pay ai-shop-job` —— 三个都停，**这一刻起 MariaDB 不再有新写入**。
- [ ] 确认没有别的写入方还连着 MariaDB：`sudo mariadb -N -B -e "SELECT COUNT(*) FROM information_schema.processlist WHERE db LIKE 'ai_shop%' AND command<>'Sleep'"` → 期望很小/0。

## T1 · 迁数据（MariaDB 只读 → MySQL）

- [ ] `bash convert.sh`（**无 SUFFIX = 正式库名**；会 DROP 目标同名库——此刻 MySQL 上若有旧的预演残留正好被清）。
      它做：导结构→归一排序规则→建空表→灌数据→**建索引**→逐表 `COUNT(*)` 对账。
- [ ] 看到 **`全部完成:结构+数据+索引已迁移,逐表对账通过`** 才继续；有 `ERROR 1062`（撞唯一键）就停下按方案 M1 处置。

## T2 · Flyway 基线（一般不用手动，留作兜底）

- [ ] 三库的 Flyway 历史随 dump 搬过去了，应用启动时应"无需迁移"。**若启动报校验错**，再手动 baseline：
      `ai_shop`→V327、`ai_shop_inv`(`inv_flyway_history`)→V6、`ai_shop_job`(`job_flyway_history`)→V2。

## T3 · 切 env、起服务

- [ ] 把 T-1 备好的三个数据源 env 写进 `/data/app/ai-shop/shop-app/shop-app.env`（先备份原文件：`cp shop-app.env env-backup/shop-app.env.bak-cutover-$(date +%m%d-%H%M)`）。
- [ ] `sudo systemctl start ai-shop`，守到 health 200：`for i in $(seq 30); do curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/actuator/health; sleep 3; done`。
- [ ] 起另外两个：`sudo systemctl start ai-shop-pay ai-shop-job`。

## T4 · 冒烟（单实例，别被并发实例的假 401 带偏 —— 见 [[second-app-instance-false-401]]）

- [ ] `curl -s http://127.0.0.1:8081/actuator/info | grep gitSha` 对得上刚发的版本。
- [ ] Flyway 日志：`journalctl -u ai-shop --since -5min | grep -i flyway` 出现"is up to date / No migration necessary"。
- [ ] 关键读：`curl -s "http://127.0.0.1:8081/mp/community/nearby" | head -c 120` 返回 `{"code":0,...}`（**单实例才准**）。
- [ ] 关键写：让**运营账号**的人登一次运营端、做一个读写动作（我没有账号，这步要人点）。
- [ ] 两个投递任务在跑：`sudo mariadb -N -B ai_shop_job -e "SELECT job_name,last_status FROM job_run WHERE job_name LIKE '%outbox%'"`
      —— ⚠️ 注意这查的是 MariaDB 的 job_run；切后要查 **MySQL** 的：改用 mysql97 客户端。

## T5 · 放流量

- [ ] 撤维护页。观察 15 分钟：`journalctl -u ai-shop -p err --since -15min` 应无新 ERROR；`logwatch` 的 outbox 死信不涨。

---

## 回滚（切后发现问题，观察期内）

1. `sudo systemctl stop ai-shop ai-shop-pay ai-shop-job`
2. 恢复 env：`cp env-backup/shop-app.env.bak-cutover-<stamp> shop-app.env`（三个数据源指回 3306）
3. `sudo systemctl start ai-shop ai-shop-pay ai-shop-job`，守到 health 200。
4. **代价**：切换后写进 MySQL 的新数据会丢——所以观察期内要么只读、要么想好怎么把增量补回 MariaDB。

## 收尾（观察期过、确认稳）—— M5

- [ ] 备份脚本 `backup-to-cos.sh` 改 `mysqldump` + 3307（否则继续备份没人用的 MariaDB、还不报错）。
- [ ] `sudo systemctl disable --now mariadb`（**先停不卸**，留一段退路）。
- [ ] 文档：部署 README 拓扑、`mysql97/README` 从「待机」改「主库」、方案状态改「已实施」。
