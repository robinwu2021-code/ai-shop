# TDD · 服务业报表

状态：首期（计数指标）已实现；营收待 D19 · 档位：1（美业插件实现设计清单里的 BAPI-119 子集；不建表）
关联：[B端门店功能矩阵-执行方案](./B端门店功能矩阵-执行方案-2026-11-09.md) D7 / W1.5；[服务业对照表](./B端服务业-App接口与插件接口对照.md) 15-15、缺口 6；美业需求 BTY-Q02、BTY-Q04（[多端功能矩阵](../requirements/美业系统-多端功能矩阵.md) Q 节）
创建：2026-11-09

## §0 需求

定案 D7：首期指标为预约数、到店数与到店率、爽约数、取消数、营收、按技师的次数与金额、热门项目前 5；范围为今天、近 7 天、本月。

| AC | 需求（Given / When / Then） |
|---|---|
| AC1 | Given 时间窗内有确认 3、到店 2、爽约 1、取消 1、占位 1 个预约，Then 预约数 6（不含占位）、到店 2、爽约 1、取消 1、到店率 = 2 ÷（6 − 1）= 40% |
| AC2 | 按技师统计到店预约里该技师被安排的项目次数 |
| AC3 | 热门项目：到店预约的项目按次数取前 5，并列按规格号排序 |
| AC4 | 时间窗由 App 按门店时区算好传入（`from`、`to`，左闭右开），与预约列表同一口径 |
| AC5 | 读权限与预约列表相同（`biz:booking:view` 或 `biz:booking:manage`） |
| AC6（**分期**） | 营收、按技师金额：等口径定案后再做，见 §4 D19 |

## §1 现状（2026-11-09 读代码）

- 美业插件没有报表接口。美业需求把经营报表放在 Q 模块：BTY-Q02「当日概览」为 P1，BTY-Q04「预约转化、爽约、技师利用率」为 P3。插件接口登记在 `docs/technical/design/schema/beauty-api-design.json`，由 `gen-beauty-api-design.py` 生成清单。**新增接口要走这份清单，由美业负责人维护。**
- 可用数据：
  - `bty_appointment`：`store_no`、`status`、`start_at`；
  - `bty_appointment_item`：`sku_no`、`qty`；
  - `bty_appointment_assignment`：`personnel_no`，需按 `bty_appointment_current.arrangement_revision` 取当前安排，写法与 `AppointmentServiceImpl#listAppointments` 的技师过滤相同。
- 预约状态：`HELD`（占位，未成单）、`PENDING`、`CONFIRMED`、`CHECKED_IN`、`NO_SHOW`、`CANCELLED`（见 `AppointmentPreparation`、`AppointmentCoordinator`）。
- **美业插件里没有金额**：收钱走宿主交易与收款域，插件只用 `bty_trade_binding` 关联来源单。

## §2 方案（建议）

### 契约

```
GET /biz/x/beauty/reports/scheduling?from=2026-11-09T00:00+08:00&to=2026-11-10T00:00+08:00
→ {from, to, appointments, arrivals, arrivalRateBp, noShows, cancellations,
   byPersonnel:[{personnelNo, services}], topServices:[{skuNo, services}]}
```

- 路径用设计清单里已规划的 **BAPI-119**（BTY-Q04「预约转化、爽约、资源利用率」），不另开新路径；清单条目状态改为「已实现首期子集」，总数仍为 160。资源利用率与实际服务时长仍为候选。
- 窗口最长 93 天，`from`、`to` 必填且 `to > from`，否则 10400。

| 指标 | 口径（按 `start_at` 落在窗口内） |
|---|---|
| 预约数 | `status <> 'HELD'` |
| 到店数 | `status = 'CHECKED_IN'`（预约状态只有 HELD / CONFIRMED / CHECKED_IN / NO_SHOW / CANCELLED；开工单后预约仍是 CHECKED_IN） |
| 到店率 | 到店数 ÷（预约数 − 取消数），以万分比整数返回；分母为 0 时返回 null |
| 爽约 / 取消 | `NO_SHOW` / `CANCELLED` |
| 按技师次数 | 到店预约中，当前安排（`arrangement_revision`）里 `personnel_no` 非空的项目数；同一项目多条安排（V7 起按容量单位拆行）只算一次 |
| 热门项目 | 到店预约的项目按 `sku_no` 计数，取前 5 |

### 模块（美业插件内）

| 动作 | 路径 |
|---|---|
| 新增 | `beauty-api`：`SchedulingReportService`、`dto/SchedulingReportQuery`、`dto/SchedulingReportVO` |
| 新增 | `beauty-core`：`SchedulingReportServiceImpl`（`AppointmentAccess#merchant(…, false)` 判权）、`BeautyMappers.AppointmentMapper` 三条聚合查询；在 `BeautyAppointmentConfiguration` 注册 |
| 新增 | `beauty-web`：`BizSchedulingReportController`（`/biz/x/beauty/reports/scheduling`） |
| 修改 | `beauty-api-design.json` 的 BAPI-119 条目，重跑 `gen-beauty-api-design.py` 等生成器 |
| 测试 | `beauty-core` `SchedulingReportIT`（原生 MySQL，库迁到 V9）；`beauty-web` `BeautyBookingRoutesTest` |

## §4 待决

| # | 问题 | 建议 |
|---|---|---|
| D7（产品确认中） | 指标与范围 | 先做 §2 的计数指标 |
| D19 | 营收按什么算：账单金额，还是实收（含储值、外部记账） | 建议用实收：按工单号读 [业务单收款汇总](./TDD-业务单收款汇总.md) 的已收，不在插件里另算钱。按技师金额需要先定分摊规则（美业需求 BTY-O 业绩模块，P3），首期不做 |
| D20 | 谁来实现 | 2026-11-09 用户确认由本会话实现首期 |
| D21（实施中发现） | 美业 V10 在 MySQL 9.7.4 上建不了表 | `ck_bsef_json_*` 用 `COALESCE(布尔表达式, 0)`，结果是整数，9.7.4 报 3812「CHECK 表达式不是布尔」；生产 9.7.2 接受。美业还没上 166，**升级 MySQL 或美业上线前**要把这几条改成 `(…) IS TRUE` 之类的布尔写法（改迁移会变校验和，需美业负责人定）。本次报表测试因此只迁到 V9 |

## §5 对账三 · 实现 → 需求（测试）

| AC | 测试 |
|---|---|
| AC1–AC3 | `SchedulingReportIT#countsFollowTheAgreedDefinitions`（占位、窗口外、他店不计；旧安排修订不计；同项目两条安排算一次；前 5 截断与并列排序） |
| AC1（分母为 0） | `SchedulingReportIT#emptyWindowHasNoRate` |
| AC4、AC5 | `SchedulingReportIT#windowAndPermissionAreChecked` |
| 路由 | `BeautyBookingRoutesTest`；宿主 `BeautyEmbeddedWiringTest`（embedded）、`BizEndpointPermTest` |
| AC6 | 未做（D19） |

集成测试命令（本机 MySQL，root 空口令）：
`mvn -o -Dmaven.repo.local=<独立仓库> -f backend-plugins/shop-industry-beauty/pom.xml -pl beauty-core test -Dtest=SchedulingReportIT -Dbeauty.test.admin-url='jdbc:mysql://127.0.0.1:3306/mysql?useSSL=false&allowPublicKeyRetrieval=true'`
