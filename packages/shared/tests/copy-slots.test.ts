// 短位上的文案不许放两件事。
//
// **背景**：2026-09-09 逐条量了三端 3070 条中文词条，61 条落在这个形状 ——
// 先说状态，再拿逗号或句号接一句「你接下来该怎么做」：
//
//     还没有会员。有人在这里买过一单，就会出现在这里
//     还没有供应商，输入名称即可建一个
//     区划没加载出来，可以重试或直接手填
//
// 机制不是有人话多，是**槽在那儿没人用**：`sh-empty` 有 text / tip / #action
// 三个槽，73 个调用点里传 `tip` 的是 0 个。「下一步」无处可去，就挤进了第一句后面。
//
// 所以这道闸拦的是**位置**，不是长度本身：空态 / 占位 / 吐司 / 标题 / 状态
// 这五种位置在界面上就该是一个词或一个短语；要说下一步，用它自己的槽。
// 提示位（`*Hint` / `*Tip` / `*Body`）不在管辖内 —— 那儿本来就是用来解释的。
//
// **只管中文**。这不是省事：句读信号翻译之后就不成立了 ——
// 英文的 `e.g. Block 3, Unit 2` 里句点后面跟着词（36 条全是这种误报），
// 阿语的 `؛` 是正常的分句符不是句末（16 条同理）。
// 三语的键集由 `i18n-parity` 保证一致，中文是这批文案的源语言。
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BASELINE = join(ROOT, "known-long-slot-copy.txt");

const LOCALES: [string, string][] = [
  ["c-app", "c-app/src/i18n/locale/zh-CN.ts"],
  ["b-app", "b-app/src/i18n/locale/zh-CN.ts"],
  ["ops-web", "ops-web/lib/i18n/messages/zh.ts"],
];

/**
 * 位置由**叶名**判定。
 *
 * ⚠️ **顺序要紧，而且这张表漏一档就等于那一档不设防**：第一版没有「状态」这一档，
 * `myMembership.off`（「你已关掉这家店的消息」）就掉进了「正文」，
 * 而正文不在管辖内 —— 它正是提出这次整改的人举的例子，却没被清单收进去。
 * 「状态」写在最前面，否则 `off` 会先被按钮那条的 `del|ok` 之外的规则抢走。
 */
const ROLES: [string, RegExp][] = [
  ["状态", /^(on|off|enabled|disabled|active|inactive|opened|closed|paused|current)$/],
  ["提示", /(hint|Hint|tip|Tip|desc|Desc|note|Note|help|why|intro|explain|sub$|Sub$|body$|Body$|lead|Lead|warn|Warn|rule|Rule)/],
  ["空态", /(empty|Empty)/],
  ["占位", /(placeholder|Placeholder|Ph$|ph$)/],
  ["吐司", /(toast|Toast|Done$|Ok$|Saved$|Failed$|Fail$|Err|msg$|Msg$)/],
  ["按钮", /(btn|Btn|action|confirm$|cancel|submit$|save$|retry|apply$|goto|add$|edit$|del(ete)?$|more$|ok$)/],
  ["标题", /(title|Title|name$|label|Label|tab$|Tab$|heading|nav)/],
];
const SHORT = new Set(["空态", "占位", "吐司", "标题", "状态"]);

function role(key: string): string {
  const leaf = key.split(".").at(-1)!;
  for (const [name, re] of ROLES) if (re.test(leaf)) return name;
  return "正文";
}

/** 把 `a: { b: "x" }` 摊平成 `a.b`。行内多个键是常态（`title: "…", empty: "…" }`）。 */
function flatten(file: string): Map<string, string> {
  const out = new Map<string, string>();
  const stack: [number, string][] = [];
  let depth = 0;
  for (const raw of readFileSync(file, "utf8").split("\n")) {
    const line = raw.replace(/\/\/.*$/, "");
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*\{/g)) stack.push([depth, m[1]!]);
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*"((?:[^"\\]|\\.)*)"/g)) {
      out.set([...stack.map(([, n]) => n), m[1]!].join("."), m[2]!);
    }
    depth += (line.match(/\{/g)?.length ?? 0) - (line.match(/\}/g)?.length ?? 0);
    while (stack.length && stack[stack.length - 1]![0] >= depth) stack.pop();
  }
  return out;
}

const cjk = (s: string) => (s.match(/[一-鿿]/g) ?? []).length;

/** 汉字上限。实测短位 p99 = 13 字，留三档余量取 16 —— 超过它的一定是接了一整句。 */
const MAX = 16;

const known = new Set(
  existsSync(BASELINE)
    ? readFileSync(BASELINE, "utf8").split("\n").map((l) => l.trim())
        .filter((l) => l && !l.startsWith("#"))
    : [],
);

const slots = LOCALES.flatMap(([app, rel]) =>
  [...flatten(join(ROOT, rel))]
    .filter(([k]) => SHORT.has(role(k)))
    .map(([k, v]) => ({ app, key: k, text: v, id: `${app} ${k}` })),
);

/**
 * 角色表的哨兵。
 *
 * **这条是消融逼出来的**：把 `["状态", …]` 那一行从表里删掉，上面三条断言全绿 ——
 * 一条 17 字、两截的 `myMembership.off` 就那么过去了。也就是说这道闸的成立
 * 完全押在「ROLES 这张表是全的」上，而在此之前**没有任何东西在守这张表**。
 * 那正是这次整改里真实发生过的事：第一版清单没有「状态」这一档，
 * 提出整改的人举的那个例子反而没被清单收进去。
 *
 * 所以每一档钉一个真实存在的键。删档、改序、正则写窄，这里立刻红。
 */
const CANARY: [string, string][] = [
  ["myMembership.off", "状态"],
  ["myMembership.on", "状态"],
  ["members.empty", "空态"],
  ["review.contentPh", "占位"],
  ["store.loadFailed", "吐司"],
  ["myMembership.title", "标题"],
  ["myMembership.hint", "提示"],   // 提示位不在管辖内，判错会让整片解释性文案被误伤
  ["order.providedBy", "正文"],
];

describe("短位文案", () => {
  it("角色表每一档都还在（删一档 = 那一档不设防）", () => {
    const wrong = CANARY.filter(([k, want]) => role(k) !== want)
      .map(([k, want]) => `${k}：期望「${want}」，实得「${role(k)}」`);
    expect(wrong, `角色判定变了：\n${wrong.join("\n")}`).toEqual([]);
  });

  // 分类器坏掉时会一条都扫不到，而「0 条违规」和「全都合规」在输出上一模一样。
  // 实测 456 条，钉一个下限：少于 300 说明摊平或角色判定出了问题，而不是文案变好了。
  it("扫到的短位够多（否则下面两条是空转）", () => {
    expect(slots.length).toBeGreaterThan(300);
  });

  it("不许有第二句 —— 要说下一步，用它自己的槽（sh-empty 的 tip / 输入框下面那行）", () => {
    const bad = slots
      .filter((s) => /。./.test(s.text) || s.text.includes("；"))
      .filter((s) => !known.has(s.id))
      .map((s) => `${s.id}  ${s.text}`);
    expect(bad, `短位上写了两句：\n${bad.join("\n")}`).toEqual([]);
  });

  it(`不许超过 ${MAX} 个汉字 —— 逗号接一整句「怎么办」，与句号是同一件事`, () => {
    const bad = slots
      .filter((s) => cjk(s.text) > MAX)
      .filter((s) => !known.has(s.id))
      .map((s) => `${s.id}  ${cjk(s.text)} 字  ${s.text}`);
    expect(bad, `短位超长：\n${bad.join("\n")}`).toEqual([]);
  });

  it("名单不许锈：已经改好的要从基线里删掉", () => {
    const live = new Set(
      slots.filter((s) => /。./.test(s.text) || s.text.includes("；") || cjk(s.text) > MAX)
        .map((s) => s.id),
    );
    const stale = [...known].filter((k) => !live.has(k));
    expect(stale, `这些已经不违规了，从 known-long-slot-copy.txt 里删掉：\n${stale.join("\n")}`).toEqual([]);
  });
});
