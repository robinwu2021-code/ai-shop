// 带归属列的表，必须登记进数据域表册 —— 否则那张表**永远不过滤**。
//
// `DataScopeRegistration` 的类注释里写着「由 `DataScopeCoverageTest` 校验
// 『带归属列的表都已注册』」。**那个测试不存在**，全仓搜不到 ——
// 于是这条被当成「有人在管」的规则，实际上一次都没被执行过：
// 65 张表带归属列（迁移里能扫到的），注册了 7 张。
//
// 缺登记不报错，表现是**看得见不该看见的**：一个只配了某社区的运营，
// 打开自提点列表看到全城的点，而页面、日志、报错里没有任何异常。
// 反过来漏登记某个维度也一样致命（见 DataScopeRegistration 类注释第 2 条）：
// handler 生成 `1=0` 而不是放行，那类主体整页空白。
//
// **这条守卫从出生就是红的**，所以用棘轮：记下今天的欠账数，只许降不许升。
// 它现在的作用不是逼人马上补完 56 张 —— 那是《权限体系-Java方案与调用链》§5.1
// 那个「几天量级」的独立批次 —— 而是**让新建的表不能再悄悄加入这个缺口**。
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");
const REGISTRATION = join(
  ROOT,
  "backend/shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java",
);

/**
 * 归属列。**这几个名字就是数据域的全部词汇** ——
 * `DataScopeRegistration` 里映射的也是它们（entity_no→MERCHANT、user_no→SELF…）。
 */
const OWNER_COLUMNS = [
  "entity_no",
  "merchant_no",
  "user_no",
  "community_no",
  "pickup_no",
  "store_no",
];

/**
 * 不需要数据域的表，**每一条都要写清为什么**。
 *
 * 判据是「这张表的行被别人看到，会不会泄露或误导」：
 * 字典与配置类的答案是不会（它们本来就该全局可见），
 * 业务数据的答案永远是会。
 */
const EXEMPT: Record<string, string> = {
  // 日志/流水类：归属列是「谁干的」而不是「谁的数据」，运营查审计本就要跨主体看
  sys_op_log: "运营操作审计，跨主体查是它的用途",
  mch_staff_log: "商家员工授权审计，同上",
  ord_status_log: "订单状态流水，随订单一起被过滤",
  // 关联表：本身不被直接查，总是从主表 join 进来
  mch_entity_community: "主体×社区关联，从主表进入",
  // 方案 v4（2026-08-22）：channel 的两张子表。总是随 mch_fulfillment_channel
  // （已登记 MERCHANT）一起查，自己不当检索入口
  mch_channel_pickup: "自提路×取货点关联，从 channel 主表进入",
  mch_channel_area: "channel×范围项关联，从 channel 主表进入",
  /*
   * ── 2026-08-29：会话与登录日志六张 ──
   *
   * 豁免理由不是「不重要」，是**这个机制根本够不到它们**：
   * 数据域是 MyBatis-Plus 的 `DataPermissionInterceptor`，只重写走 mapper 的 SQL。
   * 这六张表**没有 Java 实体**，只被 `SessionDao` / `LoginLogDao` 用 `JdbcClient`
   * 原生 SQL 访问（`WHERE token_hash = ?` / `WHERE user_no = ?` / GC 的 DELETE）。
   * 登记它们**一行行为都不会变** —— 那种登记比不登记更坏：清单上多一条，
   * 让人以为这张表已经被自动过滤保护着了。
   *
   * 而且它们本身就带着显式的 user_no 条件（查某人的会话/登录史），
   * 唯一跨用户的两处是**按用途如此**：撤销轮询与失败登录流（风控要看爆破）。
   */
  usr_session: "无实体，只走 SessionDao 的原生 SQL（按 token_hash 单点查），拦截器够不到",
  mch_session: "同 usr_session",
  ops_session: "同 usr_session",
  usr_login_log: "无实体，只走 LoginLogDao 原生 SQL；跨用户那一处是失败登录流，风控用途",
  mch_login_log: "同 usr_login_log",
  ops_login_log: "同 usr_login_log",
  /*
   * 幂等键。查询是 `WHERE idem_key = ? AND endpoint = ?` 的单点查，不是检索入口；
   * user_no 是「谁发起的」而不是「谁的数据」。
   *
   * **登记它反而有害**：加上归属过滤之后，幂等查询在锚点对不上的会话里恒 miss ——
   * 于是每一次重试都被当成新请求。重复下单比「看得见不该看见的」更贵。
   */
  sys_idempotent: "按 (idem_key, endpoint) 单点查；登记会让幂等恒 miss，重试变重复下单",
};

/** 迁移文件里每张表的列名（不依赖 DDL 解析器 —— 它自己另有守卫） */
function tableColumns(): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>();
  if (!existsSync(MIGRATION_DIR)) return out;
  /*
   * **按版本号排，不是按文件名排。** `.sort()` 是字典序：`V162` 会排在 `V97` 前面。
   * 只解析 CREATE 时看不出来（一张表只建一次），而下面要重放改名/删表 ——
   * 顺序错了就会「先把表改名，再去改一张已经不存在的表的列」。
   */
  const byVersion = readdirSync(MIGRATION_DIR)
    .filter((x) => x.endsWith(".sql"))
    .sort((a, b) => (Number(/^V(\d+)/.exec(a)?.[1] ?? 0) - Number(/^V(\d+)/.exec(b)?.[1] ?? 0))
      || a.localeCompare(b));
  for (const f of byVersion) {
    const src = readFileSync(join(MIGRATION_DIR, f), "utf8");
    for (const m of src.matchAll(
      /CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)\s*\(([\s\S]*?)\n\)\s*ENGINE/gi,
    )) {
      const cols = new Set<string>(
        [...m[2]!.matchAll(/^\s{4}(\w+)\s+\w/gm)].map((c) => c[1]!.toLowerCase()),
      );
      out.set(m[1]!.toLowerCase(), cols);
    }
    // 后续迁移加的列也算 —— 一张表可能是先建后补归属列的
    for (const m of src.matchAll(/ALTER TABLE\s+(\w+)\s+ADD COLUMN\s+(\w+)/gi)) {
      const t = m[1]!.toLowerCase();
      if (!out.has(t)) out.set(t, new Set());
      out.get(t)!.add(m[2]!.toLowerCase());
    }
    /*
     * **改列名**（`ALTER TABLE t RENAME COLUMN a TO b`）。
     * 不重放的话，这张表在模型里仍然带着那个**早就不存在的列名** ——
     * 而归属列判定看的正是列名：V97 把 `msg_message.user_no` 改成了 `receiver_no`，
     * 不重放就会一直按 `user_no` 把它报成「带归属列却没登记」。
     */
    for (const m of src.matchAll(
      /ALTER TABLE\s+(\w+)\s+RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)/gi,
    )) {
      const cols = out.get(m[1]!.toLowerCase());
      if (cols?.delete(m[2]!.toLowerCase())) {
        cols.add(m[3]!.toLowerCase());
      }
    }
    /*
     * **改表名**与**删表**。守卫此前两个都不认，于是它报的是**早已不存在的表**：
     * V162 把 6 张 `msg_*` 改成了 `notify_*`，而清单里躺着 msg_message /
     * msg_subscribe / msg_ticket 三条。
     *
     * 「报错了对象」和「漏报」一样坏 —— 下一个人会照着它去登记一张不存在的表，
     * 而真正该登记的那张（改名之后的）从头到尾没被提起过。
     */
    for (const m of src.matchAll(/ALTER TABLE\s+(\w+)\s+RENAME TO\s+(\w+)/gi)) {
      const from = m[1]!.toLowerCase();
      const to = m[2]!.toLowerCase();
      if (out.has(from)) {
        out.set(to, out.get(from)!);
        out.delete(from);
      }
    }
    for (const m of src.matchAll(/DROP TABLE(?:\s+IF EXISTS)?\s+(\w+)/gi)) {
      out.delete(m[1]!.toLowerCase());
    }
  }
  return out;
}

/** 已登记进表册的表名 */
function registered(): Set<string> {
  if (!existsSync(REGISTRATION)) return new Set();
  const src = readFileSync(REGISTRATION, "utf8");
  return new Set(
    [...src.matchAll(/registry\.register\(\s*"(\w+)"/g)].map((m) => m[1]!),
  );
}

describe("数据域表册覆盖", () => {
  const cols = tableColumns();
  const reg = registered();

  it("两个源都读得到（正则失效时不要静默通过）", () => {
    if (!existsSync(MIGRATION_DIR)) return; // 只装前端的场景
    expect(cols.size, "一张建表都没扫到，迁移目录或写法变了").toBeGreaterThan(30);
    expect(reg.size, "DataScopeRegistration 里一条登记都没扫到 —— 写法变了？")
      .toBeGreaterThan(3);
  });

  it("★★★ 带归属列的表要么登记、要么写明为什么不用（棘轮：只许降不许升）", () => {
    if (!cols.size) return;

    const missing = [...cols.entries()]
      .filter(([t, c]) => OWNER_COLUMNS.some((o) => c.has(o)))
      .filter(([t]) => !reg.has(t) && !EXEMPT[t])
      .map(([t, c]) => `${t}（${OWNER_COLUMNS.filter((o) => c.has(o)).join("/")}）`)
      .sort();

    /*
     * 2026-08-14 立此守卫时的欠账。**这个数只许降不许升。**
     *
     * 为什么不是 0：补登记一张表不是加一行配置就完事 —— 一张表被注册后，
     * <b>所有可能访问它的主体维度都要登记齐</b>，漏一个那类主体就整页空白
     * （DataScopeRegistration 类注释第 2 条）。而且现在数据域是每请求现算的，
     * 补一张表的影响立刻作用到所有在线的人。所以它属于 §5.1 那个独立批次。
     *
     * 补完一张就把这个数减一。
     */
    const PENDING = 56;

    expect(
      missing.length,
      `带归属列却没登记数据域的表：${missing.length} 张（记录值 ${PENDING}）——\n`
        + "  没登记 = 这张表**永远不过滤**。表现是「看得见不该看见的」：\n"
        + "  只配了某社区的运营，打开列表看到全城的数据，而没有任何报错。\n"
        + "  新增的表不许加入这个缺口：补一行 registry.register(...)，\n"
        + "  或者确实不需要（字典/配置/审计）就写进 EXEMPT 并说明理由。\n"
        + "  补完一张把 PENDING 减一。\n  "
        + missing.join("\n  "),
    ).toBeLessThanOrEqual(PENDING);
  });
});
