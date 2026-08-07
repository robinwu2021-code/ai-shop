#!/usr/bin/env node
/**
 * 后端验收清单生成器 —— 把 **mock 里强制执行的行为规则**导出成后端要满足的验收标准。
 *
 * 为什么是 mock 当基准：契约（OpenAPI）只能表达**形状**（字段、类型、必填），
 * 表达不了「`PREPARING` 不能直接跳 `COMPLETED`」「驳回必须填理由」「核销要校验属不属于本自提点」。
 * 这些**行为**目前只有一处是可执行的定义 —— mock。后端照着形状实现完，行为对不对没人知道。
 *
 * 两部分：
 *   · 状态机表：从 `packages/shared/src/mock/db.ts` 的 TRANSITIONS **自动抽取**，不会漂
 *   · 规则表：从三份 mock 的 `throw new Error(...)` 里抽出全部拒绝条件 —— 它们就是后端必须拒绝的输入
 *
 * 用法：npm run gen:spec
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DOC = join(ROOT, "docs/api/后端验收清单.md");
const BEGIN = "<!-- SPEC:BEGIN 由 scripts/gen-behaviour-spec.mjs 生成，勿手改 -->";
const END = "<!-- SPEC:END -->";

const read = (p) => readFileSync(join(ROOT, p), "utf8");

// ---------------------------------------------------------------- 状态机
const dbSrc = read("packages/shared/src/mock/db.ts");
const tblock = dbSrc.match(/const TRANSITIONS: Record<OrderStatus, OrderStatus\[\]> = \{(.*?)\n\};/s);
if (!tblock) throw new Error("找不到 TRANSITIONS —— 状态机改名了？");
const transitions = [...tblock[1].matchAll(/^\s*(\w+):\s*\[([^\]]*)\]/gm)].map((m) => ({
  from: m[1],
  to: [...m[2].matchAll(/"(\w+)"/g)].map((x) => x[1]),
}));

// ---------------------------------------------------------------- 拒绝条件
/** 每条 `throw new Error("...")` 都是一条「后端必须拒绝」的输入 */
function rejections(file, label) {
  const src = read(file);
  const out = [];
  for (const m of src.matchAll(/throw new Error\(\s*(`[^`]*`|"[^"]*")\s*\)/g)) {
    const msg = m[1].slice(1, -1).replace(/\$\{[^}]*\}/g, "…");
    // 取该 throw 所在的方法名：往前找最近的 `async xxx(` 或 `function xxx(`
    const before = src.slice(0, m.index);
    const owner =
      [...before.matchAll(/(?:async\s+(\w+)\s*\(|function\s+(\w+)\s*\()/g)].pop() ?? [];
    out.push({ msg, owner: owner[1] ?? owner[2] ?? "—", label });
  }
  return out;
}

const rules = [
  ...rejections("packages/shared/src/mock/db.ts", "共享"),
  ...rejections("c-app/src/api/mock.ts", "C 端"),
  ...rejections("b-app/src/api/mock.ts", "B 端"),
];

// 同一条消息可能在多处抛（如「订单不存在」），按消息去重并合并来源
const byMsg = new Map();
for (const r of rules) {
  const cur = byMsg.get(r.msg) ?? { msg: r.msg, owners: new Set(), labels: new Set() };
  cur.owners.add(r.owner);
  cur.labels.add(r.label);
  byMsg.set(r.msg, cur);
}

const lines = [
  BEGIN,
  "",
  "### 订单状态机（后端必须一致，非法迁移要拒绝）",
  "",
  "| 当前状态 | 允许迁移到 |",
  "|---|---|",
  ...transitions.map(
    (t) => `| \`${t.from}\` | ${t.to.length ? t.to.map((x) => `\`${x}\``).join(" · ") : "**终态**"} |`,
  ),
  "",
  `> 共 ${transitions.length} 个状态。**不在表内的迁移必须报错**，不能静默成功 ——`,
  "> 静默成功的后果是订单卡在一个谁也没预料到的状态里，前端界面直接空白。",
  "",
  "### 必须拒绝的输入",
  "",
  `mock 里共强制 **${byMsg.size}** 条拒绝规则。后端实现同一端点时，这些输入必须同样被拒（错误码另定，语义要一致）：`,
  "",
  "| 拒绝原因 | 触发方法 | 来源 |",
  "|---|---|---|",
  ...[...byMsg.values()]
    .sort((a, b) => a.msg.localeCompare(b.msg, "zh"))
    .map(
      (r) =>
        `| ${r.msg} | ${[...r.owners].map((o) => `\`${o}\``).join(" ")} | ${[...r.labels].join(" / ")} |`,
    ),
  "",
  END,
];

const doc = read("docs/api/后端验收清单.md");
const a = doc.indexOf(BEGIN);
const b = doc.indexOf(END);
if (a < 0 || b < 0) throw new Error("验收清单里找不到 SPEC 标记");
writeFileSync(DOC, doc.slice(0, a) + lines.join("\n") + doc.slice(b + END.length));
console.log(`已写入：${transitions.length} 个状态迁移 + ${byMsg.size} 条拒绝规则`);
