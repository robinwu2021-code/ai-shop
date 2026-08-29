#!/usr/bin/env node
/**
 * `packages/shared/tests` 的守卫**全部都要跑**，新增失败就挡住推送。
 *
 * ## 为什么把白名单改成黑名单
 *
 * 在这之前，`.githooks/pre-push` 里是一张**手工枚举**的清单：61 份守卫只列了 18 份。
 * 写守卫的人以为写完就生效了 —— 而「写了守卫」与「有闸门」是两件事。
 * 2026-08-29 把剩下 43 份跑了一遍：**16 份红、28 条断言失败**，其中至少四条是
 * **已经被突破的棘轮**（记录值写在代码里，实测早就越过去了）：
 *
 *   · data-scope-coverage  56 → 70   带归属列却没登记数据域的表 = 那张表永远不过滤
 *   · biz-contract-fields  15 → 24   后端在发、B 端契约没接的字段
 *   · doc-standard         27 → 42   没有状态行的文档
 *   · ops-endpoint-exists  反向：已经接通了却还留在 KNOWN_GAPS 里的陈行
 *
 * 一份守卫红着没人知道，与没有这份守卫是**同一件事** —— 而它还额外骗人：
 * 「这块有守卫」会让人少看一眼。
 *
 * ## 与 backend/known-failures.txt 同一套机制
 *
 * 直接要求全绿会让闸门从第一天起恒红，而**恒红的闸门等于没有闸门**（两天内就会被绕过）。
 * 所以冻结今天的 28 条为基线，只准变短：
 *
 *   · 基线之外的新失败 → 退出 1，挡住推送
 *   · 基线里已经修好的 → 打印提示，**不拦**（与后端那道一致：清单只准变短，
 *     不删的话下次有人把它改回去也没人发现）
 *
 * ## 基线记的是**量**，不是「允许失败」
 *
 * 第一版把 29 条一律记成「已知缺陷，容忍」。**那是错的**，另一条会话当天就指出来了：
 * 这里面至少三条本身就是棘轮（`没有状态行的文档`、`README 索引`、`不使用 mermaid`），
 * 它们的语义是「只降不升」。把它们冻成「允许失败」等于**把一条正在收紧的规则关掉** ——
 * 下一个人再加 100 篇没状态行的文档，闸门一声不吭。
 * 而且它伪装得比恒红更好：清单上有一行，看起来是「有人管着」。
 *
 * 所以每条基线都带一个**观测值** `@<=N`：
 *   · 实测 > N  → 红（「棘轮又涨了：N → M」）
 *   · 实测 < N  → 提示该重新冻结（清单只准变短）
 *   · 不在清单  → 红（新增失败）
 *
 * N 从失败消息里抽，按可信度依次：
 *   ① vitest 的结构化形状（`expected N to be less than or equal to M`、
 *      `expected [ …(N) ] to deeply equal []`）—— 最可信，它是断言机制本身产生的
 *   ② 守卫自己写的棘轮话术（`70 张（记录值 56）`）
 *   ③ 兜底：消息里列了几项（缩进的非空行）
 *
 * ⚠️ ③ 有个已知的弱点：vitest 会把长列表截断，所以靠 ③ 取数的那几条，
 * **欠账继续涨时它可能不动**。它挡得住「多冒出一类」，挡不住「同一类多几十条」。
 * 取到 ① 的那些没有这个问题。
 *
 * 用法：
 *     node scripts/check-shared-guards.mjs --check    # 闸门
 *     node scripts/check-shared-guards.mjs --update   # 重新冻结基线（修完一批后用）
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const SHARED = join(ROOT, "packages/shared");
const BASELINE = join(SHARED, "known-guard-failures.txt");

/** 跑一遍，返回 `文件::用例全名` 的有序集合。 */
function runGuards() {
    const out = join(mkdtempSync(join(tmpdir(), "guards-")), "res.json");
    try {
        execFileSync("npx", ["vitest", "run", "--reporter=json", `--outputFile=${out}`],
            { cwd: SHARED, stdio: "pipe" });
    } catch {
        // vitest 有失败时退出码非 0 —— 那是预期，结果仍写在 JSON 里
    }
    if (!existsSync(out)) {
        console.error("✗ vitest 没有产出结果文件 —— 多半是 node_modules 不在，或 vitest 本身炸了。\n"
            + "  这种情况**不能当成「没有失败」**，所以这里直接红。");
        process.exit(1);
    }
    const json = JSON.parse(readFileSync(out, "utf8"));
    const failed = new Map();
    let total = 0;
    for (const file of json.testResults ?? []) {
        const name = file.name.split("/tests/").pop();
        for (const a of file.assertionResults ?? []) {
            total++;
            if (a.status === "failed") {
                failed.set(`${name}::${a.fullName}`, magnitudeOf(a.failureMessages ?? []));
            }
        }
    }
    return { failed: new Map([...failed].sort()), total };
}

/** 这条失败「有多大」—— 见文件头「基线记的是量」。 */
function magnitudeOf(messages) {
    const flat = messages.join("\n").replace(/\n/g, " ");
    let m = /expected (\d+) to be less than or equal to \d+/.exec(flat);
    if (m) {
        return Number(m[1]);
    }
    m = /expected \[ …\((\d+)\) \] to deeply equal \[\]/.exec(flat);
    if (m) {
        return Number(m[1]);
    }
    m = /(\d+)\s*(?:张|个|篇|处)（(?:记录值|基线)\s*\d+）/.exec(flat);
    if (m) {
        return Number(m[1]);
    }
    // 兜底：消息里列了几项
    const items = messages.join("\n").split("\n").filter((l) => /^\s{2,}\S/.test(l)).length;
    return items || 1;
}

/** `file::name @<=N` → Map(name → N)。 */
function readBaseline() {
    if (!existsSync(BASELINE)) {
        return new Map();
    }
    const out = new Map();
    for (const raw of readFileSync(BASELINE, "utf8").split("\n")) {
        const line = raw.trim();
        if (!line || line.startsWith("#")) {
            continue;
        }
        const m = /^(.*?)\s+@<=(\d+)$/.exec(line);
        // 没写观测值的旧行按 0 处理 —— 那样它一定会红，逼人重新冻结一次，
        // 而不是悄悄退化回「允许失败」
        out.set(m ? m[1] : line, m ? Number(m[2]) : 0);
    }
    return out;
}

const { failed, total } = runGuards();

/*
 * **对照量。** 一个用例都没跑到与「全部通过」在结果上一模一样 ——
 * 而前者恰恰是最该红的那种（依赖没装、目录改名、vitest 配置坏掉）。
 * 61 份守卫今天是 322 条断言，取一个远低于它、又远高于 0 的下界。
 */
if (total < 150) {
    console.error(`✗ 只跑到 ${total} 条断言 —— 守卫没被真正执行。`
        + "这与「全部通过」长得一样，所以这里必须红。");
    process.exit(1);
}

if (process.argv.includes("--update")) {
    /*
     * **别在脏工作区上冻基线。**
     *
     * 这个仓库常有多个会话同时在改。2026-08-29 我就这么翻过一次：`--update` 跑在
     * 带着别人未提交改动的工作区上，把**别人的在建回归**（bean-validation-wired）
     * 写进了我的清单 —— 那等于替他们把问题掩掉，而且掩在一个署着我名字的文件里。
     *
     * 只提醒不阻断：修完一批之后立刻重冻是正常操作，那时工作区本来就是脏的。
     * 要紧的是**冻完看一眼 diff**，只留自己认得的行。
     */
    try {
        const dirty = execFileSync("git", ["status", "--porcelain"], { cwd: ROOT })
            .toString().trim();
        if (dirty) {
            console.warn("⚠ 工作区不干净 —— 冻出来的基线会包含**别人未提交的改动**。");
            console.warn("  冻完务必 `git diff packages/shared/known-guard-failures.txt`，只留自己认得的行。\n");
        }
    } catch {
        // 不在 git 仓库里就算了，这只是提醒
    }

    const header = [
        "# packages/shared/tests 里**已知红着**的守卫。",
        "#",
        "# 每行末尾的 `@<=N` 是**观测值**，不是「允许失败」：实测超过 N 就红。",
        "# 这些断言里有相当一部分本身就是棘轮（「只降不升」），把它们记成",
        "# 「允许失败」等于把一条正在收紧的规则关掉 —— 下一个人再加 100 条也没人知道，",
        "# 而清单上有一行会让人以为「有人管着」。",
        "#",
        "# 生成：node scripts/check-shared-guards.mjs --update",
        "# 闸门：node scripts/check-shared-guards.mjs --check（pre-push 会跑）",
        "#",
        "# 修好一条就删一行 —— 留着的害处是**那条守卫从此免检**。",
        "# 分诊与降账计划：docs/technical/design/守卫与闸门-问题与优化方案.md",
        "#",
    ].join("\n");
    const body = [...failed].map(([k, v]) => `${k} @<=${v}`).join("\n");
    writeFileSync(BASELINE, `${header}\n${body}\n`, "utf8");
    console.log(`已冻结基线：${failed.size} 条（共 ${total} 条断言）→ ${BASELINE}`);
    process.exit(0);
}

const base = readBaseline();
const added = [...failed.keys()].filter((k) => !base.has(k));
const grown = [...failed].filter(([k, v]) => base.has(k) && v > base.get(k));
const shrunk = [...failed].filter(([k, v]) => base.has(k) && v < base.get(k));
const fixed = [...base.keys()].filter((k) => !failed.has(k));

console.log(`  共享守卫 ${total} 条断言 / ${failed.size} 红（基线 ${base.size} 条）`);

if (fixed.length || shrunk.length) {
    console.log("\n🎉 这些已经变好了，重新冻结一次（node scripts/check-shared-guards.mjs --update）：");
    fixed.forEach((f) => console.log(`    修好了  ${f}`));
    shrunk.forEach(([k, v]) => console.log(`    ${base.get(k)} → ${v}  ${k}`));
    console.log("  （清单只准变短 —— 不更新的话，下次涨回去也没人发现）");
}

if (grown.length) {
    console.error("\n✗ 这些欠账**涨了**（棘轮只许降不许升）：");
    grown.forEach(([k, v]) => console.error(`    ${base.get(k)} → ${v}  ${k}`));
}
if (added.length) {
    console.error("\n✗ 新增了守卫失败，这些不在 known-guard-failures.txt 里：");
    added.forEach((f) => console.error(`    ${f}`));
}
if (grown.length || added.length) {
    console.error("\n  本机重现：cd packages/shared && npx vitest run");
    process.exit(1);
}
