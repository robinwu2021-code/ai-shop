// ops-web 调的每个 `/ops/**` 端点，后端必须真的有。
//
// 为什么需要它：ops-web 是**按完整产品设计写的**，后端按优先级分期实现 ——
// 到今天 189 条调用里有 123 条后端没有。这本身不是错，错的是**没人知道是哪 123 条**。
//
// 后果分两种，只有第二种是真的坏：
//   ① 整域未开工（风控、财务、内容…）—— 那个菜单进去整页都是 mock，一眼看得出来
//   ② **域做了却漏了几条** —— 页面看起来是活的，数据也是真的，
//      而某个按钮点下去 404。运营不会怀疑「这个功能还没做」，
//      只会觉得「系统坏了」。上一轮就撞到一个：券的预算列做出来了、
//      闸门也装了，而**改预算的端点不存在**，于是预算永远是 0，闸门永远不生效。
//
// 所以这条守卫是**棘轮**：把今天的缺口固定成基线，只准变少不准变多。
//   · 新出现的未接通调用 → 红，逼加的人回答一句「后端要不要做」
//   · 基线里已经接通的 → 也红，逼人把它从清单里删掉（否则清单几个月后就烂了）
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * **整域未开工**：后端一条都没实现的域。整域跳过，不逐条登记 ——
 * 逐条列出来是几十行噪音，而它们的状态是一样的：这块功能还没开始做。
 *
 * ⚠️ 一旦某个域开始实现（后端出现第一条），它就必须从这里移走，
 * 剩下的缺口逐条登记进 {@link KNOWN_GAPS} —— 下面那条断言会强制这件事。
 * 这正是最危险的时刻：域"活"了，而漏掉的那几条会变成死按钮。
 */
const UNBUILT_DOMAINS = new Set([
  // 平台治理的后期功能，UI 先行
  "contents", "risk-events", "risk-rules", "blacklists", "audit-logs",
  "faqs", "materials", "rule-texts", "appearance", "feature-flags",
  // 财务与清结算
  "finance", "payments", "settlements", "split-records", "refund-split-backs",
  "fee-rule", "markets",
  // 履约与物流
  "fulfillment", "shipments", "freight-templates",
  // 商品中心（现有的是 goods，skus 是另一套更细的视图）
  "skus",
  // 营销的后期部分
  "marketing", "content-slots", "fission-campaigns", "coupon-issues",
  "push-tasks", "demands", "attribution-rule", "attribution-traces",
  // 组织与权限（ops 自己的 RBAC 管理界面）
  "staffs", "roles",
]);

/**
 * **域做了却漏了**：这些域后端已经有实现，而这几条没有 —— 页面看着是活的，
 * 点下去 404。每一条都要有个说法，不能只是列在这里。
 */
const KNOWN_GAPS: Record<string, string> = {
  // ── 归档：ops-web 每个列表页都有归档按钮，后端只做了 categories 一个域 ──
  // 18 打 2。运营确实需要「下架但不删」（列表越积越长，删除不可接受），
  // 要么后端补齐 9 个实体的 archived_at + 端点，要么把按钮按实体隐藏。
  "POST /ops/coupons/{x}/archive": "归档未实现（全局，只有 categories 有）",
  "POST /ops/coupons/{x}/unarchive": "同上",
  "POST /ops/campaigns/{x}/archive": "同上",
  "POST /ops/campaigns/{x}/unarchive": "同上",
  "POST /ops/communities/{x}/archive": "同上",
  "POST /ops/communities/{x}/unarchive": "同上",
  "POST /ops/merchants/{x}/archive": "同上",
  "POST /ops/merchants/{x}/unarchive": "同上",
  "POST /ops/pickups/{x}/archive": "同上",
  "POST /ops/pickups/{x}/unarchive": "同上",

  // ── 券：发券 ──
  // （改预算 /budget 已补齐 —— 它曾是这张表里最坏的一条：V21 的列、领券那条
  //   UPDATE 里的闸门、页面上的进度条三样都在，唯独运营改不了它，
  //   于是预算恒为 0，闸门永远不生效。「功能做完了但没有入口」的典型。）
  "POST /ops/coupons/{x}/issue": "手动发券（客服补偿券走同一条）",

  // ── 平台投放场次：后端没有这个领域对象，见 运营端营销列表契约错配.md §3 ──
  "POST /ops/campaigns": "建平台场次 —— 后端无此对象，待产品决定",

  // ── 门店经营支持 ──
  "GET /ops/stores/qrcodes": "门店码管理",
  "GET /ops/stores/acquisition": "获客数据",
  "GET /ops/stores/templates": "装修模板",
  "POST /ops/stores/templates": "装修模板",
  "POST /ops/stores/templates/{x}/enabled": "装修模板",

  // ── 其余零星 ──
  "POST /ops/groups/{x}/audit": "拼团审核（现只有 abort）",
  "POST /ops/groups/{x}/status": "改团状态（现只有 abort）",
  "POST /ops/tickets/{x}/assign": "工单指派",
  "POST /ops/tickets/{x}/proxy-actions": "工单里的代客操作",
  "POST /ops/after-sales/{x}/status": "改售后状态（现有 arbitrate 等具名动作）",
  "POST /ops/orders/proxy": "代客下单",
};

function normalize(p: string): string {
  return p.replace(/\$\{[^}]+\}/g, "{x}").replace(/\{\w+\}/g, "{x}");
}

function domainOf(p: string): string {
  return p.split("/")[2] ?? "?";
}

/** ops-web https 层发出去的每一条 `/ops/**` 调用 */
function opsWebCalls(): Set<string> {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const out = new Set<string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/client\.(get|post|put|del)\(\s*[`"]([^`"]+)/g)) {
      const path = m[2]!;
      if (path.startsWith("/ops/")) out.add(`${m[1]!.toUpperCase()} ${normalize(path)}`);
    }
  }
  return out;
}

/** 后端真正映射出来的每一条 `/ops/**` 端点 */
function backendEndpoints(): Set<string> {
  const out = new Set<string>();
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) { walk(p); continue; }
      if (!e.name.endsWith("Controller.java")) continue;
      const src = readFileSync(p, "utf8");
      const base = src.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/);
      const prefix = base?.[1] ?? "";
      for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
        const raw = m[2]!;
        const full = raw.startsWith("/ops/") ? raw : (prefix.startsWith("/ops") ? prefix + raw : null);
        if (!full) continue;
        const verb = m[1]!.toUpperCase().replace("DELETE", "DEL");
        out.add(`${verb} ${normalize(full)}`);
      }
    }
  };
  for (const m of ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]) {
    walk(join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"));
  }
  return out;
}

describe("运营端端点存在性", () => {
  const calls = opsWebCalls();
  const backend = backendEndpoints();

  it("解析没失效 —— 扫不到东西时不能静默通过", () => {
    expect(calls.size, "一条 ops-web 调用都没扫到，正则或目录变了？").toBeGreaterThan(50);
    expect(backend.size, "一条后端端点都没扫到，正则或目录变了？").toBeGreaterThan(30);
  });

  it("★★★ 新增的调用后端必须有 —— 否则那个按钮点下去就是 404", () => {
    const gaps = [...calls]
      .filter((c) => !backend.has(c))
      .filter((c) => !UNBUILT_DOMAINS.has(domainOf(c.split(" ")[1]!)))
      .filter((c) => !(c in KNOWN_GAPS));

    expect(
      gaps.sort(),
      "这些 ops-web 调用后端没有，且不在已知缺口里 —— **点下去 404**。\n" +
        "  三选一：后端补上；从 ops-web 拿掉；或者登记进 KNOWN_GAPS 并写清楚为什么。\n" +
        "  最坏的是留着不管：页面看起来是活的，运营不会怀疑功能没做，只会觉得系统坏了。",
    ).toEqual([]);
  });

  it("★★ 已经接通的要从清单里删掉 —— 否则清单几个月后就烂了", () => {
    const stale = Object.keys(KNOWN_GAPS).filter((k) => backend.has(k));
    expect(
      stale.sort(),
      "这些已经接通了，从 KNOWN_GAPS 删掉。\n" +
        "  留着的害处是它会让人以为这块还没做，从而绕开它另写一套",
    ).toEqual([]);

    const built = [...UNBUILT_DOMAINS].filter((d) =>
      [...backend].some((b) => domainOf(b.split(" ")[1]!) === d));
    expect(
      built.sort(),
      "这些域后端**已经开始实现了**，不能再整域跳过。\n" +
        "  把它从 UNBUILT_DOMAINS 移走，剩下的缺口逐条登记进 KNOWN_GAPS。\n" +
        "  **这是最危险的时刻**：域「活」了，页面看着能用，而漏掉的那几条会变成死按钮",
    ).toEqual([]);
  });
});
