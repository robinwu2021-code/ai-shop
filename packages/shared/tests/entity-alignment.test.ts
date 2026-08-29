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
  mkt_fission_invite: "裂变邀请台账：表建了、写入链路从没建过，见上面注释；补实体前先定写入方",
};

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
      // 字段名与列名分离是常态，对齐要按**实际映射到的列**算
      const fields = new Set(
        [...src.matchAll(
          /(?:@TableField\("(\w+)"\)\s*)?private\s+[\w.]+(?:<[^>]*>)?(?:\[\])?\s+(\w+)\s*;/g,
        )].map((m) => (m[1] ? camel(m[1]) : m[2]!)),
      );
      out.set(t, { file: p.slice(ROOT.length + 1), fields });
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
  it("解析到了实体与表 —— 一个都没有多半是路径或注解写法变了", () => {
    expect(schema.size, "没解析到迁移").toBeGreaterThan(20);
    expect(entities.size, "没扫到 @TableName 实体").toBeGreaterThan(10);
  });

  it("实体不缺列 —— 少一个字段 MyBatis 不报错，只是**读不出那一列**", () => {
    const drift: string[] = [];
    for (const [table, { file, fields }] of entities) {
      const missing = businessCols(table).filter((c) => !fields.has(c));
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
    const naked = [...schema.keys()]
      .filter((t) => !IGNORE.test(t) && !entities.has(t) && !(t in NO_ENTITY_YET));
    expect(
      naked,
      "这些表没有 Java 实体，也没登记原因：\n  " +
        naked.join("\n  ") +
        "\n→ 要么补实体，要么登记进 NO_ENTITY_YET 并写清何时补。" +
        "\n  留着不管的后果：这张表只能靠手写 SQL 访问，而没人知道它已经建好了。",
    ).toEqual([]);
  });

  it("登记的「欠着」确实还欠着 —— 补完要删登记", () => {
    const stale = Object.keys(NO_ENTITY_YET).filter((t) => entities.has(t));
    expect(stale, `这些表已经有实体了，请从 NO_ENTITY_YET 删除：${stale.join("、")}`).toEqual([]);
  });
});
