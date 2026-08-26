// 生成物有没有跟着源码走。
//
// **为什么需要它**：这个仓库有六个「一条命令跑出来」的清单，
// 而在本脚本之前只有界面清单（gen-ui-catalog.py）挂了闸门。
// 其余五个谁都不盯，于是一直在漂 —— 2026-08-26 重跑一次：
// API 清单 +51/−16（漏了 /biz/spu-std、/mp/user/phone/* 等一批）、
// B 端功能点矩阵 +192/−69。
//
// 清单的价值全在**说的是真话**。一份漏了十几个接口的 API 清单
// 比没有清单更糟 —— 它会让读的人理直气壮地按它去对齐。
//
// **做法是「跑一遍看变不变」，不是各改五个生成器加 --check**：
//   · 生成器不用动，将来新增一个只要在 GENERATORS 里加一行
//   · 判据是产物本身，而不是生成器自己声称的「没变」
//
// ⚠️ 它会**真的写文件**。所以 pre-push 里跑的是 HEAD 干净副本（$GATE_WT），
// 写到那份副本上，不碰任何人的工作区 —— 与编译闸门同一条规矩：
// 闸门要判的是「推出去的那份对不对」。
//
// 用法：
//   node scripts/check-generated-docs.mjs           # 跑并列出漂了的
//   node scripts/check-generated-docs.mjs --check    # 漂了就非零退出
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

/** 生成器 → 它产出的、**需要提交**的文件。html 是 md 的派生物，不单列。 */
const GENERATORS = [
  ["gen-api-index.mjs", ["docs/api/API清单.md", "docs/api/openapi-b.yaml", "docs/api/openapi-c.yaml"]],
  ["gen-api-detail.mjs", ["docs/api/API详情-B端.md", "docs/api/API详情-C端.md", "docs/api/API详情-平台端.md"]],
  ["gen-biz-role-matrix.mjs", ["docs/technical/reference/B端功能矩阵-按角色.md"]],
  ["gen-biz-feature-perm-matrix.mjs", ["docs/technical/reference/B端功能点-权限码-页面.md"]],
  ["gen-perm-endpoint-matrix.mjs", ["packages/shared/tests/fixtures/ops-role-endpoint-matrix.json"]],
  ["gen-table-inventory.mjs", ["docs/technical/reference/数据库表清单.md"]],
];

const sha = (p) => (existsSync(p) ? createHash("sha1").update(readFileSync(p)).digest("hex") : "∅");

const drifted = [];
for (const [script, outputs] of GENERATORS) {
  const before = outputs.map((o) => sha(join(ROOT, o)));
  try {
    execFileSync("node", [join(ROOT, "scripts", script)], { cwd: ROOT, stdio: "pipe" });
  } catch (e) {
    console.error(`✗ ${script} 跑不起来：${e.message.split("\n")[0]}`);
    process.exit(1);
  }
  outputs.forEach((o, i) => {
    if (sha(join(ROOT, o)) !== before[i]) drifted.push(`${o}   ← ${script}`);
  });
}

if (!drifted.length) {
  console.log(`✓ ${GENERATORS.length} 个生成器的产物都是最新的`);
  process.exit(0);
}

console.error("✗ 这些生成物与源码对不上了（跑一次生成器就会变）：");
for (const d of drifted) console.error(`    ${d}`);
console.error("\n  修：跑一遍对应的生成器，把产物一起提交。");
console.error("  清单的价值全在「说的是真话」—— 漏了的那几条会让读的人理直气壮地按它去对齐。");
process.exit(process.argv.includes("--check") ? 1 : 0);
