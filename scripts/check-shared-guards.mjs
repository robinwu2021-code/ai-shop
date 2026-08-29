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
    const failed = [];
    let total = 0;
    for (const file of json.testResults ?? []) {
        const name = file.name.split("/tests/").pop();
        for (const a of file.assertionResults ?? []) {
            total++;
            if (a.status === "failed") {
                failed.push(`${name}::${a.fullName}`);
            }
        }
    }
    return { failed: [...new Set(failed)].sort(), total };
}

function readBaseline() {
    if (!existsSync(BASELINE)) {
        return [];
    }
    return readFileSync(BASELINE, "utf8").split("\n")
        .map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
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
    const header = [
        "# packages/shared/tests 里**已知红着**的守卫。只准变短，不准变长。",
        "#",
        "# 生成：node scripts/check-shared-guards.mjs --update",
        "# 闸门：node scripts/check-shared-guards.mjs --check（pre-push 会跑）",
        "#",
        "# 为什么要有它：直接要求全绿会让闸门从第一天起恒红，而恒红的闸门等于没有闸门。",
        "# 修好一条就删一行 —— 留着的害处是**那条守卫从此免检**。",
        "#",
    ].join("\n");
    writeFileSync(BASELINE, `${header}\n${failed.join("\n")}\n`, "utf8");
    console.log(`已冻结基线：${failed.length} 条（共 ${total} 条断言）→ ${BASELINE}`);
    process.exit(0);
}

const base = readBaseline();
const baseSet = new Set(base);
const nowSet = new Set(failed);
const added = failed.filter((f) => !baseSet.has(f));
const fixed = base.filter((f) => !nowSet.has(f));

console.log(`  共享守卫 ${total} 条断言 / ${failed.length} 红（基线 ${base.length} 条）`);

if (fixed.length) {
    console.log("\n🎉 这几条已经修好了，请从 packages/shared/known-guard-failures.txt 里删掉：");
    fixed.forEach((f) => console.log(`    ${f}`));
    console.log("  （清单只准变短 —— 不删的话，下次有人把它改回去也没人发现）");
}

if (added.length) {
    console.error("\n✗ 新增了守卫失败，这些不在 known-guard-failures.txt 里：");
    added.forEach((f) => console.error(`    ${f}`));
    console.error("\n  本机重现：cd packages/shared && npx vitest run");
    process.exit(1);
}
