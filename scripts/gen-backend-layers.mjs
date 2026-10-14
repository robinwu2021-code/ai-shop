// 后端分层清单：模块 → 域 → Controller / Service / Mapper / Entity / Port。
//
// **为什么必须生成**：手工那份（`Java实体清单.md`）抬头写着「58 实体」，
// 而当天真实是 151 个 —— 差 93 个。手工清单没有变短的机制，
// 加一个类的人不会想起来去改一份别的文档。
//
// 它同时给出**四条对齐判据**，都是「不报错但会出事」的那一类：
//   ① Service 接口没有实现 —— 注入时启动失败，但只在跑到那个 profile 时
//   ② impl 没有对应接口   —— 违反 ArchitectureTest「Service 必须是接口」
//   ③ Port 没有实现       —— 跨域调用运行到那一行才 NoSuchBean
//   ④ Controller 路由前缀不在 /ops /biz /mp 里 —— 部署隔离判不到，端点可能注册错实例
//
// ⚠️ 本脚本**只扫文件名与包路径**，不解析调用关系。
// 「谁调谁」要看 ArchUnit（`ArchitectureTest`）与 SPI 的 Port 定义 ——
// 在这里再实现一遍依赖分析，等于造第二套可能与守卫不一致的判据。
//
// 用法：node scripts/gen-backend-layers.mjs
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const BACKEND = join(ROOT, "backend");
const OUT = join(ROOT, "docs/technical/reference/后端分层清单.md");

/** 域包名 → 中文。与 ER 图的域分法对齐 —— 两处不同会让人以为是两套模型 */
const DOMAIN_CN = {
  auth: "认证", user: "消费者账号", merchant: "商家主体与门店", community: "社区与自提点",
  product: "商品与类目", trade: "交易与购物车", fulfillment: "履约", marketing: "营销与团购",
  promotion: "券与活动", settle: "结算与积分", member: "会员", message: "消息与触达",
  content: "内容", platform: "平台配置", iam: "权限与账号", risk: "风控", review: "评价",
  portal: "端口聚合（BFF）", common: "公共", spi: "跨域接口（SPI）", infra: "基础设施",
  config: "配置", job: "定时任务", support: "支撑",
};

const files = [];
(function walk(dir) {
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    const st = statSync(p);
    if (st.isDirectory()) {
      // ⚠️ 是 continue 不是 return —— return 会把这个目录**剩下的条目全跳过**。
      // 第一版写成 return，于是排在 target/ 之后的兄弟目录整片没扫到。
      if (f === "target" || f === "test") continue;
      walk(p);
    } else if (f.endsWith(".java")) files.push(p);
  }
})(BACKEND);

/**
 * 「被实现过」的接口名。**从所有 java 文件收集，不能只从已分类的 rows 里收** ——
 * 第一版就是那么写的，于是 `StubWxAuthGateway`（以 Gateway 结尾、没被归到任何一层）
 * 的 `implements WxAuthPort` 从没被数到，WxAuthPort 被误报成「无实现」。
 *
 * 三种写法都算：`class X implements A, B`、`interface X extends A`（接口继承，
 * 实现落在下一层）、以及泛型参数要剥掉。
 */
const implemented = new Set();
for (const p of files) {
  const src = readFileSync(p, "utf8");
  for (const m of src.matchAll(/\b(?:class|interface)\s+\w+[^{]*?\b(?:implements|extends)\s+([^{]+?)\s*\{/gs)) {
    for (const t of m[1].split(",")) {
      const n = t.trim().replace(/<.*/, "").split(".").pop();
      if (n) implemented.add(n);
    }
  }
}

const rows = [];
for (const p of files) {
  const rel = relative(BACKEND, p);
  if (rel.includes("/test/") || rel.includes("/target/")) continue;
  const mod = rel.split("/")[0];
  const pkg = rel.replace(/^.*java\/ai\/neargo\/shop\//, "").split("/");
  const domain = pkg[0] ?? "?";
  const name = pkg.at(-1).replace(/\.java$/, "");
  const src = readFileSync(p, "utf8");

  // 「是不是接口」要看声明，不能看名字：CaptchaService 叫 Service 而它是个**类**，
  // 按名字判会把它报成「接口没有实现」。
  const isInterface = new RegExp(`\\binterface\\s+${name}\\b`).test(src);

  let kind = null;
  // @RestControllerAdvice 是全局异常/包装器，不是端点 —— 它没有路由前缀，
  // 按 Controller 判会永远落进「前缀异常」那一档
  if (/@RestController\b/.test(src) && !/@RestControllerAdvice/.test(src)) kind = "Controller";
  else if (/@TableName/.test(src)) kind = "Entity";
  else if (name.endsWith("ServiceImpl") || name.endsWith("PortImpl")) kind = "Impl";
  else if (name.endsWith("Port")) kind = isInterface ? "Port" : "Impl";
  else if (name.endsWith("Service")) kind = isInterface ? "Service" : "Impl";
  else if (name.endsWith("Mappers") || name.endsWith("Mapper")) kind = "Mapper";
  if (!kind) continue;

  rows.push({
    mod, domain, name, kind, src,
    prefix: kind === "Controller"
      // /callback 是第四种：支付通道回调，不属于三端任何一个，也不该有 @Profile
      ? (src.match(/Mapping\(\s*"?\/?(ops|biz|mp|callback)\b/) ?? src.match(/"\/(ops|biz|mp|callback)\//))?.[1] ?? "?"
      : "",
    profile: (src.match(/@Profile\("(\w+)"\)/) ?? [])[1] ?? "",
  });
}

// ── 四条对齐判据 ──
//
// ⚠️ **按命名猜「谁实现了谁」是错的**，第一版就是这么干的，误报一片：
// `MerchantPortImpl` 一个类实现了 MerchantQueryPort / MerchantAdminPort 等**六个** Port，
// 而按 `XxxPort → XxxPortImpl` 找必然找不到。同理 `@Service` 可以直接标在
// 没有 `Impl` 后缀的类上。所以判据改成**读 implements 子句**。
const names = (k) => new Set(rows.filter((r) => r.kind === k).map((r) => r.name));
const svc = names("Service");
const ports = names("Port");

const noImpl = [...svc].filter((s) => !implemented.has(s)).sort();
/*
 * **这一条以前也在按命名猜**（`XxxServiceImpl` 找得到 `XxxService` 就算数），
 * 与上面那段注释说的正好相反 —— 它是收敛那轮漏改的一条。
 *
 * 误报的来源是「接口不在 `service/` 包里就不算 Service」：
 * `PayChannelRateService` 在 `master/`、`OpsPayChannelAppService` 在 `payclient/`，
 * 两个接口都真实存在，却双双被报成「没有接口」。
 * 而这张表的读者会照着去「修」一个不存在的问题 ——
 * <b>一个报假违规的清单，训练的是「这张表不用看」</b>。
 *
 * 改成看这个类的 implements 子句里有没有东西：真正的
 * 「impl 没有对应接口」是一个什么都不实现的 `XxxServiceImpl`。
 */
const noIface = rows.filter((r) => r.kind === "Impl" && r.name.endsWith("ServiceImpl")
  && !/\bclass\s+\w+[^{]*?\bimplements\b/s.test(r.src)).map((r) => r.name).sort();
const portNoImpl = [...ports].filter((p) => !implemented.has(p)).sort();
const badPrefix = rows.filter((r) => r.kind === "Controller" && r.prefix === "?").map((r) => r.name).sort();

const byDomain = new Map();
for (const r of rows) {
  const d = byDomain.get(r.domain) ?? { c: [], s: [], m: [], e: [], p: [] };
  ({ Controller: d.c, Service: d.s, Mapper: d.m, Entity: d.e, Port: d.p, Impl: [] }[r.kind])?.push(r);
  byDomain.set(r.domain, d);
}

const L = [];
const count = (k) => rows.filter((r) => r.kind === k).length;
L.push("# 后端分层清单");
L.push("");
L.push("> **本文是生成的**：`node scripts/gen-backend-layers.mjs`。不要手改。");
L.push("> 扫 `backend/*/src/main/java`，按「模块 → 域 → 分层」归并。");
L.push(">");
L.push("> 它替掉的手工清单（`Java实体清单.md`）抬头写着「58 实体」，而当时真实是 151 个。");
L.push("> ⚠️ **只扫文件名与注解，不解析调用关系** —— 「谁调谁」看 `ArchitectureTest`");
L.push("> 与 SPI 的 Port 定义；在这里再实现一遍依赖分析，等于造第二套可能与守卫不一致的判据。");
L.push("");
L.push(`统计：**Controller ${count("Controller")} · Service ${count("Service")} · ` +
  `实现 ${count("Impl")} · Mapper ${count("Mapper")} · 实体 ${count("Entity")} · Port ${count("Port")}**`);
L.push("");

L.push("## 对齐判据");
L.push("");
L.push("| 判据 | 数 | 不对的后果 |");
L.push("|---|---|---|");
L.push(`| Service 接口没有实现 | ${noImpl.length} | 注入时启动失败，且只在跑到那个 profile 时才炸 |`);
L.push(`| impl 没有对应接口 | ${noIface.length} | 违反 ArchitectureTest「Service 必须是接口」 |`);
L.push(`| Port 没有实现 | ${portNoImpl.length} | 跨域调用运行到那一行才 NoSuchBean |`);
L.push(`| Controller 前缀不是 /ops /biz /mp | ${badPrefix.length} | 部署隔离判不到，端点可能注册到错的实例上 |`);
L.push("");
for (const [t, list] of [["Service 无实现", noImpl], ["impl 无接口", noIface],
  ["Port 无实现", portNoImpl], ["Controller 前缀异常", badPrefix]]) {
  if (list.length) L.push(`- **${t}**：${list.map((x) => `\`${x}\``).join("、")}`);
}
L.push("");
L.push("> **已查过、不是缺陷的几条**（判据的边界，不是代码的问题）：");
L.push("> - `CommonMetaController` 走 `/common/master-data`：**三端共用的主数据，故意不带端前缀**。");
L.push("> - `OpenInventoryController` 走 `/open/v1/**`：**给商家自己的 ERP / 收银系统用的第四个面**，");
L.push(">   独立的 `@Profile(\"openapi\")` —— 外部流量 QPS 不可控、要单独限流、要独立故障域。");
L.push(">   它不带 `/biz` 前缀是有意的：那三个前缀对应的是「谁在看屏幕」，而这一面没有屏幕。");
L.push("> - `MediaReadController` 的前缀来自配置（`${shop.upload.private-prefix:/media}`），");
L.push(">   静态扫描读不出是必然的。");
L.push(">");
L.push("> 这三条留在表里而不是加白名单：**白名单会把下一个真问题一起藏掉**。");
L.push("> 数字变了就来看一眼，这几条认得出来。");
L.push("");

L.push("## 按域");
L.push("");
L.push("| 域 | Controller | Service | Mapper | 实体 | Port |");
L.push("|---|---|---|---|---|---|");
for (const [d, v] of [...byDomain].sort((a, b) =>
  (b[1].c.length + b[1].s.length + b[1].e.length) - (a[1].c.length + a[1].s.length + a[1].e.length))) {
  L.push(`| ${DOMAIN_CN[d] ?? d} \`${d}\` | ${v.c.length} | ${v.s.length} | ${v.m.length} | ${v.e.length} | ${v.p.length} |`);
}
L.push("");

L.push("## Controller 与部署隔离");
L.push("");
L.push("> `@Profile` 必须与路径前缀一致（`/ops` → `ops`，`/mp` `/biz` → `api`）。");
L.push("> **标错不会有任何编译期或测试期信号** —— 测试上下文两个 profile 都在，");
L.push("> 症状只在真实实例上出现：那几条端点根本不注册，请求返回 404。");
L.push("> 守卫是 `ControllerProfileTest`，这里只是把现状列出来对照。");
L.push("");
L.push("| Controller | 前缀 | @Profile | 域 |");
L.push("|---|---|---|---|");
for (const r of rows.filter((x) => x.kind === "Controller").sort((a, b) =>
  a.prefix.localeCompare(b.prefix) || a.name.localeCompare(b.name))) {
  L.push(`| \`${r.name}\` | /${r.prefix} | ${r.profile || "—"} | ${DOMAIN_CN[r.domain] ?? r.domain} |`);
}
L.push("");

writeFileSync(OUT, L.join("\n"));
console.log(`✅ ${OUT}`);
console.log(`   Controller ${count("Controller")} · Service ${count("Service")} · 实体 ${count("Entity")} · Port ${count("Port")}`);
console.log(`   对齐：Service无实现 ${noImpl.length} · impl无接口 ${noIface.length} · Port无实现 ${portNoImpl.length} · 前缀异常 ${badPrefix.length}`);
