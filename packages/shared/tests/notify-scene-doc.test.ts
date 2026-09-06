import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * 需求矩阵 §5.4 触达场景清单 ↔ 后端 `NotifyScene.ALL`。
 *
 * <h2>为什么只判「标 ✅ 的行」</h2>
 *
 * 2026-09-06 量过一遍：需求文档里反引号包着的大写标识符共 689 处、
 * 代码里查不到的 38 种，而其中 **36 种是噪声或诚实申报** ——
 * 18 个需求编号（`A11`–`A24`）、2 个迁移号、3 个术语缩写、4 个代码标识符名，
 * 外加 8 个**明确标着 ⬜/🚧 的未做项**。
 *
 * 所以「需求文档里的标识符必须存在于代码」这条判据不能用：95% 误报的闸门
 * 只会被加一张豁免名单，然后退化成谁都不看的东西。
 *
 * 那次测量指出的是这条**窄而准**的：§5.4 是结构化表（有「触发」列和「状态」列），
 * **标 ✅ 就是在说「这条已经接通了」**，那它的场景码就必须真的存在。
 * ⬜/🚧 的行豁免 —— 它们已经说了自己没做，那不是谎。
 *
 * <h2>它抓到过什么</h2>
 *
 * 挂之前先跑，抓到 P-N-1：状态 ✅、触发写着 `TICKET_CREATED`，而那个码不存在。
 * 功能其实是实现了的（工单通知**同域直推**，与工单在同一事务里，刻意不绕 outbox），
 * ✅ 没写错 —— 错的是把一个刻意不走场景机制的实现描述成了场景码。
 * 照它去 `msg_scene_channel` 里找会找不到，从而以为没接。已改（同一提交）。
 */
const ROOT = join(import.meta.dirname, "../../..");
const MATRIX = join(ROOT, "docs/requirements/需求矩阵-三端.md");
const SCENE = join(
  ROOT,
  "backend/shop-core/src/main/java/ai/neargo/shop/message/NotifyScene.java",
);

/** `NotifyScene` 里进了 `ALL` 的场景码 */
function declaredScenes(): Set<string> {
  const src = readFileSync(SCENE, "utf8");
  const all = src.match(/ALL\s*=\s*Set\.of\(([^)]*)\)/s);
  if (!all) throw new Error("NotifyScene.ALL 的形状变了 —— 判据读不到就等于没查");
  const names = [...all[1]!.matchAll(/\b([A-Z][A-Z0-9_]*)\b/g)].map((m) => m[1]!);
  const p = prefix(src);
  const out = new Set<string>();
  for (const name of names) {
    // 常量是 `PREFIX + "RESERVE"` 这种编译期常量，要拼回完整场景码
    const m = src.match(new RegExp(`String ${name}\\s*=\\s*PREFIX\\s*\\+\\s*"([^"]+)"`));
    if (m) out.add(p + m[1]!);
    const lit = src.match(new RegExp(`String ${name}\\s*=\\s*"([^"]+)"`));
    if (!m && lit) out.add(lit[1]!);
  }
  if (out.size === 0) throw new Error("一个场景码都没解析出来 —— 解析写挂了");
  return out;
}

function prefix(src: string): string {
  const m = src.match(/String PREFIX\s*=\s*"([^"]*)"/);
  return m ? m[1]! : "";
}

/** §5.4 的三张表里，每一行的「触发」与「状态」 */
function matrixRows(): { id: string; trigger: string | null; done: boolean }[] {
  const md = readFileSync(MATRIX, "utf8");
  const sec = md.match(/### 5\.4 触达与推送域[\s\S]*?(?=\n## |\n### 5\.5|$)/);
  if (!sec) throw new Error("找不到 §5.4 —— 改标题要同步改本测试");
  const out: { id: string; trigger: string | null; done: boolean }[] = [];
  for (const line of sec[0].split("\n")) {
    const m = line.match(/^\|\s*([CBP]-N-\d+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|/);
    if (!m) continue;
    const trigger = m[3]!.match(/`([A-Z][A-Z0-9_]{2,})`/)?.[1] ?? null;
    out.push({ id: m[1]!, trigger, done: m[6]!.includes("✅") });
  }
  return out;
}

describe("触达场景清单与代码", () => {
  const rows = matrixRows();

  it("§5.4 至少解析出 12 行 —— 少扫等于全绿", () => {
    // 表格格式一改，上面那个正则会一行都匹配不到，而下面那条断言照样通过。
    // 这条是它的网：今天三张表共 17 行，留出余量但不允许塌掉。
    expect(rows.length, `只解析出 ${rows.length} 行，表格格式变了？`).toBeGreaterThanOrEqual(12);
  });

  it("★★ 标 ✅ 的行，它的场景码必须真的存在 —— 否则那一行在说一件没发生的事", () => {
    const declared = declaredScenes();
    const subject = rows.filter((r) => r.done && r.trigger);
    /*
     * 「我确实看了这么多个」。没有这句的话，把所有行都标成 ⬜（或触发列改个写法）
     * 就能让受检集合变空 —— 违规集为空、断言通过，而一行都没查。
     * 这一型判据在本轮反复栽在这里（NotifySceneCoverageTest、InvMirrorEventCoverageTest
     * 都补过同样一句），所以写在前面。今天受检 7 行。
     */
    expect(subject.length, `受检行数 ${subject.length}，这个测试正在空转`).toBeGreaterThanOrEqual(5);

    const missing = subject
      .filter((r) => !declared.has(r.trigger!))
      .map((r) => `${r.id} → ${r.trigger}`);
    expect(
      missing,
      "这些行标着 ✅（已接通），但「触发」列的场景码不在 NotifyScene.ALL 里：\n  " +
        missing.join("\n  ") +
        "\n要么那一行的状态不实，要么它根本不走场景机制（那就别把实现写成场景码）。",
    ).toEqual([]);
  });
});
