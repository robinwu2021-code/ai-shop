// 两套类型系统的词汇对齐。
//
// 现状：C/B 两端用 `packages/shared/src/types`，平台端有自己的 `ops-web/lib/types`。
// 22 个同名类型里，**5 个的枚举值互不相同** —— 同一个状态机两套词汇。
// 字段不同是正常的（平台看得到 `communityName`，消费者看不到），
// **枚举值不同不是**：后端只会实现一套，一端写进去的值另一端读不出来。
//
// 这个测试不强求立刻统一（ops-web 的字面量散在 20+ 处，改动属于那条线），
// 而是把已知漂移**钉成基线**：
//   · 基线里的分歧 = 已知、已记录、有归属，不报错
//   · 基线外的新分歧 = 立刻失败
// 否则的话，今天数出 5 个，下周就是 8 个，而没人会注意到多出来的那 3 个。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const SHARED = join(ROOT, "packages/shared/src/types");
const OPS = join(ROOT, "ops-web/lib/types");

/** 从一个目录里抽出所有「字符串字面量联合类型」的值集合 */
function enums(dir: string): Record<string, Set<string>> {
  const out: Record<string, Set<string>> = {};
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir)) {
    if (!f.endsWith(".ts") || f.endsWith(".test.ts")) continue;
    const src = readFileSync(join(dir, f), "utf8");
    for (const m of src.matchAll(/export type (\w+)\s*=\s*([^;]+);/g)) {
      const vals = [...m[2]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
      // 只看纯字面量联合：带引用类型的（如 `A | B`）不是词汇表
      if (vals.length >= 2) out[m[1]!] ??= new Set(vals);
    }
  }
  return out;
}

/**
 * 已知漂移基线。
 * key = 类型名，value = 说明「谁是权威 + 怎么映射 + 谁来修」。
 * **加一条基线必须同时在 docs/requirements/需求矩阵-三端.md §七之四 写下映射**，
 * 否则这里就变成了「把问题登记一下然后忘掉」的清单。
 */
const KNOWN_DRIFT: Record<string, string> = {
  TrafficSource:
    "shared 只有 MERCHANT_OWNED/PLATFORM（订单上的归因快照），ops 多 INVITE/CHANNEL" +
    "（运营看的是全部来源）。且 growth 的 AttrSource 是第三套说法（STORE_CODE↔MERCHANT_OWNED、" +
    "INVITER↔INVITE）。三者要并成一套 —— 已在登记表标 MERGE",
  AfterSaleType: "ops 多一个 EXCHANGE（换货）—— 一期不做换货，用「退货重下」代替（C-AS-01）。",
  MerchantStatus:
    "shared: NONE/APPLYING/REJECTED/ACTIVE/SUSPENDED（商家自己看到的）；" +
    "ops: DRAFT/SUBMITTED/REVIEWING/APPROVED/REJECTED（审核流水线）。" +
    "APPROVED 与 ACTIVE 是同一件事的两个名字 —— 接后端前必须并成一个。",
  CampaignType:
    "两端各自的活动类型集合（shared 是商家活动，ops 是平台活动）。" +
    "接后端时要么合并成一张表，要么显式分成 MerchantCampaignType / PlatformCampaignType。",
  CampaignStatus: "shared 有 PAUSED（商家可暂停），ops 有 SCHEDULED（平台可预约生效）。都合理，合并即可。",
  AppealStatus: "ops 的申诉状态；shared 侧叫 ReviewAppealStatus，值域需对齐（UPHELD/REJECTED/PENDING）。",
};

describe("两套类型系统的词汇对齐", () => {
  const S = enums(SHARED);
  const O = enums(OPS);

  it("同名枚举的值集合：新增分歧必须先记进基线", () => {
    if (!Object.keys(O).length) return; // ops-web 不在时跳过（CI 只装两端依赖的场景）
    const unexpected: string[] = [];
    for (const name of Object.keys(S)) {
      if (!O[name]) continue;
      const a = S[name]!;
      const b = O[name]!;
      const same = a.size === b.size && [...a].every((v) => b.has(v));
      if (!same && !(name in KNOWN_DRIFT)) {
        unexpected.push(
          `${name}: shared=[${[...a].sort().join(",")}] ops=[${[...b].sort().join(",")}]`,
        );
      }
    }
    expect(
      unexpected,
      "同一个枚举在两端有不同的值域 —— 后端只会实现一套，一端写进去的值另一端读不出来。\n" +
        "要么统一，要么写进 KNOWN_DRIFT 并在矩阵文档 §七之四 记下映射：\n" +
        unexpected.join("\n"),
    ).toEqual([]);
  });

  it("基线只登记真的还在漂移的项 —— 修好了要从基线里删掉", () => {
    if (!Object.keys(O).length) return;
    const stale = Object.keys(KNOWN_DRIFT).filter((name) => {
      const a = S[name];
      const b = O[name];
      if (!a || !b) return false; // 一端没有这个类型，不算「已修好」
      return a.size === b.size && [...a].every((v) => b.has(v));
    });
    expect(stale, `以下已经对齐了，请从 KNOWN_DRIFT 删除：${stale.join(", ")}`).toEqual([]);
  });
});

describe("端点前缀：文档与代码不许再对不上", () => {
  // 这条断言的来历：ADR-007 原定 B 端走 `/mb/**`，后端独立实现成了 `/biz/**`，
  // **发现时两边一条也对不上**。ADR 抬头改过了，但功能清单与 TDD 里仍写着 `/mb/**` ——
  // 一份过期文档比没有文档更糟：照着它写后端，46 个端点会全部落空。
  const read = (app: string) => readFileSync(join(ROOT, app, "src/api/endpoints.ts"), "utf8");
  const paths = (src: string) => [...src.matchAll(/path:\s*"([^"]+)"/g)].map((m) => m[1]!);

  /**
   * `/common/**` 是**第三类端点**：跨端公共元数据（后端 `CommonMetaController`）。
   *
   * 行业/主体类型/支付通道这类主数据 C 端与 B 端要的是同一份，
   * 给它造一个 `/biz/master-data` 别名只会得到两条路径服务同一件事 ——
   * 而两条路径迟早返回不一样的东西。所以它显式豁免，不是漏网。
   */
  const SHARED_PREFIX = "/common/";

  it("C 端全部 /mp/**，B 端全部 /biz/**（跨端公共元数据 /common/** 除外）", () => {
    const bad: string[] = [];
    for (const [app, prefix] of [
      ["c-app", "/mp/"],
      ["b-app", "/biz/"],
    ] as const) {
      for (const p of paths(read(app))) {
        if (p.startsWith(SHARED_PREFIX)) continue;
        if (!p.startsWith(prefix)) bad.push(`${app}: ${p}（应以 ${prefix} 开头）`);
      }
    }
    expect(bad, `端点前缀不一致：\n${bad.join("\n")}`).toEqual([]);
  });

  it("文档里不许把 /mb/** 当成现行口径", () => {
    // 允许**标注为历史**的提法（「原定 /mb/**，后端实现成了 /biz/**」）——
    // 那是有用的上下文；不允许的是把它当现行前缀写在待办或设计里。
    const docs = [
      "docs/requirements/B端功能清单.md",
      "docs/requirements/C端功能清单.md",
      "docs/requirements/平台端功能清单.md",
      "docs/technical/TDD-b-app.md",
    ];
    const HISTORICAL = /原定|已废弃|历史|曾经|~~/;
    const bad: string[] = [];
    for (const d of docs) {
      readFileSync(join(ROOT, d), "utf8")
        .split("\n")
        .forEach((line, i) => {
          if (line.includes("/mb/") && !HISTORICAL.test(line)) bad.push(`${d}:${i + 1} ${line.trim()}`);
        });
    }
    expect(bad, `以下位置把已废弃的 /mb/** 当成了现行口径：\n${bad.join("\n")}`).toEqual([]);
  });
});

describe("契约四件套：契约 / 端点表 / mock / http 必须一一对应", () => {
  // 少一处的后果各不相同，但都不会在编译期暴露：
  //   · 端点表缺 → 走真后端时抛「未知端点」，mock 下一切正常
  //   · mock 缺 → 开发期该页直接空白
  //   · http 缺 → 切到真后端才发现这个功能没接
  // 逐条比对是唯一可靠的办法 —— 46+60 个方法靠人眼看不出来。
  const read = (app: string, f: string) =>
    readFileSync(join(ROOT, app, "src/api", f), "utf8");

  for (const app of ["c-app", "b-app"]) {
    it(`${app}: 四处齐全`, () => {
      const methods = new Set([...read(app, "contract.ts").matchAll(/^ {2}(\w+)\(/gm)].map((m) => m[1]!));
      const eps = new Set([...read(app, "endpoints.ts").matchAll(/^ {2}(\w+): \{/gm)].map((m) => m[1]!));
      const mockSrc = read(app, "mock.ts");
      const mocks = new Set(
        [...mockSrc.matchAll(/^ {2}(?:async )?(\w+)[(:]/gm)].map((m) => m[1]!),
      );
      const https = new Set([...read(app, "http.ts").matchAll(/^ {2}(\w+):/gm)].map((m) => m[1]!));

      const gaps: string[] = [];
      for (const m of methods) {
        if (!eps.has(m)) gaps.push(`${m}: 端点表缺`);
        if (!mocks.has(m)) gaps.push(`${m}: mock 缺`);
        if (!https.has(m)) gaps.push(`${m}: http 缺`);
      }
      for (const e of eps) if (!methods.has(e)) gaps.push(`${e}: 端点表多出（契约里没有）`);
      expect(gaps, `${app} 契约四件套对不上：\n${gaps.join("\n")}`).toEqual([]);
    });
  }
});
