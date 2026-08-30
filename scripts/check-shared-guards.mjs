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

/*
 * 跑哪些工作区。**两个都要跑** ——
 * ops-web 自己那 50 份测试此前一份都不在闸门里，与 packages/shared 那次是同一个形状：
 * 写守卫的人以为写完就生效了。2026-08-29 把它们跑了一遍时是全绿的（664 条断言），
 * 所以它的基线是空的 —— **空基线不是「没在管」，是「今天一条都不欠」**，
 * 从此任何一条红都会挡住推送。
 */
const WORKSPACES = ["packages/shared", "ops-web"];
const baselineOf = (ws) => join(ROOT, ws, "known-guard-failures.txt");

/** 跑一遍，返回 `文件::用例全名` 的有序集合。 */
function runGuards(ws) {
    const out = join(mkdtempSync(join(tmpdir(), "guards-")), "res.json");
    try {
        execFileSync("npx", ["vitest", "run", "--reporter=json", `--outputFile=${out}`],
            { cwd: join(ROOT, ws), stdio: "pipe" });
    } catch {
        // vitest 有失败时退出码非 0 —— 那是预期，结果仍写在 JSON 里
    }
    if (!existsSync(out)) {
        console.error(`✗ ${ws}: vitest 没有产出结果文件 —— 多半是 node_modules 不在，或 vitest 本身炸了。\n`
            + "  这种情况**不能当成「没有失败」**，所以这里直接红。");
        process.exit(1);
    }
    const json = JSON.parse(readFileSync(out, "utf8"));
    const failed = new Map();
    let total = 0;
    for (const file of json.testResults ?? []) {
        const name = file.name.split(`/${ws}/`).pop().replace(/^tests\//, "");
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
    /*
     * vitest 对同一种断言有**两种**打印法：短一点的列表印成 `[ …(N) ]`，
     * 长的印成 `[ Array(N) ]`。只认前一种的话，后一种会一路掉到兜底 ③
     * ——「消息里列了几项」——而消息是被截断的，于是量出来的数**偏小**。
     *
     * 这正是文件头警告过的量具错误，而且已经发生过：
     * 「平台端每个字段都有说明」曾按 ③ 冻在 371，真值 386；
     * 2026-08-30 把它改到 361（真值）后闸门当场恒红 ——
     * 两条路径量的根本不是同一个数。**恒红的闸门等于没有闸门**，
     * 所以要修的是量具，不是把基线调回去。
     */
    m = /expected \[ (?:…|Array)\((\d+)\) \] to deeply equal \[\]/.exec(flat);
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
function readBaseline(ws) {
    const file = baselineOf(ws);
    if (!existsSync(file)) {
        return new Map();
    }
    const out = new Map();
    for (const raw of readFileSync(file, "utf8").split("\n")) {
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

/** 每个工作区的断言下界 —— 低于它说明守卫没被真正执行（见下方注释）。 */
const MIN_ASSERTIONS = { "packages/shared": 150, "ops-web": 300 };

let blocked = false;

for (const ws of WORKSPACES) {
    const { failed, total } = runGuards(ws);

    /*
     * **对照量。** 一个用例都没跑到与「全部通过」在结果上一模一样 ——
     * 而前者恰恰是最该红的那种（依赖没装、目录改名、vitest 配置坏掉）。
     * 取一个远低于今天的实测、又远高于 0 的下界。
     */
    if (total < MIN_ASSERTIONS[ws]) {
        console.error(`✗ ${ws}: 只跑到 ${total} 条断言 —— 守卫没被真正执行。`
            + "这与「全部通过」长得一样，所以这里必须红。");
        process.exit(1);
    }

    if (process.argv.includes("--update")) {
        const header = [
            `# ${ws} 里**已知红着**的守卫。`,
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
        /*
         * **冻高了是静默的。**
         *
         * 基线写大一点，闸门只是变松 —— 不红、不报错、看不出来。代价要等到某天
         * 有人真的把欠账加到那个数以内，闸门说「够不着上限，通过」，
         * 而那一刻没人会想到是几个月前一次**量具错误**留下的松扣。
         * 它与本仓库其他闸门失效是同一个形状：错了不会说。
         *
         * 2026-08-29 差点发生：我拿一个没限行首的 grep 数出 17（真值 16），
         * 若按它冻基线，那 6 篇文档里就多出一张图的免检额度，而且永远查不出来。
         * 是动手改的人先自己重算了一遍才拦住。
         *
         * 所以这里把**变大的那些**单独打出来 —— 缩小不必说，变大要人看一眼。
         */
        const prev = readBaseline(ws);
        const raised = [...failed].filter(([k, v]) => prev.has(k) && v > prev.get(k));
        const brandNew = [...failed.keys()].filter((k) => !prev.has(k));

        const body = [...failed].map(([k, v]) => `${k} @<=${v}`).join("\n");
        writeFileSync(baselineOf(ws), `${header}\n${body}\n`, "utf8");
        console.log(`${ws}: 已冻结基线 ${failed.size} 条（共 ${total} 条断言）`);
        if (raised.length || brandNew.length) {
            console.warn(`⚠ ${ws}: 这次把闸门**调松**了，确认不是量具错了 ——`);
            raised.forEach(([k, v]) => console.warn(`    ${prev.get(k)} → ${v}  ${k}`));
            brandNew.forEach((k) => console.warn(`    新增一条免检  ${k}`));
            console.warn("  「两条独立路径重算一遍，数字对不对得上」——"
                + "见 docs/technical/design/守卫与闸门-问题与优化方案.md §2");
        }
        continue;
    }

    const base = readBaseline(ws);
    const added = [...failed.keys()].filter((k) => !base.has(k));
    const grown = [...failed].filter(([k, v]) => base.has(k) && v > base.get(k));
    const shrunk = [...failed].filter(([k, v]) => base.has(k) && v < base.get(k));
    const fixed = [...base.keys()].filter((k) => !failed.has(k));

    console.log(`  ${ws}：${total} 条断言 / ${failed.size} 红（基线 ${base.size} 条）`);

    if (fixed.length || shrunk.length) {
        console.log(`\n🎉 ${ws} 这些已经变好了，重新冻结一次（node scripts/check-shared-guards.mjs --update）：`);
        fixed.forEach((f) => console.log(`    修好了  ${f}`));
        shrunk.forEach(([k, v]) => console.log(`    ${base.get(k)} → ${v}  ${k}`));
        console.log("  （清单只准变短 —— 不更新的话，下次涨回去也没人发现）");
    }
    if (grown.length) {
        console.error(`\n✗ ${ws} 这些欠账**涨了**（棘轮只许降不许升）：`);
        grown.forEach(([k, v]) => console.error(`    ${base.get(k)} → ${v}  ${k}`));
        blocked = true;
    }
    if (added.length) {
        console.error(`\n✗ ${ws} 新增了守卫失败，这些不在 known-guard-failures.txt 里：`);
        added.forEach((f) => console.error(`    ${f}`));
        blocked = true;
    }
    if (grown.length || added.length) {
        console.error(`\n  本机重现：cd ${ws} && npx vitest run`);
    }
}

if (blocked) {
    process.exit(1);
}
