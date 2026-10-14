/**
 * 校验注解要**真的会触发**，不是写上就行。
 *
 * Bean Validation 在 Spring 里有两条独立的接线，缺哪条都是**静默失效** ——
 * 注解还在源码里、编译通过、接口 200，只是那条规则从来没跑过：
 *
 *  ① `@RequestBody` 上的 DTO：DTO 里的 `@Pattern` / `@NotBlank` 要生效，
 *     **方法参数必须带 `@Valid`**。少了它，脏数据一路进库。
 *  ② 直接写在**方法参数**上的约束（`@RequestParam @Pattern ...`）：
 *     要生效，**类上必须有 `@Validated`**。少了它同样一声不响。
 *
 * 为什么值得一道闸：`Phones.CN_MOBILE` 这条手机号格式判据落地时，
 * 唯一能证明它生效的证据是**一个场景测试被迫换了号码**（126 前缀被拒了）。
 * 那是间接证据 —— 把 `@Valid` 删掉，那个测试照样绿，因为它用的是合法号。
 * 「加了 @Pattern 却不生效」这件事，事后从任何一个绿色的测试里都看不出来。
 *
 * 这道闸不验规则对不对（那是各自测试的事），只验**接线在不在**。
 */
import { describe, expect, it } from "vitest";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "../../..");
const SRC = ["backend/shop-app/src/main", "backend/shop-core/src/main", "backend/shop-base/src/main"];

const CONSTRAINT = /@(?:jakarta\.validation\.constraints\.)?(NotBlank|NotNull|NotEmpty|Pattern|Size|Min|Max|Email|Positive|PositiveOrZero|Negative|Digits|DecimalMin|DecimalMax|AssertTrue|AssertFalse|Past|Future)\b/;

function javaFiles(): string[] {
  try {
    return execFileSync("find", [...SRC.map((d) => join(ROOT, d)), "-name", "*.java"],
      { encoding: "utf8" }).split("\n").filter(Boolean);
  } catch {
    return [];
  }
}

const FILES = javaFiles().map((f) => ({ path: f.replace(ROOT + "/", ""), src: readFileSync(f, "utf8") }));

/** 从 `from` 起找第一个 open，返回到与它配对的 close 为止的那一段 */
function balanced(src: string, from: number, open: string, close: string): string {
  const i = src.indexOf(open, from);
  if (i < 0) return "";
  let depth = 0;
  for (let j = i; j < src.length; j++) {
    if (src[j] === open) depth++;
    else if (src[j] === close && --depth === 0) return src.slice(i, j + 1);
  }
  return "";
}

/**
 * 每个文件里声明了哪些类型、其中哪些带约束。
 *
 * **必须按文件作用域解析，不能按简单名全局归并。** 第一版归并了，于是
 * `LoginReq` 这个名字在三个 controller 里各有一份（`OpsPlatform` 与 `MpUser`
 * 的带 `@NotBlank`、`BizAuth` 的一个约束都没有），`BizAuth` 那处被误报成
 * 「少 @Valid」。`RegisterReq` / `RejectReq` / `CreateReq` 同样重名。
 *
 * 加上更早那次「往后取 1500 字符」把邻居 record 的约束算到隔壁头上 ——
 * 同一道闸在写的过程中按距离、按名字各错了一次。都是没看结构。
 */
type Scope = { declared: Set<string>; constrained: Set<string> };

function scopeOf(src: string): Scope {
  const declared = new Set<string>();
  const constrained = new Set<string>();
  for (const m of src.matchAll(/\brecord\s+([A-Z]\w*)\s*\(/g)) {
    declared.add(m[1]);
    if (CONSTRAINT.test(balanced(src, m.index!, "(", ")"))) constrained.add(m[1]);
  }
  for (const m of src.matchAll(/\bclass\s+([A-Z]\w*)[^{;]*\{/g)) {
    declared.add(m[1]);
    if (CONSTRAINT.test(balanced(src, m.index!, "{", "}"))) constrained.add(m[1]);
  }
  return { declared, constrained };
}

const SCOPES = new Map(FILES.map((f) => [f.path, scopeOf(f.src)]));

/**
 * 这个类型带约束吗。先在**用它的那个文件**里找（这个仓库的请求体几乎都是
 * controller 内的嵌套 record）；本文件没声明才看别处 —— 而别处若有同名的多份，
 * 无从判断是哪一个，宁可放过也不误报。
 */
function isConstrained(usingFile: string, type: string): boolean {
  const own = SCOPES.get(usingFile);
  if (own?.declared.has(type)) return own.constrained.has(type);
  const owners = [...SCOPES.values()].filter((s) => s.declared.has(type));
  if (owners.length !== 1) return false;
  return owners[0].constrained.has(type);
}

describe("Bean Validation：注解要真的会触发", () => {
  it("★★★ 带约束的 DTO 用作 @RequestBody 时，参数必须有 @Valid —— 少了它规则一次都不跑", () => {
    const total = [...SCOPES.values()].reduce((n, s) => n + s.constrained.size, 0);
    expect(total, "一个带约束的 DTO 都没找到，说明解析器坏了而不是代码干净").toBeGreaterThan(0);
    const bad: string[] = [];
    for (const { path, src } of FILES) {
      for (const m of src.matchAll(/(?:@[\w.]*Valid(?:ated)?\s+)?@RequestBody\s+(?:@[\w.]*Valid(?:ated)?\s+)?(?:final\s+)?([\w.]+)/g)) {
        const type = m[1].split(".").pop()!;
        if (!isConstrained(path, type)) continue;
        if (/Valid/.test(m[0])) continue;
        bad.push(`${path} ${type}`);
      }
    }
    // 棘轮：清单里的是已知欠账（接上会改 22 个线上端点的报错行为，得逐条看调用方）。
    // 这道闸拦的是**新增**，同时催清单变短 —— 修好了不删，那个位置就永远免检。
    const known = new Set(
      readFileSync(join(ROOT, "known-unwired-validation.txt"), "utf8")
        .split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#")),
    );
    const fresh = [...new Set(bad)].filter((b) => !known.has(b)).sort();
    const fixed = [...known].filter((k) => !bad.includes(k)).sort();
    expect(fresh, `新出现的「注解不会触发」：\n  ${fresh.join("\n  ")}`).toEqual([]);
    expect(fixed, `这几条已经接上了，从 known-unwired-validation.txt 里删掉：\n  ${fixed.join("\n  ")}`).toEqual([]);
  });

  it("★★★ 约束直接写在方法参数上时，类上必须有 @Validated —— 少了它同样静默失效", () => {
    const bad: string[] = [];
    for (const { path, src } of FILES) {
      if (!/@(?:Get|Post|Put|Patch|Delete|Request)Mapping/.test(src)) continue;
      const onParam = [...src.matchAll(/@(?:RequestParam|PathVariable|RequestPart)[^)]*\)?\s*(@[\w.]*(?:NotBlank|NotNull|Pattern|Size|Min|Max|Email)\b)/g)];
      if (!onParam.length) continue;
      if (/@Validated\b/.test(src)) continue;
      const line = src.slice(0, onParam[0].index).split("\n").length;
      bad.push(`${path}:${line} 方法参数上有 ${onParam[0][1]}，但类上没有 @Validated`);
    }
    expect(bad, `这些类的方法参数约束不会触发：\n  ${bad.join("\n  ")}`).toEqual([]);
  });
});
