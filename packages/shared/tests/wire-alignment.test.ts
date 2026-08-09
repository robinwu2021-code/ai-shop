// 请求体字段级对齐：**路径对得上，不代表 body 对得上**。
//
// 这条断言的来历：`/biz/pickup/verify` 前后端路径完全一致，契约守卫（api-align.py）显示"已覆盖"，
// 但后端收 `{verifyCode}`、b-app 发 `{code}` —— 联调时以 400「参数错误」的形式暴露，
// 而那个报错既不指出是哪个字段，也不指出是谁的错。发现它靠的是人工去读 Java。
//
// 守卫只比路径，是因为路径能从两边**平等地**抽出来；body 一边是 TS 一边是 Java。
// 但 Java 的 `record XxxReq(String a, Boolean b)` 结构极其规整，抽字段并不难 ——
// 难的是有人想到要抽。
//
// 覆盖面：B 端 `/biz/**` 里**两边都定义了 body** 的 POST。后端没用 @RequestBody 的
// （如纯 @PathVariable 的 approve/receive）不在此列。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
/**
 * 找出所有 Controller 文件。
 *
 * **不能只扫 `shop-app/portal`** —— 2026-08 模块合并（S7 垂直切片）之后，
 * 25 个 Controller 里有 20 个搬进了各自域模块的 `api` 包，
 * 只扫 portal 会让这条守卫悄悄退化成「只比 5 个 Controller」：
 * 它不报错、还是绿的，但已经不再守任何东西。
 *
 * @param portal `mp` | `biz` | `ops` —— 按端分组，与包名末段一致
 */
function controllerFiles(portal: string): string[] {
  const roots = ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]
    .map((m) => join(ROOT, "backend", m, "src/main/java"))
    .filter((d) => existsSync(d));
  const out: string[] = [];
  const walk = (dir: string) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      // 端的归属看目录：portal/mp 与 user/api/mp 都算 mp
      else if (e.name.endsWith("Controller.java") && dir.endsWith(`/${portal}`)) out.push(p);
    }
  };
  roots.forEach(walk);
  return out;
}

/** 后端：路径 → 请求体字段集合 */
function backendBodies(portal: string): Map<string, { type: string; fields: string[] }> {
  const out = new Map<string, { type: string; fields: string[] }>();
  for (const file of controllerFiles(portal)) {
    const src = readFileSync(file, "utf8");

    // 同一个文件里的 `record XxxReq(String a, Boolean b) {}` —— Java record 的形参就是 JSON 字段
    const records = new Map<string, string[]>();
    for (const m of src.matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
      const fields = m[2]!
        .split(",")
        .map((p) => p.trim().split(/\s+/).pop())
        .filter((x): x is string => !!x);
      records.set(m[1]!, fields);
    }

    // @PostMapping("/biz/x") + 下一行的方法签名里的 @RequestBody XxxReq
    for (const m of src.matchAll(
      /@PostMapping\(\s*"([^"]+)"\s*\)\s*public\s+[\w.<>]+\s+\w+\(([^)]*)\)/gs,
    )) {
      const path = m[1]!;
      const bodyType = m[2]!.match(/@RequestBody\s+(\w+)/)?.[1];
      if (!bodyType) continue; // 没有 body 的端点不比
      const fields = records.get(bodyType);
      if (fields) out.set(path, { type: bodyType, fields });
    }
  }
  return out;
}

/** 前端：路径 → wire 类型字段集合。两端结构相同（endpoints + requests + 生成器的映射表） */
function frontendBodies(app: string): Map<string, { type: string; fields: string[] }> {
  const eps = readFileSync(join(ROOT, app, "src/api/endpoints.ts"), "utf8");
  const reqs = readFileSync(join(ROOT, app, "src/api/requests.ts"), "utf8");
  const gen = readFileSync(join(ROOT, app, "scripts/gen-openapi.mjs"), "utf8");

  // 方法名 → 路径（`:x` 换成 `{x}`，与 Java 的写法对齐）
  const pathOf = new Map<string, string>();
  for (const m of eps.matchAll(/(\w+):\s*\{\s*method:\s*"POST",\s*path:\s*"([^"]+)"/g)) {
    pathOf.set(m[1]!, m[2]!.replace(/:(\w+)/g, "{$1}"));
  }

  // 方法名 → wire 类型（取自生成器里的 REQUEST_TYPES —— 它已经是这份映射的唯一真源）
  const typeOf = new Map<string, string>();
  const block = gen.match(/const REQUEST_TYPES = \{(.*?)\n\};/s);
  for (const m of (block?.[1] ?? "").matchAll(/(\w+):\s*"(\w+)"/g)) typeOf.set(m[1]!, m[2]!);

  // wire 类型 → 字段（只解析直白的 interface；type 别名指向共享类型的跳过，那类由 TS 保证）
  const fieldsOf = new Map<string, string[]>();
  for (const m of reqs.matchAll(/export interface (\w+) \{(.*?)\n\}/gs)) {
    fieldsOf.set(m[1]!, [...m[2]!.matchAll(/^\s{2}(\w+)\??:/gm)].map((x) => x[1]!));
  }

  const out = new Map<string, { type: string; fields: string[] }>();
  for (const [method, path] of pathOf) {
    const type = typeOf.get(method);
    const fields = type ? fieldsOf.get(type) : undefined;
    if (type && fields) out.set(path, { type, fields });
  }
  return out;
}

/**
 * 已知差异基线（同 type-alignment 的做法）。
 *
 * 与 B 端那三条不同，C 端这几条**不是改个名就能解决**——它们背后是两边设计不同，
 * 需要决策而不是重命名。所以登记在这里：**基线内不报错、基线外立刻失败**，
 * 且每条必须写清「差在哪 / 谁该改 / 为什么」。空着理由的条目下次就没人看得懂了。
 */
const KNOWN_BODY_DRIFT: Record<string, string> = {
  "/mp/order":
    "前端多发 usePoints / groupNo / appointmentAt，后端 CreateOrderReq 不认。" +
    "**这三个是真功能不是多余字段**：积分抵扣（一期 FEATURES.points=false，可缓）、" +
    "参团下单、预约时段。后两条是 C 端已实现的下单路径 —— 后端不补字段，接上去就是**静默丢数据**：" +
    "参团单变普通单、预约单没有时段。→ 后端补字段。",
  "/mp/order/{orderNo}/after-sale":
    "后端要 refundMinor（退款金额）、前端发 reasonCode（原因编码）。" +
    "退款金额该由服务端按订单算还是前端传，属于口径问题（部分退款要不要支持）→ 待定 M4。",
  "/mp/group-request":
    "求团单两边设计不同：前端有 pickupNo / budgetMinor（预算），后端有 images / days（有效期）。" +
    "不是命名差异，是**需求本身没对齐** → 需要产品拍板求团单到底收哪些信息。",
};

describe.each([
  ["C 端", "mp", "c-app"],
  ["B 端", "biz", "b-app"],
])("请求体字段（%s）：前端发的与后端收的必须一致", (_label, portal, app) => {
  const be = backendBodies(portal);
  const fe = frontendBodies(app);

  it("两边都定义了 body 的端点，字段名对得上", () => {
    if (!be.size) return; // 后端源码不在（只装前端的场景）
    const problems: string[] = [];
    for (const [path, b] of be) {
      const f = fe.get(path);
      if (!f) continue; // 前端还没接这条 —— 属于进度差，不是漂移
      if (path in KNOWN_BODY_DRIFT) continue; // 已登记的设计差异，见基线注释
      const missing = b.fields.filter((x) => !f.fields.includes(x));
      const extra = f.fields.filter((x) => !b.fields.includes(x));
      if (missing.length || extra.length) {
        problems.push(
          `${path}\n    后端 ${b.type}: [${b.fields.join(", ")}]\n    前端 ${f.type}: [${f.fields.join(", ")}]` +
            (missing.length ? `\n    → 前端没发：${missing.join(", ")}` : "") +
            (extra.length ? `\n    → 后端不认：${extra.join(", ")}` : ""),
        );
      }
    }
    expect(
      problems,
      "路径对得上但 body 对不上 —— 联调时会以 400「参数错误」的形式暴露，" +
        "而那个报错既不说是哪个字段，也不说是谁的错：\n" +
        problems.join("\n"),
    ).toEqual([]);
  });

  it("基线只登记真的还差着的 —— 对齐了要删掉", () => {
    if (!be.size) return;
    const stale = Object.keys(KNOWN_BODY_DRIFT).filter((path) => {
      const b = be.get(path);
      const f = fe.get(path);
      if (!b || !f) return false;
      return (
        b.fields.length === f.fields.length && b.fields.every((x) => f.fields.includes(x))
      );
    });
    expect(stale, `以下已经对齐，请从 KNOWN_BODY_DRIFT 删除：${stale.join(", ")}`).toEqual([]);
  });

  it("至少比到了几条 —— 解析失效时不能静默通过", () => {
    // 正则解析最危险的失败方式是「一条都没抽到」却报绿。守卫自己瘸了比没有守卫更糟。
    if (!be.size) return;
    const compared = [...be.keys()].filter((p) => fe.has(p));
    expect(
      compared.length,
      `后端抽到 ${be.size} 条带 body 的端点、前端抽到 ${fe.size} 条，但没有一条能对上 —— ` +
        "多半是解析失效（路径写法变了？REQUEST_TYPES 改名了？），不是真的没有交集",
    ).toBeGreaterThan(0);
  });
});
