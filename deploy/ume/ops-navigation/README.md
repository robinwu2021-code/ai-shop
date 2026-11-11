# 166 运营导航 V657 发布

范围：ai-shop 一二级导航、短名与英文、真实角色配置矩阵；独立 near-pay 导航和生效角色映射矩阵。线上支付端仍保持原七页白名单。

V657 只变更菜单名称、分组、顺序和归属。实库 V590/V591 的支付菜单码是 `OPS_PAY_01..08`，前端冻结表登记的是 `OPS_FINANCE__PAY_*`；迁移同时识别两组既有编码，不修改任何角色授权、权限码或门禁，不创建第二套权限记录。

## 验证和制品

MySQL 9.7.4 隔离库从166只读复制菜单、角色和Flyway历史。使用运行环境相同的Flyway11.14.1将656迁移到657，152项全部匹配；角色、582条角色授权、6条成员关系及283个功能点的身份/权限/门禁保持。重复执行0项。结果见 `evidence/rehearsal-20261111.json`。

生产模式前端：ai-shop `NEXT_PUBLIC_USE_MOCK=0 NEXT_PUBLIC_API_BASE=/ops-shop NEXT_PUBLIC_BASE_PATH=/ops-shop`；支付端 `NEXT_PUBLIC_DATA_SOURCE=http NEXT_PUBLIC_API_BASE_URL=/oms-api/pay-ops/v2 NEXT_PUBLIC_OPS_EDITION=live NEXT_PUBLIC_BASE_PATH=/oms`。双域名共用各自的同一静态目录。

后端没有Java代码变更。发布从166当前实际运行的JAR复制，仅增加 `BOOT-INF/classes/db/migration/V657__ops_compact_navigation.sql`，逐个校验原归档条目字节不变。基础JAR必须与manifest的SHA256一致；不以工作区的旧JAR替换其他会话已发布的功能。

## 顺序

`release.py <phase> <payload-dir>` 在166以root执行。payload包含公开的manifest、V657.sql和两个静态归档，不包含凭据。

1. `prepare`：取得部署锁，核对容器/运行JAR和制品哈希，备份原JAR/容器配置/静态链接；构造仅增加迁移资源的新JAR并保留旧静态资源。
2. 经受控连接用Flyway执行唯一V657，核对生产数据库的角色/授权/权限/门禁和历史未变。成功证据放入payload的 `production-result.json`。此步骤不repair历史、不手工写Flyway记录。
3. `backend`：停止两个宿主容器，将新JAR写入原文件inode后启动并守候健康；不更改容器配置、挂载或CORS。失败恢复旧JAR并尝试拉起两个服务。
4. `frontend`：原子切换两端静态链接。公开目录显式设置0755/0644，私有备份保持0700/0600。
5. 完成公网、真实登录与菜单/权限回读，再 `finish`：核对健康、原容器配置和独立支付/库存服务未变，释放锁。

需要回退时执行 `rollback`：恢复两个静态链接和旧JAR；成功的V657记录保留，不回滚业务数据或删除Flyway历史。相对链接和链式链接均可恢复。失败时保留锁及私有备份供修复，不打印敏感配置。

部署脚本的独立审查修复了链式链接识别和umask导致403两个问题。四个本地回退/故障模型验证：`python3 -m unittest discover -s deploy/ume/ops-navigation -p 'test_*.py'`。这些是模型验证，不代表已在166制造故障演练。

## 本次结果

2026-11-11已部署166并验收。公开证据位于 `evidence/`，其中 production 为实际迁移后核对，public 为46项双域名检查，operator 为真实登录及接口回读，retained-assets 为旧页面资源保留检查。私有数据库快照、凭据、容器inspect、登录令牌未入库。源代码与发布记录分别提交后推送，不把验收记录误认为额外业务变更。

## V658 隔离库演练（2026-11-11）

V658 只调整菜单名称、分组、顺序与归属，并撤掉售后治理下重复的「退款分账」点（先把授权并到经营财务同名点）。本机 MySQL 9.7.4 从 V1 迁移到 V657 的隔离库上，以 Flyway 11.14.1 执行 V658：功能点只少那一条，其余权限/门禁不变；151 个菜单叶子与前端一致（含 OPS_PAY_01..08）；每个角色可见菜单与权限码集合不变；合成自建角色的授权完整并入；重复执行无变化。结果见 `evidence/rehearsal-v658-20261111.json`。

该演练库不是 166 实库副本；上 166 前仍按上文顺序先用只读副本复核授权快照。

## V658 发布（2026-11-11 已部署 166）

经 UME 跳板机发布，`release.py` 按 manifest 登记的唯一迁移（V658）执行 prepare → 迁移 → backend → frontend → finish。基础 JAR 为 V657 发布后的 `505b0451…`，4 个容器 ID 与 V657 一致；新 JAR `6770b628…` 只多 V658 一个条目。

- 迁移前在 .61 用 root 导出 6 张菜单/授权/Flyway 表（`--set-gtid-purged=OFF`，root 私有目录）。
- 166 主机 Java 为 1.8，`MenuSnapshot`/`MenuMigration` 在 ai-shop-ops 同一镜像（Java 21）的一次性容器里运行，依赖取自运行 JAR；连库配置由 `ai-shop.env` 就地生成、用后删除。
- 生产核对：Flyway 仅新增 658（checksum -783404707）；只删售后重复点及其 2 条授权（FINANCE/SUPER_ADMIN 原已持有财务同名点）；其余 282 点权限/门禁不变；151 叶子与前端一致；15 角色、6 成员、每个角色可见菜单与权限码不变；3 个运营自建角色未受影响。
- 后端两服务 UP，应用启动 Flyway 校验通过；两端静态链接切换；`verify-public.py` 46 项通过，新旧两个域名均返回新构建。
- 未做：真实账号登录后的菜单/接口回读（需部署工作站的 Ed25519 测试票据）。

证据：`evidence/deployment-v658-20261111.json`、`production-v658-20261111.json`、`public-v658-20261111.json`。
回退：`release.py rollback <payload>`（恢复旧 JAR 与两端静态链接；V658 记录与菜单数据保留，需要时用 .61 上的表备份恢复）。
