# 美业独立 Java 工程

> 状态：基础工程、P1数据层及政策/项目定义查询发布已实现 · 2026-10-21。完整营业能力仍在开发。

沿零售的 shop-parent、Java 21、MyBatis-Plus、主库事务及审计规范实现。
执行范围、原始方法签名和验收进度见[后端开发计划](../../docs/technical/TDD-美业系统-后端开发计划.md)。

| 构件 | 职责 | 当前实现 |
|---|---|---|
| beauty-api | DTO / Service 接口 | 政策及项目定义的请求、响应、Service与内部HTTP合同 |
| beauty-core | 领域实现、实体、Mapper | 首批16表迁移、结构校验、主库接线；政策/定义、操作日志及预约/工单任务、来源分配及提交意图16个实体与Mapper、政策与项目定义发布/历史查询事务 |
| beauty-web | 用户端 HTTP 入口 | 独立条件配置、api/ops profile隔离；政策和项目定义查询/发布 Controller |
| beauty-channel | 内部协议适配 | standalone 的政策/定义远程Service；商品事实反向适配 |
| beauty-server | 独立内部服务 | 可执行启动、内部政策/定义端点、服务令牌安全链、主库与PLATFORM outbox接线 |

只有 server 是可执行 JAR。web/channel 不依赖 core；商品、订单、资金、人员、排班、资源、库存及通知打印沿原所有者提供。
当前 readiness 固定 OUT_OF_SERVICE，等待预约/资源、来源/履约等后续权威合同和完整营业验收。

## 构建与宿主形态

先按平台流程准备已发布到本机/制品库的 shop-parent、shop-base、shop-store-mybatis、shop-base-auth、svc-client；POM不依赖零售源码目录解析父工程。同机多工作树共用 SNAPSHOT 仓库时，应先构建本分支公共构件，再构建行业包，避免旧构件覆盖。

```sh
mvn -o -f backend/pom.xml -pl shop-store-mybatis -am install
mvn -o -f backend-plugins/shop-industry-beauty/pom.xml verify
mvn -o -f backend-plugins/shop-industry-beauty/pom.xml install
mvn -o -f backend/pom.xml -pl shop-app -am -Pbeauty-embedded test -Dtest=BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -f backend/pom.xml -pl shop-app -am -Pbeauty-standalone test -Dtest=BeautyEmbeddedWiringTest -Dsurefire.failIfNoSpecifiedTests=false
```

宿主默认零售构建不带美业。embedded 引入 web/core；standalone 引入 web/channel，enforcer禁止本地 core/server。
运行时 `shop.beauty.enabled` 默认 false；启用后 `shop.beauty.deployment` 必须明确为 embedded 或 standalone，并与构件形态一致。
独立 beauty-server 只接受 standalone。关闭不需要数据库；启用后通过平台 PrimaryPersistenceConfiguration 装配主库。

独立服务参数：`BEAUTY_SERVER_PORT`（默认8087）、`BEAUTY_DB_URL`、`BEAUTY_DB_USER`、`BEAUTY_DB_PASSWORD`、`SHOP_INTERNAL_TOKEN`。
数据库与令牌无内置凭据。服务令牌采用既有 InternalHttp 头协议；业务委托和受权范围仍需正式入口接入。

## 数据所有权与迁移

V1仅创建16项P1美业私有表，不包含14项共用人员/资源结构、P3护理业绩提成或P4疗程计划。
业务唯一键带 tenant/entity 前缀；源行分配 guard 身份不含 source_revision；数量守恒、时间区间由数据库 CHECK 约束保护。

`shop.beauty.migration.mode` 默认 validate，仅校验；migrate 仅供部署时单一受控执行者显式使用。
行业历史表为 bty_flyway_history，Flyway对象不注册为全局Bean，不接管零售的迁移位置及历史。
独立 server 的平台全量 Flyway 默认关闭；宿主的原平台 Flyway保持独立生效。
发现无历史的既有 bty 表、未知表、历史/字段/默认值/索引/检查约束漂移时拒绝启动；不会隐式 baseline 既有美业数据或 repair。

V1首次生成后冻结；后续新增版本，不回写已执行文件。P3/P4按其正式合同再追加迁移。

## 验证

```sh
node --test scripts/tests/beauty-schema-sql.test.mjs
node scripts/gen-beauty-schema.mjs --check
node scripts/verify-beauty-schema-mysql.mjs
node scripts/verify-beauty-persistence.mjs --include-server --include-host
node scripts/smoke-beauty-server.mjs
```

MySQL脚本只创建和删除自己命名的一次性容器，使用已缓存的 mysql:8.4，不拉镜像、不读取营业库配置。
DDL验证无网络；JDBC测试仅映射本机随机端口，每例独立数据库。IT由脚本明确选择，普通 verify 不冒充已执行真实数据库测试。
JAR smoke明确关闭美业、清空数据源 URL并监听本机随机端口，检查 liveness=200、readiness=503后结束自己启动的进程。

## 首个业务切片与三行业共用边界

`POST /biz/x/beauty/store-policies/versions`（BAPI-002）按既有 Controller/Service 方法设计实现；
新增可选原始 `X-Store-No` 参数用于拒绝过滤器默认店回落掩盖的非法请求，Service 参数保持原设计。
登录员工、主体和当前门店来自可信上下文，权限沿用 `biz:store`，不接受请求体伪造 actor。
请求包含 `expectedRevision`、`validFrom` 和 typed `policy`；P1 仅允许 `PREPAID_SINGLE`。
首次创建要求 revision=0，后续按当前正数 revision 发布；追加不可变版本、CAS 更新根、操作日志、幂等结果与 PLATFORM outbox 同一主库事务。
同 key/同规范输入返回原响应，异参或过期版本冲突；时间归一 UTC 毫秒。

零售拥有的 auth、IdempotencyService、PLATFORM outbox 和主库事务继续作为核心：
公共包新增 StoreRequestScopeValidator、IdempotencyScope、PlatformOutboxPublisher、PlatformWritePrerequisites，
餐饮和美业接入；零售既有默认门店回落与无 key 的旧接口行为保持兼容，并有回归用例。
收尾已追平餐饮最新已提交 c634de551（本分支74fdaf218），包括实际政策读取入口。
元数据前置检查复用事务连接，单连接池也能发布；不执行公共表 DDL。

本批新增 `GET /biz/x/beauty/store-policies`、`GET /biz/x/beauty/service-definitions`、
`POST /biz/x/beauty/service-definitions/versions`。两种 Service 同时提供 embedded 与 standalone 实现。
政策按 `effective_at` 选有效版本，不把未来版本提前生效；响应分别给出根revision和有效版本revision。
定义使用现有审计 `created_at` 作历史查询时点，沿平台JVM本地审计约定换算；部署各实例须使用同一稳定时区，
返回publishedAt转换为UTC。定义查询page默认1、size默认20且最大100；子要求只能通过当前范围内父版本读取。

定义的 `duration` 包含 minutes/bufferBeforeMin/bufferAfterMin；requirements包含角色、资源类型、技能项目、数量和占用区间。
P1仅单步骤SERVICE（sequenceNo=0）、ANY_QUALIFIED规则，资源类型STAFF/ROOM/SEAT/DEVICE；实际人员匹配在后续资源合同验收。
基础时长校验 `prd_goods.duration_min`，共享商品域ServiceProductFactsPort负责MAIN/CN、本店、SERVICE类型及在架报价事实。
美业不保存售价；duration与商品事实不符时拒绝。商品外部查询在主事务外执行，禁止从已开启事务调用定义发布。

不可变政策/定义发布事件本批采用明确归档合同：版本表和action_log为权威，outbox事件留作发布凭据，
不承诺通知、支付或资源副作用。部署需显式设置
`spring.config.import=classpath:beauty-publication-archive.yml`（可合并到已有导入列表）；
此文件精确登记两个事件并将platform源设为UNIFIED，不默认开启，也不覆盖其他消费者合同。
已有实际消费者的部署应自行配置正式路由，不能同时归档同一事件。缺路由仍拒绝发布、整体回滚。
独立server本批只装配共享PLATFORM存储用于归档；需要实际投递消费者时须先接入交付引擎并验收。

standalone 宿主需要配置 `shop.services.targets.BEAUTY`；beauty-server 反向查商品需配置
`shop.services.targets.PLATFORM`，两者使用原 `shop.services.internal-token`。
令牌只授予受信平台内部委托资格；公开用户不得直接提交entity/store/actor。
内部scope字段非空有长度约束，Service再次校验query门店和SQL范围；操作人适配原审计后finally恢复线程身份。
readiness 继续为503，直到业务提供方、正式投递和后续合同完成验收。

ENG-03 在政策/定义切片通过；DATA-04 的定义版本发布和父链隔离已实现。公开营业信息与完整商品目录、预约/工单等仍待后续切片。
DATA-05/06的数据层已实现：预约及项目/安排历史、工单、任务、步骤、实际参与段7实体，15条既定自定义Mapper方法；
没有新增公开接口。改约/签到、任务启停/数量、绑定版本、冻结/关闭均做本地范围及CAS检查，
返回0须由后续业务Service转为冲突。安排revision、工单scopeRevision、任务bindingRevision与BaseEntity.version分别处理。
未来业务事务必须按工单根→排序任务→子行加锁；当前trade_binding读取仅校验本地引用完整性，不能代表来源授权或资源确认。
DATA-07持久化已实现：提交意图、来源绑定、规范来源行guard三个实体与9条原始Mapper方法（操作日志复用已有实现）。
累计16项P1实体和数据域。来源修订不另开guard；分配/刷新/释放显式CAS和数量精度校验；恢复扫描有界SKIP LOCKED，
必须同短事务认领租约后提交，外部查询不持锁。跨action相同请求摘要歧义拒绝，未来Service规范摘要须包含action。
释放采用整guard保守校验，任一历史分配已消费、在途或父链不完整时拒绝；即使其他部分空闲也不局部释放。
涉及来源的业务事务按排序guard→工单→任务→绑定/提交加锁；恢复scanner的租约事务独立提交。
这些本地方法不代表外部取消/消费证明；完整恢复Service、来源权限校验、预约工作台和共用人员/资源合同仍待后续。完整49任务尚未完成。

## 共用人员 SHR-01a 基础切片

共用人员五表及内部批量事实查询由 `shop-merchant` 实现，正式平台迁移为V532；不加入美业私有迁移。
`shop-base/spi/personnel/PersonnelFactsPort` 读取明确名单在指定门店/时点的有效任职、业务岗位和已核验资格。
允许无登录账号，账号/权限角色不作为岗位或技能；输出不含账号、电话和联系资料。
同一人员的重叠任职及超限数据明确拒绝；数据库规范人员/任职号用于投影分组，三次查询来自同一只读快照。
SHR-01/02整体仍未完成：人员维护写业务、排班、规范资源/组合占用和美业standalone远程接入后续实施，不能据事实查询宣称可预约或开工。
实施与验收见[开发计划§11](../../docs/technical/TDD-美业系统-后端开发计划.md)。


## 共用资源 SHR-02a 基础切片

规范资源主档由shop-merchant拥有，平台V533新增mch_resource，未加入美业私有迁移。
STAFF同主体同人员唯一、capacity=1且不归某个门店，通过有效任职验证查询门店；物理ROOM/SEAT/DEVICE必须属于有效门店。
shop-base/spi/resource/ResourceFactsPort提供明确资源名单的单SQL批量事实，拒绝重叠任职和超限；不输出账号、联系资料或占用凭证。
openOccupied只表示主档已有开放阻断指针；事实查询不提供班表、剩余容量或整个服务时段的预约许可。
人员/资源维护、排班、组合占用、旧slot切换与美业独立服务远程消费仍待后续，完整SHR-02未完成。
实施与验收见[开发计划§12](../../docs/technical/TDD-美业系统-后端开发计划.md)。


## 共用周排班 SHR-01b 基础切片

shop-merchant拥有mch_schedule_rule/change_log，平台V534新增，未进入美业私有迁移。
shop-base/spi/schedule/ScheduleWindowsPort在明确资源名单、门店及最长31天范围内返回周排班窗口。
规则按本地ISO星期与开始日展开，支持跨午夜、午夜1440分钟及区域/固定偏移时区；窗口按请求和有效任职裁剪。
同店同任职合并并保留规则来源，邻接任职分段；跨店同资源重叠或同店任职重叠明确拒绝。
DST缺失/重复端点影响请求时拒绝，不猜测偏移；数据库规范门店号用于筛选，支持CI引用且不输出其他店规则。
本批周窗口不包含P3例外审批或容量/资格许可；变更历史数据不代表已处理已有预约影响。
维护HTTP/写Service、完整影响协调、容量桶/组合占用、远程接入与预约日历后续实施。验收见[开发计划§13](../../docs/technical/TDD-美业系统-后端开发计划.md)。
