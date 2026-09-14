# MariaDB → MySQL 9.7 迁移工具

方案见 [运维-MariaDB切MySQL方案.md](../../../../docs/technical/design/运维-MariaDB切MySQL方案.md)。
本目录是把方案落成能跑的脚本,外加**分级种子**。所有脚本**只读现网 MariaDB、只写 MySQL 目标库**。

## 文件

| 文件 | 干什么 |
|---|---|
| `tiers.conf` | 种子分级清单:哪些表进「必要」、哪些进「测试」、哪些不灌 |
| `lib.sh` | 公共函数(排序规则归一、连接、对账),被下面三个引用 |
| `convert.sh` | **正式迁移**:三库结构+数据从 MariaDB 迁到 MySQL,同时构建索引,逐表对账 |
| `extract-seed.sh` | 从源库抽**分级种子**(required / test / accounts) |
| `build-fresh.sh` | 在 MySQL 上**从零建新环境**:空结构 + 按档灌种子 |

## 种子为什么分级

2026-09-14 盘满事故的根源之一:**测试商品 SK9901 混在生产库里**(种子直接写库,没有"生产不灌测试数据"这条线)。
分级就是把这条线画出来:

| 档 | 内容 | 谁要 |
|---|---|---|
| **必要 REQUIRED**（37 张表） | 系统配置/参考:菜单·角色·权限点·类目树·规格字典·标品库·支付渠道·通知模板·风控规则·计量单位·任务定义… | **所有环境,含生产** |
| **测试 TEST**（126 张表） | 演示业务:社区·商家·商品(含 SK9901)·订单·购物车·库存流水·用户… | **只测试/预发** |
| 运行时 SKIP（36 张表） | 日志·会话·发件箱·幂等·Flyway 历史·媒体登记… | **谁都不灌,新环境该为空** |
| 参考 REGION（sys_region 66万行） | 行政区划 | 由迁移 V31/V181 灌,不进种子文件 |
| 账号 ACCOUNTS（sys_ops_staff） | 运营账号,**含密码哈希** | 单独文件、当凭据、不入库 |

实测(2026-09-14):生产档拿到菜单 13 / 角色授权 423,而商品·订单·社区全 0、SK9901 不存在;测试档三者齐全。
**新表默认落 TEST** —— 安全:不会把新的业务数据误当"必要"灌进生产。要进必要档,得在 `tiers.conf` 里显式登记。

## 怎么用

### 场景一:正式把现网迁到 MySQL(方案 M4 的核心一步)

```bash
# 先演练:迁到 *_m2,不占正式库名,现网不受影响
SUFFIX=_m2 bash convert.sh
# 正式切换窗口内:迁到同名库(会先 DROP 目标同名库)
bash convert.sh
```
`convert.sh` 会:dump 结构→归一排序规则(uca1400/520/unicode_ci→0900,bin 不动)→建空表(索引结构落地)→
灌数据(InnoDB 同时构建二级/唯一索引)→逐表 `COUNT(*)` 对账。任何一步不干净都会停下报错。

### 场景二:起一个干净的测试/预发库

```bash
# 生产档:只系统配置,零演示数据
bash build-fresh.sh --level required --suffix _staging
# 测试档:系统配置 + 演示业务数据
bash build-fresh.sh --level test --suffix _staging
```

### 场景三:只想要分级种子文件(给别处用)

```bash
bash extract-seed.sh                 # → ./out/seed-required.sql、seed-test.sql
WITH_ACCOUNTS=1 bash extract-seed.sh # 额外导 seed-accounts.sql(含哈希,别提交)
```

## 连接从哪来(默认对准本机)

- 源 MariaDB:`sudo mariadb` / `sudo mariadb-dump`(默认 socket = 现网 3306)。**只读。**
- 目标 MySQL:`sudo /opt/mysql/current/bin/mysql --defaults-file=/etc/mysql97/my.cnf`(3307)。
- 换机器时用环境变量覆盖:`SRC_CLI` `SRC_DUMP` `TGT_CLI` `MY_DEFAULTS`(见 `lib.sh`)。

## 新环境怎么登进去

生产档(`--level required`)**不含账号**(账号在 ACCOUNTS 档,含密码哈希,不随种子灌)。建好后二选一:
1. 用应用的建号入口/接口创建管理员(推荐,密码由应用按 bcrypt 生成);
2. `WITH_ACCOUNTS=1 extract-seed.sh` 导出 `seed-accounts.sql`、灌进去,**然后立刻改口令**
   (现网 `admin` 是弱口令 `admin123`,别带进新环境)。

## 注意

- **正式 `convert.sh`(无 SUFFIX)会 DROP 目标同名库** —— 只在确认要覆盖时跑,演练一律带 `SUFFIX=_m2`。
- 种子里的 `USE \`db\`;` 决定灌进哪个库;`build-fresh.sh` 会把它改写到带后缀的库。
- 三库**零跨表外键**(2026-09-14 实测),灌数据不受引用顺序约束。正式迁移前仍 grep 一遍 `FOREIGN KEY` 复核。
- 唯一索引在 0900 下**零冲突**已用真数据验通(方案 M1);`convert.sh` 灌数据时若报 ER_DUP_ENTRY,说明切换前又攒了冲突数据,按方案 M1 处置。
