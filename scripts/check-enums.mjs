// 枚举对账：**端上声明的取值，后端真的会下发/接受吗**。
//
// 为什么需要它：现有的 `check:api` 只比路径，不比取值。而这一轮连着撞到三次
// 同一形状的故障 —— 路径通、状态码 200、字段也在，唯独**值对不上**：
//   · 订单状态：端上筛 PAID/SHIPPED/ARRIVED，后端存 WAIT_FULFILL/FULFILLING
//     → 买家付完钱订单从「待取货」消失，商家三个页签全空
//   · 售后状态：ops-web 一套「处理阶段」的名字，库里另一套
//   · 登录方式：端上发 WX_MINI，后端只认 WECHAT_MP
// 三次都不是打字错误，是**两边各自定义了一套词汇，而没有任何东西比对过**。
// 只跑 mock 的端更是如此：mock 说什么它就信什么，永远不会被真实响应打脸。
//
// 做法（刻意保守，宁可漏报不误报）：
//   全集 = 后端 Java 与迁移脚本里出现过的所有大写下划线字面量
//   逐个检查端上的字面量联合类型，报出全集里没有的值
//
// 它抓不到「两边都有这个词但含义不同」，那需要语义比对；
// 但上面三次故障它全都能抓到，而且零成本。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");

/**
 * 不参与比对的类型：**纯端上概念**，后端根本不认识它们，报出来只是噪音。
 * 每一条都要写清楚为什么 —— 否则这份名单会变成「报错了就往里加一行」的垃圾场。
 */
const CLIENT_ONLY = new Map([
  ["Market", "多市场是端上的展示分区（CN/SG），后端按门店所在地算，不下发这个词"],
  ["Lang", "语言标识走 Accept-Language 头，不是业务枚举"],
  ["Skin", "皮肤是端上的外观设置，与后端无关"],
  ["ThemeMode", "同上"],
  ["MaterialKind", "分享物料的形态由端上决定用哪种渲染，后端只给文案与链接"],
]);

/**
 * 取常量对象里的取值：`export const X = { A: "A", B: "B" } as const;`
 *
 * <p>为什么必须一起扫：**这类常量对象也是 wire 契约**。
 * 漏掉它的代价这一轮实测过 —— `CATEGORY_TYPE.GOODS = "GOODS"` 而后端存的是
 * `NORMAL`，C 端「日用百货」标签页因此永远是空的，而页面写着
 * 「你的社区还没有这类商家」，把 bug 完美伪装成业务事实。
 * 工具只扫联合类型时，这条一直是绿的。
 */
function constEnums(text) {
  const out = new Map();
  const re = /export const ([A-Z][A-Z0-9_]*) = \{([^}]*)\} as const;/g;
  for (const m of text.matchAll(re)) {
    const values = [...m[2].matchAll(/:\s*"([^"]+)"/g)].map((x) => x[1]);
    if (values.length) out.set(m[1], values);
  }
  return out;
}

/** 取一个文件里所有字面量联合类型：`export type X = "A" | "B";` */
function unionTypes(text) {
  const out = new Map();
  const re = /export type (\w+)\s*=\s*((?:\s*\|?\s*"[^"]+"\s*)+);/g;
  for (const m of text.matchAll(re)) {
    const values = [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    if (values.length) out.set(m[1], values);
  }
  return out;
}

function walk(dir, ext, out = []) {
  if (!existsSync(dir)) return out;
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, name.name);
    if (name.isDirectory()) walk(p, ext, out);
    else if (p.endsWith(ext)) out.push(p);
  }
  return out;
}

/** 后端可能下发或接受的全部取值。Java 常量、switch 分支、迁移脚本里的种子与默认值都算 */
export function backendVocabulary() {
  const out = new Set();
  for (const f of walk(join(ROOT, "backend"), ".java")) {
    if (f.includes("/target/") || f.includes("/src/test/")) continue;
    for (const m of readFileSync(f, "utf8").matchAll(/"([A-Z][A-Z0-9_]{2,})"/g)) {
      out.add(m[1]);
    }
  }
  for (const f of walk(join(ROOT, "backend/shop-app/src/main/resources/db/migration"), ".sql")) {
    for (const m of readFileSync(f, "utf8").matchAll(/'([A-Z][A-Z0-9_]{2,})'/g)) {
      out.add(m[1]);
    }
  }
  return out;
}

/** 端上声明的枚举。shared 供 c-app/b-app 共用，ops-web 自己一套 */
export function clientEnums() {
  const out = [];
  const shared = join(ROOT, "packages/shared/src/types/index.ts");
  for (const [name, values] of unionTypes(readFileSync(shared, "utf8"))) {
    out.push({ client: "shared", name, values });
  }
  // 常量对象同样是 wire 契约 —— 见 constEnums 的注释
  const consts = join(ROOT, "packages/shared/src/utils/constants/index.ts");
  if (existsSync(consts)) {
    for (const [name, values] of constEnums(readFileSync(consts, "utf8"))) {
      out.push({ client: "shared", name, values });
    }
  }
  for (const f of walk(join(ROOT, "ops-web/lib/types"), ".ts")) {
    if (f.endsWith(".test.ts")) continue;
    for (const [name, values] of unionTypes(readFileSync(f, "utf8"))) {
      out.push({ client: "ops-web", name, values });
    }
  }
  return out;
}

export function findGaps() {
  const vocab = backendVocabulary();
  const gaps = [];
  for (const { client, name, values } of clientEnums()) {
    if (CLIENT_ONLY.has(name)) continue;
    // 只查形似枚举值的 token：小写或带空格的是展示文案，本来就不该在后端出现
    const missing = values.filter((v) => /^[A-Z][A-Z0-9_]*$/.test(v) && !vocab.has(v));
    if (missing.length) gaps.push({ client, name, missing, total: values.length });
  }
  return gaps;
}

// ─────────────────────────────────────────────────────────────────────────────

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const gaps = findGaps();
  const byClient = { shared: [], "ops-web": [] };
  for (const g of gaps) byClient[g.client].push(g);

  console.log("枚举对账：端上声明的取值，后端从不下发/接受的部分\n");
  for (const [client, rows] of Object.entries(byClient)) {
    const label = client === "shared" ? "shared（c-app / b-app）" : "ops-web";
    if (!rows.length) {
      console.log(`✅ ${label}：无差异`);
      continue;
    }
    console.log(`── ${label} —— ${rows.length} 个类型有差异 ──`);
    for (const r of rows) {
      console.log(`   ${r.name}  (${r.missing.length}/${r.total})  ${r.missing.join(", ")}`);
    }
    console.log("");
  }
  console.log(
    "差异不等于 bug：也可能是后端还没实现那条链路。\n" +
      "但每一条都意味着**端上写了一个后端永远不会给它的值** —— 要么去实现，要么从契约里删掉。",
  );
}
