#!/usr/bin/env node
/**
 * `docs/technical/README.md` 的「未归类」小节。
 *
 * ## 为什么要生成，而不是手工补
 *
 * 这份索引是**人工编排**的（按域分组：积分 / 支付 / 结算 / 端与架构 / 运营端逐模块…），
 * 那份编排有价值，生成器不该碰。但它同时要求**每一篇文档都在里面**，而
 * `design/` 下有两百多篇、还在按天增加 —— 2026-08-29 实测：212 篇里索引登记了 185 篇。
 *
 * 手工补的问题不是「一次补不完」，是**补完第二天又会红**：那天同伴新加一篇文档没登记，
 * 「每篇文档都要在索引里」那条棘轮从 71 涨到 72，于是 pre-push **挡住了所有人**，
 * 不只是作者自己。
 *
 * 所以折中：**人工分组原样保留，生成器只维护末尾一个「未归类」小节**。
 * 编排不丢、闸门恒绿，而有人可以慢慢把条目从「未归类」挪进正确分组 ——
 * 挪走之后这里会自动少一行，因为判据是「文件名有没有出现在这个小节之外」。
 *
 * ## 一个容易写错的地方
 *
 * 判断「这篇已经登记了吗」时，**必须把「未归类」小节自己排除掉**。
 * 不排除的话，第一次生成之后每篇都算「已登记」，小节会永远停在第一次的内容上，
 * 而新文档再也进不来 —— 那时它看起来仍然是绿的。
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const ROOT = new URL("..", import.meta.url).pathname;
const TECH = join(ROOT, "docs/technical");
const INDEX = join(TECH, "README.md");
const BEGIN = "<!-- gen-doc-index:begin -->";
const END = "<!-- gen-doc-index:end -->";

function mdFiles(dir, out = []) {
    for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        if (statSync(p).isDirectory()) {
            if (name !== "ADR") mdFiles(p, out);
        } else if (name.endsWith(".md") && name !== "README.md") {
            out.push(p);
        }
    }
    return out;
}

/**
 * 文档自己的状态行。取不到就留空 —— **不编一个**。
 *
 * ⚠️ **状态行里的 markdown 链接要拆成纯文本。** 那些链接是相对于**原文档所在目录**写的
 * （`../../v2/…`、`../design/…`），原样搬进 `docs/technical/README.md` 就指到了别处 ——
 * 第一版这么干，立刻被「md 引用的 md 必须存在」那条闸抓到两条死链。
 * 只留链接文字：索引这一列要的是「它现在什么状态」，不是一个可点的深链。
 */
function statusOf(src) {
    const m = /^\s*(?:>\s*)?(?:\*\*)?状态(?:\*\*)?\s*[:：]\s*(.+)$/m.exec(src);
    if (!m) return "";
    return m[1].trim()
        .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
        .replace(/\|/g, "\\|")
        .slice(0, 80);
}

function titleOf(src, fallback) {
    const m = /^#\s+(.+)$/m.exec(src);
    return (m ? m[1] : fallback).trim().replace(/\|/g, "\\|");
}

const index = readFileSync(INDEX, "utf8");
const b = index.indexOf(BEGIN);
const e = index.indexOf(END);
// **判「已登记」时排除生成块自己** —— 理由见文件头
const curated = b < 0 ? index : index.slice(0, b) + index.slice(e < 0 ? b : e + END.length);

const rows = [];
for (const f of mdFiles(TECH).sort()) {
    const name = f.split("/").pop();
    if (curated.includes(name)) continue;
    const src = readFileSync(f, "utf8");
    const rel = f.slice(TECH.length).replace(/^\/+/, "");
    rows.push(`| [${titleOf(src, name)}](./${rel}) | ${statusOf(src) || "—"} |`);
}

const block = [
    BEGIN,
    "",
    "### 未归类",
    "",
    "> **这一节由 `scripts/gen-doc-index.mjs` 生成，别手改。**",
    "> 它列的是还没被放进上面任何一个分组的文档 —— 不是「不重要」，是**还没人给它安家**。",
    "> 把某一条挪进合适的分组之后，这里会自动少一行（判据是「文件名有没有出现在本节之外」）。",
    "",
    "| 文档 | 状态 |",
    "|---|---|",
    ...rows,
    "",
    END,
].join("\n");

const next = b >= 0 && e >= 0
    ? index.slice(0, b) + block + index.slice(e + END.length)
    : index.replace(/\s*$/, "\n\n") + block + "\n";

writeFileSync(INDEX, next);
console.log(`docs/technical/README.md：未归类 ${rows.length} 篇`);
