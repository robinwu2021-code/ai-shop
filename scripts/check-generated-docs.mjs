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
//   node scripts/check-generated-docs.mjs            # 列出漂了的；漂了就非零退出
//   node scripts/check-generated-docs.mjs --check     # 同上（`--check` 只是保留兼容）
//
// **它不改你的工作区**：跑生成器是为了比对，比完立刻把文件还原回去。
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, existsSync, rmSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");

/**
 * 生成器 → 它产出的、**需要提交**的文件。html 是 md 的派生物，不单列。
 *
 * 第一个元素是**相对仓库根的路径**（不再默认 `scripts/`）——
 * 三份 OpenAPI 规格的生成器住在各端自己的 `scripts/` 下，而它们此前一个都没挂在这儿。
 * 后果不是「产物旧了」这么轻：2026-08-27 查出来**三份规格的生成器全都跑不起来**，
 * 各自停在最后一次能跑通的那天，而没有任何东西会报：
 *   · c-app —— 新端点漏登记 RESPONSE_TYPES，生成器直接失败（已修，本次挂上闸门）
 *   · b-app —— 18 个端点（整个库存域）用了「复数资源名 + id」，撞上它自己那条命名规矩
 *   · ops-web —— 契约声明了 `lockChannel`，http 实现里没有
 * 后两条要动的是**线上端点或约定本身**，不是文档，所以先不挂 —— 一道从第一天起
 * 就红的闸门等于没有闸门（同 `check-new-test-failures.mjs` 的口径）。
 */
const GENERATORS = [
  /*
   * ⚠️ 这里原先写着三个产物，而 `gen-api-index.mjs` **只写一个**（`docs/api/API清单.md`）。
   * `openapi-b.yaml` 是它的**输入**，`openapi-c.yaml` 更是**从来不存在**——
   * 于是这两条永远「跑前跑后一样」，永远绿。列一个不存在的产物不会报错，
   * 只会让人以为它被守着。
   */
  ["scripts/gen-api-index.mjs", ["docs/api/API清单.md"]],
  ["c-app/scripts/gen-openapi.mjs", ["docs/api/openapi.yaml"]],
  ["ops-web/scripts/gen-openapi.mjs", ["docs/api/openapi-ops.yaml"]],
  ["b-app/scripts/gen-openapi.mjs", ["docs/api/openapi-b.yaml"]],
  ["scripts/gen-api-detail.mjs", ["docs/api/API详情-B端.md", "docs/api/API详情-C端.md", "docs/api/API详情-平台端.md"]],
  ["scripts/gen-biz-role-matrix.mjs", ["docs/technical/reference/B端功能矩阵-按角色.md"]],
  ["scripts/gen-biz-feature-perm-matrix.mjs", ["docs/technical/reference/B端功能点-权限码-页面.md"]],
  ["scripts/gen-perm-endpoint-matrix.mjs", ["packages/shared/tests/fixtures/ops-role-endpoint-matrix.json"]],
  ["scripts/gen-table-inventory.mjs", ["docs/technical/reference/数据库表清单.md"]],
  ["scripts/gen-c-feature-matrix.mjs", ["docs/technical/reference/C端功能点-登录态-页面.md"]],
  ["scripts/gen-backend-layers.mjs", ["docs/technical/reference/后端分层清单.md"]],
  // 只维护 README 末尾那个「未归类」小节，人工分组它不碰（理由见生成器文件头）
  ["scripts/gen-doc-index.mjs", ["docs/technical/README.md"]],
  ["scripts/gen-perm-domain-matrix.py", ["docs/technical/reference/三端权限矩阵-按业务域.md"]],
  // 运营端功能清单：它比权限矩阵多量一样东西 —— **谁看得见哪个菜单**。
  // 权限码可达（那是上面那份端点矩阵管的）不等于菜单里有那一行
  ["scripts/gen-ops-feature-list.py", ["docs/technical/reference/运营端-功能清单.md"]],
  ["scripts/gen-ui-lib.py", ["docs/technical/design/ui-lib.json"]],
  /*
   * 三份界面规范。**必须是生成的**：这个仓库里有 170 份手写设计文档，
   * 而 2026-08-28 把界面从头理一遍时发现，凡是手写的规范无一例外地陈了 ——
   * 它们描述的是「写文档那天」的样子。一份说错了的规范比没有规范更糟：
   * 读的人会理直气壮地照它去对齐。
   */
  ["scripts/gen-ui-spec.py", ["docs/technical/design/规范-字体.md",
                              "docs/technical/design/规范-版面.md",
                              "docs/technical/design/规范-组件.md"]],
  /*
   * 行为规格（状态迁移 + 拒绝规则）。此前只有 `glossary.test.ts` 在数条数，
   * 而那条断言在全量 vitest 里 —— 全量不挂闸门，等于没人守。
   * 本次给 mock 补两条手机号拒绝规则时它就红了，靠的是我自己跑全量才看见。
   */
  ["scripts/gen-behaviour-spec.mjs", ["docs/api/后端验收清单.md"]],
  /*
   * H2 测试库的两份 schema。**它们此前完全无人看守** —— 而漏生成的症状
   * 与真因毫不相干：2026-08-30 有人改完进销存 DDL 没重生成，
   * 12 个测试全红在 `Column "supplier_no" not found`（表在、列不在），
   * 没有任何东西指向「产物陈了」。
   *
   * 第三个元素是**命令行参数**，为这个生成器加的：
   * `gen-test-schema.py [输出文件] [迁移源目录]`，不给参数只写平台那一份。
   * 于是「跑了生成器」与「生成了我要的那份」是两件事 ——
   * 有人跑了不带参数的那条，看到「wrote … 161 tables」以为好了，
   * 而进销存那份一个字没变。**登记要按调用登记，不是按脚本登记。**
   */
  ["backend/scripts/gen-test-schema.py",
   ["backend/shop-app/src/test/resources/schema-test.sql"]],
  ["backend/scripts/gen-test-schema.py",
   ["backend/shop-app/src/test/resources/db/inventory-h2/V1__inventory_baseline.sql"],
   ["backend/shop-app/src/test/resources/db/inventory-h2/V1__inventory_baseline.sql",
    "backend/shop-inventory/src/main/resources/db/inventory"]],
];

const sha = (p) => (existsSync(p) ? createHash("sha1").update(readFileSync(p)).digest("hex") : "∅");

/*
 * ⚠️ **跑完要把文件还原。**
 *
 * 这里的判据是「跑一遍生成器，产物变不变」—— 而跑生成器就是**真的覆盖文件**。
 * 不还原的话有两个后果，第二个更隐蔽：
 *
 *   1. 它改了你的工作区。在这个共享目录里，那是最该避免的那件事 ——
 *      别人 `git status` 会看到几份自己没动过的文件。
 *   2. **第二遍必绿。** `before` 取的是当前文件的 SHA，而第一遍已经把它覆盖成
 *      最新产出了，于是第二遍比的是「刚生成的」和「再生成一次的」，永远相等。
 *      也就是说**这道闸门每棵树只能用一次**：pre-push 里每次新开 GATE_WT 所以没事，
 *      而任何人手工跑它来「验一下」，第二遍拿到的绿是假的。
 *      2026-08-27 我自己就是这么被骗过一次：第一遍红（没看清），第二遍绿，
 *      于是拿着「我验过了」去推，被 pre-push 当场拦下。
 *      **一道会给假绿的闸门比没有闸门更危险** —— 它让人拿着假证据去做下一步。
 *
 * 还原用内容不用 SHA：SHA 只够判断变没变，还不回去。
 */
const snapshot = (p) => (existsSync(p) ? readFileSync(p) : null);
const restore = (p, buf) => {
  if (buf === null) { if (existsSync(p)) rmSync(p); return; }
  writeFileSync(p, buf);
};

const drifted = [];
for (const [script, outputs, argv = []] of GENERATORS) {
  // 同一个脚本可以登记多次（不同参数产出不同的份），标签要分得开
  const label = argv.length ? `${script} ${argv[0]}` : script;
  const paths = outputs.map((o) => join(ROOT, o));
  const kept = paths.map(snapshot);
  const before = paths.map(sha);
  try {
    // 生成器不限语言：UI 标准库是 python，其余是 node
    const runner = script.endsWith(".py") ? "python3" : "node";
    execFileSync(runner, [join(ROOT, script), ...argv], { cwd: ROOT, stdio: "pipe" });
  } catch (e) {
    paths.forEach((p, i) => restore(p, kept[i]));
    /*
     * **把真实原因带出来。** `stdio: "pipe"` 把生成器的 stderr 收进了
     * `e.stderr`，而这里原先只打 `e.message` 的第一行 ——
     * 那一行是「Command failed: node …/gen-api-detail.mjs」，
     * 等于说了一句「它挂了」然后把为什么扣下。
     * 最常见的那个原因还特别好认：新开的 worktree 里没有 node_modules
     * （它是 gitignore 的），而 gen-api-detail / gen-model-align 都 import yaml。
     */
    const lines = String(e.stderr ?? "").split("\n").map((l) => l.trim()).filter(Boolean);
    // 首行常是 `node:internal/...:301` 这种堆栈头 —— 挑真正说事的那一条
    const why = lines.find((l) => /Error:|ERR_[A-Z_]+|Cannot find/.test(l))
      ?? lines[0] ?? e.message.split("\n")[0];
    console.error(`✗ ${label} 跑不起来：${why.trim()}`);
    if (/ERR_MODULE_NOT_FOUND|Cannot find module/.test(why)) {
      console.error("  多半是这棵树里没有 node_modules（它不进 git）——");
      console.error("  裸 worktree 里跑不了这道闸门。pre-push 会把主仓的软链过去，手工跑要自己链。");
    }
    process.exit(1);
  }
  outputs.forEach((o, i) => {
    /*
     * **先问「它存在吗」，再问「它变了吗」。**
     *
     * sha() 对缺失文件返回 `∅`，于是「跑前不存在、跑后也不存在」会比成相等 ——
     * 一条永远绿的登记。这个坑本文件头部记着已经踩过一次
     * （openapi-c.yaml 从来没存在过，白白列了很久），但当时只是删掉了那一行，
     * 没有任何东西阻止它再来一次。现在阻止了。
     *
     * 它同时守住另一半：**生成器跑了、但没写出这份产物**。
     * 那正是「跑了」与「跑到了」的差别，而缺参数、写错路径、
     * 提前 return 都会长成这个样子，且全都退出码 0。
     */
    if (!existsSync(paths[i])) {
      console.error(`✗ ${label} 跑完之后 ${o} 仍然不存在。`);
      console.error("  要么这份产物的路径写错了（那这条登记从来没守住过任何东西），");
      console.error("  要么生成器根本没写它 —— 缺参数 / 提前 return 都长这样，且退出码 0。");
      process.exit(1);
    }
    if (sha(paths[i]) !== before[i]) drifted.push(`${o}   ← ${label}`);
  });
  // 判完立刻还原：这道闸门只回答「对不对」，不替谁改文件
  paths.forEach((p, i) => restore(p, kept[i]));
}

if (!drifted.length) {
  console.log(`✓ ${GENERATORS.length} 个生成器的产物都是最新的`);
  process.exit(0);
}

console.error("✗ 这些生成物与源码对不上了（跑一次生成器就会变）：");
for (const d of drifted) console.error(`    ${d}`);
console.error("\n  修：跑一遍对应的生成器，把产物一起提交。");
console.error("  清单的价值全在「说的是真话」—— 漏了的那几条会让读的人理直气壮地按它去对齐。");
/*
 * ⚠️ **检出漂移一律非零退出，不再看有没有 `--check`。**
 *
 * 这里原本是 `process.exit(argv.includes("--check") ? 1 : 0)` ——
 * 不带 `--check` 时**打印红、退出绿**。而自动化只读退出码：
 * `node scripts/check-generated-docs.mjs && 下一步` 会把漂移直接跨过去，
 * 屏幕上那几行 ✗ 谁也不会去看。
 *
 * 「只想看看别拦我」这个诉求仍然成立，但那是调用方的事（`|| true`），
 * 不该由被调方替它把结论改成绿的。**一个工具发现了问题却在退出码里否认，
 * 比它没检查更坏** —— 它给了「我跑过了」这句话一个假的依据。
 *
 * `--check` 保留接受（pre-push 一直这么调），只是不再决定退出码。
 */
process.exit(1);
