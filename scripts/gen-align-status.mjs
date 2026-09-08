/**
 * 把 `三端功能对齐-运营端职责推导.md` 判定表的**状态列**改成生成的。
 *
 * <h2>为什么这一列必须生成</h2>
 *
 * 它原来是手抄自 `平台端功能清单.md`，而那份文档比代码旧得离谱
 * （自称 21 个路由页，nav.ts 实际 123 个；57 个页面它一个字没提）。
 * 2026-09-09 那一天，同一个人（我）就着它把 P-4.2 掉单补偿先判成「未建」、
 * 再翻案成「已建」，**两次都不对**：页面代码与后端端点都在，只是 nav 没标 ready。
 *
 * 判定列（裁 / 定 / 兜）是业务判断，手写的，留着；
 * **状态列是事实，事实不该被抄第二遍。**
 *
 * 真源：`docs/technical/reference/运营端-功能清单.md`（它自己由 nav.ts 生成）。
 * 不直接读 nav.ts —— 中间那层已经处理了单页段、角色可见性等细节，
 * 再解析一遍就是第三份 nav 解析器，而本仓库为「同一逻辑写三遍」付过账。
 */
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(import.meta.dirname, "..");
const OPS = join(ROOT, "docs/technical/reference/运营端-功能清单.md");
const ALIGN = join(ROOT, "docs/requirements/三端功能对齐-运营端职责推导.md");

/** P-x.y → 它名下每个菜单叶子的状态 */
function leafStatusByMatrix() {
  const by = new Map();
  for (const line of readFileSync(OPS, "utf8").split("\n")) {
    const c = line.split("|");
    if (c.length !== 8) continue;
    const mx = c[5].trim();
    if (!/^P-\d+\.\d+$/.test(mx)) continue;
    if (!by.has(mx)) by.set(mx, []);
    by.get(mx).push(c[6].trim());
  }
  if (by.size < 20) {
    throw new Error(
      `只解析出 ${by.size} 个矩阵号（下界 20）—— 上游表格的列数变了？\n`
      + "不抛的话下面每一行都会落到「查不到」分支，而那看起来像是运营端什么都没有。",
    );
  }
  return by;
}

/**
 * 一个落点的聚合状态。
 *
 * 「部分就绪」不折叠成 ✅ 或 🟡 的任何一边，直接把比例写出来 ——
 * P-4.1 是 1/3（订单检索就绪，异常单处理与代客下单没有），
 * 折成 ✅ 会盖掉两个没做完的，折成 🟡 会抹掉一个做完的。
 */
function aggregate(ids, by) {
  const all = ids.flatMap((id) => by.get(id) ?? []);
  if (all.length === 0) return "—";
  const ok = all.filter((s) => s === "✅").length;
  if (ok === all.length) return "✅";
  if (ok === 0) return all.every((s) => s.startsWith("🔜")) ? "🔜 二期" : "🟡 未标 ready";
  return `🟡 ${ok}/${all.length}`;
}

const by = leafStatusByMatrix();
const src = readFileSync(ALIGN, "utf8");
let changed = 0;
const out = src.split("\n").map((line) => {
  const m = line.match(
    /^(\|\s*[CB]\s*\|\s*(?:C-[A-Z]{2}-\d{2}|B-\d+(?:\.\d+){1,2})[a-z]?[^|]*\|[^|]*\|[^|]*\|([^|]*)\|)([^|]*)\|\s*$/,
  );
  if (!m) return line;
  const ids = [...m[2].matchAll(/P-\d+\.\d+/g)].map((x) => x[0]);
  const next = ids.length === 0 ? "—" : aggregate(ids, by);
  if (m[3].trim() !== next) changed++;
  return `${m[1]} ${next} |`;
});
writeFileSync(ALIGN, out.join("\n"));
console.log(`状态列已重写：改动 ${changed} 行 / 矩阵号 ${by.size} 个`);
