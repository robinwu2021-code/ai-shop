// Java 实体与库表的字段对齐。
//
// ═══ 这是守卫体系的盲区 ═══
//
// 已有的守卫覆盖了四条边：
//   契约 ↔ 库      gen-model-align
//   契约 ↔ 前端    wire-alignment
//   库   ↔ 血缘    schema-lineage
//   库   ↔ 单测库  JS/Python 双解析器
//
// **唯独没有「实体 ↔ 库」**。于是迁移里加的每一列，实体不跟也不会有任何症状 ——
// MyBatis-Plus 不会因为实体少一个字段而报错，它只是**读不出那一列**。
// 发现方式通常是：有人写了查询，拿到 null，以为是数据问题。
//
// 真实规模：这条守卫第一次跑起来时，`stl_bill` 的实体落后库 8 列，
// 另有 5 张表**根本没有实体**（共 73 列）。全部是 V20 之后加的。
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error -- 生成器是 .mjs，没有类型声明；它是 DDL 解析的唯一真源
import { readSchema, AUDIT, MIGRATION_DIR, INVENTORY_MIGRATION_DIR } from "../../../scripts/lib/ddl.mjs";

const ROOT = join(import.meta.dirname, "../../..");
const BACKEND = join(ROOT, "backend");

/** BaseEntity 已经提供的字段，实体自己不再声明 */
const BASE_FIELDS = new Set([
  "id", "tenantNo", "createdAt", "createdBy", "updatedAt", "updatedBy", "version", "deleted",
]);

const camel = (s: string) =>
  s.split("_").map((p, i) => (i ? p[0]!.toUpperCase() + p.slice(1) : p)).join("");

/**
 * 还没有实体的表，**必须登记在这里并写清为什么**。
 *
 * 登记不是豁免，是「知道欠着」。补上实体之后要从这里删掉 ——
 * 删不掉说明还没做完。
 */
const NO_ENTITY_YET: Record<string, string> = {
  mkt_counter_campaign_fence: "共享 marketing owner 协调行由 CounterCampaignFenceMapper 显式 exact-PK SQL 维护，SELECT revision 返回 Long；不映射 ORM 实体，不是 beauty 表或业务账。",
  sys_outbox_stream: "平台事件流序号由 JdbcOutboxStore 固定 Source.PLATFORM 的 JdbcTemplate 加锁分配，按 stream_key 查询 next_sequence；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  sys_outbox_delivery: "平台投递状态由 JdbcOutboxStore 固定 Source.PLATFORM 的 JdbcTemplate 追加、认领和回写，按投递 id 或 event_no/handler_id 查询；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  sys_outbox_operation: "平台重放/跳过审计由 JdbcOutboxStore 固定 Source.PLATFORM 的 JdbcTemplate 追加，按 request_id 查询幂等操作；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  prd_stock_sync_operation: "库存同步幂等操作由 StockReconcileService 的 JdbcTemplate 维护，按 operation_no 读取结果并按门店查询历史，映射 OperationResult/OperationRecord 与 JSON 结果；已有 JDBC/record 模型，无独立 ORM 实体。",
  prd_stock_alignment_batch: "库存初始对齐批次由 StockAlignmentService 的 JdbcTemplate 维护，按 batch_no/entity_no/store_no 查询 Batch 并生成 BatchView；已有 JDBC/record 工作流模型，无独立 ORM 实体。",
  prd_stock_alignment_line: "库存初始对齐明细由 StockAlignmentService 的 JdbcTemplate 追加和回写，按 batch_no/line_no 查询 StoredLine 并生成 LineView；已有 JDBC/record 快照模型，无独立 ORM 实体。",
  ord_refund_coupon_scope: "退款券分摊冻结范围由 RefundObligationMapper 的持久 SQL 写入并按 order_no 查询，RefundCouponScopeServiceImpl/RefundObligationServiceImpl 读写 Scope record 的 JSON 快照；采用 SQL mapper/record 模型，无独立 ORM 实体。",
  ord_refund_obligation: "退款券/库存义务由 RefundObligationMapper 的持久 SQL 创建、锁定和回写，按 execution_no 查询 Map 行并由退款服务生成 Progress record；采用 SQL mapper/record 模型，无独立 ORM 实体。",
  inv_outbox_stream: "库存事件流序号由 JdbcOutboxStore 固定 Source.INVENTORY 的 JdbcTemplate 加锁分配，按 stream_key 查询 next_sequence；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  inv_outbox_delivery: "库存投递状态由 JdbcOutboxStore 固定 Source.INVENTORY 的 JdbcTemplate 追加、认领和回写，按投递 id 或 event_no/handler_id 查询；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  inv_outbox_operation: "库存重放/跳过审计由 JdbcOutboxStore 固定 Source.INVENTORY 的 JdbcTemplate 追加，按 request_id 查询幂等操作；采用 source-bound JDBC 适配器，无独立 ORM 实体。",
  inv_event_consumed: "库存消费幂等收件记录由 TransactionalOutboxHandler 的 JdbcTemplate 与消费效果同事务插入，按 event_no/handler 唯一键冲突识别已消费；OutboxConfiguration 为 INVENTORY 选择该收件表和库存事务，无独立 ORM 实体。",
  pts_statement_final_fact: "可信终局发生时间与首次就绪序列投影，由PointsConfigurationMapper维护，按积分SQL mapper设计无ORM实体。",
  pts_statement_build: "大账单构建状态及高水位由PointsConfigurationMapper与MybatisStatementBuildStore与不可变record维护，按积分SQL mapper设计无ORM实体。",
  pts_statement_source_history: "大账单来源版本的只增历史由PointsConfigurationMapper与MybatisStatementBuildStore维护，用于分页重建旧修订，不设独立ORM实体。",
  pts_statement_build_item: "大账单固定来源分页明细由PointsConfigurationMapper与MybatisStatementBuildStore维护，与build同事务，按积分SQL mapper设计无ORM实体。",
  pts_statement_confirmation: "每修订通知与确认期限由StatementConfirmationMapper维护，按积分SQL mapper设计无ORM实体。",
  pts_user_adjustment: "双人调账原申请及审批版本由PointsAdjustmentMapper维护，账务经统一posting，按积分SQL mapper设计无ORM实体。",
  pts_availability_projection: "可重建的版本水位派生投影，由PointsAvailabilityMapper与AcceptanceAvailabilityProjection读写不可变快照，不是独立业务聚合，按现有积分SQL mapper方案不设ORM实体。",
  prd_category_points: "旧版类目积分规则表；生产只读审计为空，新积分方案不再读取，由 points integration policy 按门店×场景控制；不迁移、不删除历史表。",
  ord_points_refund_execution: "SQL mapper 由 RefundExecutionMapper/RefundLedgerMapper 直接访问，事务与恢复队列共用投影，无独立 ORM 实体。",
  lgs_push_event: "物流推送审计由 PushIngestion 通过 JdbcTemplate 追加与回写，按事件号定点读取，不使用 ORM 实体。",
  lgs_precheck_cache: "渠道预检缓存由 LogisticsPortImpl 通过 JdbcTemplate 读写，按运单身份定点读取，不使用 ORM 实体。",
  lgs_update_log: "物流更新审计由 WaybillUpdateAudit 通过 JdbcTemplate 追加并按运单查询，不使用 ORM 实体。",
  pts_point_type: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_fund_pool: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_account: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_voucher: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_ledger_entry: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_pool_flow: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_fund_evidence: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_refund_reserve: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_grant_position: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsOperationsMapper 维护，无独立 ORM 实体。",
  pts_redeem_position: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_refund_preparation: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_payout_ticket: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_store_statement: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_fund_transfer: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_grant_line: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_redeem_line: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_merchant_agreement: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper 维护，无独立 ORM 实体。",
  pts_statement_revision: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_statement_line: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_receivable: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_receivable_allocation: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_refund_preparation_line: "预留关系投影表，当前业务权威记录由 PointsPositionMapper/PointsRecordMapper 的不可变 JSON 快照维护；此表不作为生产写入或查询入口。",
  pts_pool_request: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper 维护，无独立 ORM 实体。",
  pts_topup_plan: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper 维护，无独立 ORM 实体。",
  pts_switch: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsOperationsMapper 维护，无独立 ORM 实体。",
  pts_switch_log: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper 维护，无独立 ORM 实体。",
  pts_pause_interval: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper 维护，无独立 ORM 实体。",
  pts_lifecycle_fact: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper 维护，无独立 ORM 实体。",
  pts_inbox: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsConfigurationMapper, PointsEventMapper 维护，无独立 ORM 实体。",
  pts_outbox: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsEventMapper, PointsOperationsMapper, PointsPostingMapper 维护，无独立 ORM 实体。",
  pts_stat_daily: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsOperationsMapper 维护，无独立 ORM 实体。",
  pts_cursor: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsEventMapper 维护，无独立 ORM 实体。",
  stl_split_intent: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 SplitIntentMapper 维护，无独立 ORM 实体。",
  ord_points_integration_policy: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsBindingMapper 维护，无独立 ORM 实体。",
  ord_points_command: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsBindingMapper 维护，无独立 ORM 实体。",
  ord_points_binding: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsFactMapper, OrderPointsBindingMapper 维护，无独立 ORM 实体。",
  ord_points_source_counter: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsFactMapper 维护，无独立 ORM 实体。",
  ord_points_source_fact: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsFactMapper 维护，无独立 ORM 实体。",
  ord_points_placement: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsPlacementMapper 维护，无独立 ORM 实体。",
  ord_points_refund_binding: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 RefundExecutionMapper, OrderPointsRefundBindingMapper 维护，无独立 ORM 实体。",
  ord_points_recovery_cursor: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsRecoveryCursorMapper 维护，无独立 ORM 实体。",
  stl_points_refund_hold: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 PointsSettlementRefundHoldMapper 维护，无独立 ORM 实体。",
  ord_points_refund_retry: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsRefundRetryMapper 维护，无独立 ORM 实体。",
  ord_points_work_cursor: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 OrderPointsWorkCursorMapper 维护，无独立 ORM 实体。",
  stl_cash_refund_reverse: "持久 SQL mapper 与不可变 record/JSON 快照模型，由 CashRefundSplitMapper 维护，无独立 ORM 实体。",
  // 新表如果一时来不及配实体，登记在这里并写清何时补，别让守卫红着过夜。
  // （ful_batch 已在「按业务模块整理实体」时补齐 FulBatch。）
  /*
   * 裂变邀请台账。**这是真欠账，而且欠的不是一个实体，是整条链路。**
   *
   * <p>V121 建了表，`FissionService` 的注释也写着「发奖与新客判定挂在台账
   * mkt_fission_invite 上，由 C 端注册/首单链路触发」—— 而**没有任何代码碰过它**。
   *
   * <p>连带的后果在运营端看得见：`MktFissionCampaign.invitedCount /
   * convertedCount` 的注释说自己是「台账的聚合快照」，实际只在
   * `FissionServiceImpl` 新建活动时 `set(0)`，**再没有一处递增**。
   * 而 ops-web 的「邀请有礼」列表就渲染这两列 —— 运营看到的两个 0
   * 不是「还没人参加」，是永远不会变。
   *
   * <p>补实体之前要先定「谁写台账」：C 端注册链路还是首单链路，
   * 以及幂等键 uk_fission_invitee 怎么用。那是功能，不是补一个类。
   */
};

// shedlock 进这里而不是 NO_ENTITY_YET：**它不是欠账，是框架自管表**。
// 表结构由 ShedLock 硬编码（列名不能改），读写全在 JdbcTemplateLockProvider 内部，
// 配一个实体反而会让人以为可以用它读锁状态 —— 而那样读到的是不带锁语义的快照。
/*
 * 会话与登录日志六张进这里而不是 NO_ENTITY_YET：**它们不是欠账，是有意不配实体**。
 *
 * <p>读写全在 `shop-auth-store` 的 SessionDao / LoginLogDao 里，用 `JdbcClient`
 * 原生 SQL（按 token_hash 单点查、按 user_no 取登录史、GC 的 DELETE）。
 * 配一个 MyBatis 实体的害处是实在的：数据域是 MyBatis-Plus 的拦截器，
 * 有了实体这些表就会被它看见 —— 而它们恰恰不该被按业务归属过滤
 * （撤销轮询与失败登录流按用途就是跨用户的）。
 * 同 shedlock 那条：配实体反而会让人以为可以那样用它。
 */
const IGNORE = new RegExp("^(flyway_schema_history|sys_idempotent|sys_outbox|shedlock"
  + "|(usr|mch|ops)_(session|login_log))$");

/** 返回既无 ORM 实体、也无精确非 ORM 登记的业务表。 */
function unregisteredBusinessTables(
  schemaTables: Iterable<string>,
  entities: ReadonlyMap<string, unknown>,
  registrations: Readonly<Record<string, string>>,
): string[] {
  return [...schemaTables]
    .filter((t) => !IGNORE.test(t) && !entities.has(t) && !Object.hasOwn(registrations, t));
}

/** 收集 backend 下所有 `@TableName("xxx")` 的实体，键是表名 */
function scanEntities(dir: string, out = new Map<string, { file: string; fields: Set<string> }>()) {
  for (const e of readdirSync(dir)) {
    if (e === "target" || e === "node_modules") continue;
    const p = join(dir, e);
    if (statSync(p).isDirectory()) scanEntities(p, out);
    else if (e.endsWith(".java")) {
      const src = readFileSync(p, "utf8");
      const t = src.match(/@TableName\("(\w+)"\)/)?.[1];
      if (!t) continue;
      // `private Long x;` / `private List<String> y;` / `private Map<String, X> z;`
      // `@TableField("col")` 显式映射优先 —— 全库改名（merchant_no→entity_no）后
      // 字段名与列名分离是常态，对齐要按**实际映射到的列**算。
      // Getter/Setter 等字段注解可以位于 TableField 与字段之间，必须整组读取。
      const fields = new Set(
        [...src.matchAll(
          /((?:@[\w.]+(?:\([^)]*\))?\s*)*)private\s+[\w.]+(?:<[^>]*>)?(?:\[\])?\s+(\w+)\s*;/g,
        )].map((m) => {
          const column = m[1]!.match(/@TableField\(\s*"(\w+)"\s*\)/)?.[1];
          return column ? camel(column) : m[2]!;
        }),
      );
      /*
       * 同一张表可能有两个实体：过渡期老实体只改了表名、新实体在新模块里（TDD-物流模块 批 1：
       * FulShipment 与 LgsWaybill 都映射 lgs_waybill）。这条检查要回答的是「这一列有没有人读得到」，
       * 所以按**所有实体字段的并集**对账 —— 只取最后扫到的那个，会因为目录顺序误报过渡用的那一个。
       */
      const prev = out.get(t);
      out.set(t, prev
        ? { file: `${prev.file} + ${p.slice(ROOT.length + 1)}`, fields: new Set([...prev.fields, ...fields]) }
        : { file: p.slice(ROOT.length + 1), fields });
    }
  }
  return out;
}

const schema: Map<string, { cols: { name: string }[] }> = existsSync(BACKEND)
  ? readSchema(ROOT, [MIGRATION_DIR, INVENTORY_MIGRATION_DIR])
  : new Map();
const entities = existsSync(BACKEND) ? scanEntities(BACKEND) : new Map();

/** 库里的业务列（去掉 BaseEntity 那八列），转成 camelCase */
const businessCols = (table: string) =>
  (schema.get(table)?.cols ?? [])
    .map((c) => camel(c.name))
    .filter((c) => !AUDIT.has(c) && !BASE_FIELDS.has(c));

describe("Java 实体与库表对齐", () => {
  it("多个字段注解之间的显式列映射仍优先于 Java 字段名", () => {
    const fields = entities.get("trd_shipping_upload")?.fields;
    expect(fields?.has("sourceSnapshot")).toBe(true);
    expect(fields?.has("sourceSnapshotJson")).toBe(false);
  });

  it("解析到了实体与表 —— 一个都没有多半是路径或注解写法变了", () => {
    expect(schema.size, "没解析到迁移").toBeGreaterThan(20);
    expect(entities.size, "没扫到 @TableName 实体").toBeGreaterThan(10);
  });

/**
 * **刻意不映射**的列：库里有、实体故意不映射。按表按列登记，且要写明为什么。
 *
 * 与「忘了加字段」的区别是**有没有替代读法**：忘了加，那一列就没人读得到；
 * 刻意不映射，是因为读它反而会读到错的东西。
 *
 * ⚠️ 只放到列这一级，不许整表豁免 —— 整表豁免之后，
 * 这张表上任何真正的漏字段都不会再报。
 */
const DELIBERATELY_UNMAPPED: Record<string, Record<string, string>> = {
  prd_goods: {
    pointsConfig: "旧版商品积分配置列保留在已应用 schema；新积分方案不按商品写配置，避免 MyBatis updateById 覆盖遗留值。",
  },
  ord_sub_order: {
    pointsGranted: "旧版积分发放标记保留在已应用 schema；新积分方案以 points voucher 与订单绑定事实实现幂等，不读写该列。",
    openAfterSaleNo:
      "V406 售后占位（一张订单同时只有一张进行中售后，库内 CAS）。只由 AfterSaleSlotMapper 的专用 SQL 读写 ——" +
      "订单流程会先读子单再 updateById，实体若映射它，会把读到的旧占位原样写回，占位就再也释放不掉。",
  },
  sys_pay_channel: {
    markets:
      "V295 起通道×市场在 sys_pay_channel_market 关系表。这一列还在库里（已应用的迁移是冻结的，" +
      "且留着才有回滚余地），但不再被写入 —— 映射它就会有人读到陈旧值，" +
      "那正是「运营改了一张表、领域读另一张，保存成功而改动不生效且不报错」的形状。",
  },
};

  it("实体不缺列 —— 少一个字段 MyBatis 不报错，只是**读不出那一列**", () => {
    const drift: string[] = [];
    for (const [table, { file, fields }] of entities) {
      const waived = DELIBERATELY_UNMAPPED[table] ?? {};
      const missing = businessCols(table)
        .filter((c) => !fields.has(c))
        .filter((c) => !waived[c]);
      if (missing.length) drift.push(`${table}（${file}）缺 ${missing.length} 列：${missing.join("、")}`);
    }
    expect(
      drift,
      "实体落后于库表。MyBatis-Plus 不会因此报错，表现是**查出来是 null**，" +
        "而排查的人会先怀疑数据：\n  " +
        drift.join("\n  ") +
        "\n→ 给实体补上字段。",
    ).toEqual([]);
  });

  it("实体没有库里不存在的字段 —— 那种写入会在运行时才炸", () => {
    const ghost: string[] = [];
    for (const [table, { file, fields }] of entities) {
      const cols = new Set(businessCols(table));
      // @TableField(exist = false) 的字段不落库，扫描认不出来，所以只报有把握的：
      // 名字看起来像列（非集合、非 transient）却在库里没有
      const extra = [...fields].filter(
        (f) => !cols.has(f) && !BASE_FIELDS.has(f) && !readFileSync(join(ROOT, file), "utf8")
          .match(new RegExp(`exist\\s*=\\s*false[^;]*?\\b${f}\\b|\\b${f}\\b[^;]*?exist\\s*=\\s*false`)),
      );
      if (extra.length) ghost.push(`${table}（${file}）多出：${extra.join("、")}`);
    }
    expect(
      ghost,
      "实体有库里没有的字段。插入/更新时才会炸，而那时是线上：\n  " + ghost.join("\n  "),
    ).toEqual([]);
  });

  it("每张业务表都有实体，或已登记为「知道欠着」", () => {
    const naked = unregisteredBusinessTables(schema.keys(), entities, NO_ENTITY_YET);
    expect(
      naked,
      "这些表没有 Java 实体，也没登记原因：\n  " +
        naked.join("\n  ") +
        "\n→ 要么补实体，要么登记进 NO_ENTITY_YET 并写清何时补。" +
        "\n  留着不管的后果：这张表只能靠手写 SQL 访问，而没人知道它已经建好了。",
    ).toEqual([]);
  });

  it("登记的非 ORM 表通过分类，未登记的新业务表仍被拒绝", () => {
    const probe = "quality_unregistered_business_probe";
    expect(unregisteredBusinessTables([...schema.keys(), probe], entities, NO_ENTITY_YET))
      .toEqual([probe]);
  });

  it("删去真实非 ORM 登记后，真实业务表与未知 probe 都被拒绝", () => {
    const registrations = { ...NO_ENTITY_YET };
    delete registrations.sys_outbox_stream;
    const probe = "quality_unregistered_business_probe";
    expect(unregisteredBusinessTables([...schema.keys(), probe], entities, registrations).sort())
      .toEqual([probe, "sys_outbox_stream"].sort());
  });

  it("已有积分、物流登记与新增 JDBC owner 表共同参与分类", () => {
    const tables = ["pts_account", "lgs_push_event", "sys_outbox_stream", "prd_stock_sync_operation",
      "prd_stock_alignment_batch", "prd_stock_alignment_line", "ord_refund_coupon_scope",
      "ord_refund_obligation", "inv_outbox_delivery", "inv_event_consumed"];
    for (const table of tables) {
      expect(schema.has(table), `真实迁移缺少 ${table}`).toBe(true);
      expect(entities.has(table), `${table} 应由非 ORM owner 维护`).toBe(false);
    }
    expect(unregisteredBusinessTables(tables, entities, NO_ENTITY_YET)).toEqual([]);
  });

  it("继承的登记键与自带原型键不能放行未知业务表", () => {
    const probe = "quality_unregistered_business_probe";
    const registrations = Object.assign(Object.create({ [probe]: "继承键不是登记" }), NO_ENTITY_YET);
    expect(unregisteredBusinessTables([probe, "toString"], entities, registrations))
      .toEqual([probe, "toString"]);
  });

  it("登记的「欠着」确实还欠着 —— 补完要删登记", () => {
    const stale = Object.keys(NO_ENTITY_YET).filter((t) => entities.has(t));
    expect(stale, `这些表已经有实体了，请从 NO_ENTITY_YET 删除：${stale.join("、")}`).toEqual([]);
  });
});
