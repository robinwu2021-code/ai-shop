// 权限配置文档里的数据清单必须与生成器一致。
//
// **它守的是「生成物没重新生成」这一类问题**，而这一类在本仓库反复出现：
//   · 2026-08-12：改了 nav.ts 加了 6 个菜单叶子，忘了重跑生成器 →
//     那几个功能在菜单里根本进不去，症状看起来像「功能没做完」
//   · 同一天：那份数据清单写着「68 个功能点」，而库里已经 104 个
//
// 一份**错的**清单比没有清单更坏 —— 人会照着它做判断。
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const DOC = join(ROOT, "docs/technical/design/权限配置落库-数据库设计与数据清单.md");
const BEGIN = "<!-- BEGIN:generated";
const END = "<!-- END:generated -->";

describe("权限配置文档的数据清单", () => {
  it("★★★ 必须与 gen-perm-seed.mjs 的产出一致 —— 不一致就是清单过期了", () => {
    const doc = readFileSync(DOC, "utf8");
    const b = doc.indexOf(BEGIN);
    const e = doc.indexOf(END);
    expect(b, `${DOC} 里找不到 ${BEGIN} 标记`).toBeGreaterThan(-1);
    expect(e, `${DOC} 里找不到 ${END} 标记`).toBeGreaterThan(b);

    const inDoc = doc.slice(b, e + END.length).trim();
    const fresh = execFileSync("node", ["scripts/gen-perm-seed.mjs", "--markdown"], {
      cwd: join(ROOT, "ops-web"), encoding: "utf8", maxBuffer: 8 << 20,
    }).trim();

    // 先比行数与首行差异：整段 diff 是一大坨表格，看不出「哪里变了」
    const a = inDoc.split("\n");
    const c = fresh.split("\n");
    const firstDiff = a.findIndex((line, i) => line !== c[i]);
    expect(
      firstDiff === -1 && a.length === c.length,
      "数据清单与生成器的产出不一致 —— 跑一次：\n"
        + "    node ops-web/scripts/gen-perm-seed.mjs --doc\n\n"
        + (firstDiff === -1
          ? `行数不同：文档 ${a.length} 行，生成器 ${c.length} 行`
          : `第 ${firstDiff + 1} 行起不同：\n  文档：${a[firstDiff]}\n  生成：${c[firstDiff] ?? "(没有这一行)"}`),
    ).toBe(true);
  });
});
