/**
 * 「自动生成」顺带挑好的商品参数，落到表单里那一步（§2.B）。
 *
 * <p>核心判据是**保护商家已有输入**：模型挑的是草稿，最该拦的是「盖掉人已经选好的」。
 * 2026-10-06（AC4）起**放开了模板/候选过滤**——识别到的参数即使不在本类目模板里
 * （如「重量 2.5kg」「原产地 云南昭通」）也作自由参数落下,label 为准;模板是推荐不是上限。
 * 它落的是**可删**的草稿（AC1 的 removeParam 兜底）,且识别全程绝不自动上架,所以
 * 「宁可多落、让商家删」好过「识别得准却静默丢掉」。
 */
import { describe, expect, it, vi } from "vitest";
import { ref } from "vue";

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/api", () => ({ api: { mSpecProps: vi.fn(async () => []) } }));
vi.mock("@/utils/spec-override", () => ({ buildSpecOverride: vi.fn(() => ({})) }));

import { useGoodsParams } from "@/pages/goods-edit/params";

/** 生鲜那一支的模板（V349 给 CAT120 绑的那几个） */
const DIMS = [
  {
    templateNo: "SD_STORE_COND", name: "储存条件",
    options: [{ code: "STGROOM", label: "常温" }, { code: "STGCHILL", label: "冷藏 0~5℃" }],
  },
  {
    templateNo: "SD_TASTE", name: "口感风味",
    options: [{ code: "TSTSWEET", label: "清甜" }, { code: "TSTCRISP", label: "脆爽" }],
  },
  {
    templateNo: "SD_ORIGIN", name: "产地",
    options: [
      { code: "ORIGINLOCAL", label: "本地" }, { code: "ORIGINCN", label: "国产" },
      { code: "ORIGINIMP", label: "进口" },
    ],
  },
];

function setup() {
  const p = useGoodsParams(ref("CAT120"));
  p.propDims.value = DIMS as unknown as typeof p.propDims.value;
  return p;
}

describe("自动生成挑好的参数", () => {
  it("★★★ 空着的填上、已选的不动 —— 一键覆盖掉他自己选的值没有撤销", () => {
    const p = setup();
    // 商家已经自己选了「常温」
    p.paramValues.value = {
      SD_STORE_COND: { dimNo: "SD_STORE_COND", name: "储存条件", code: "STGROOM", label: "常温" },
    } as typeof p.paramValues.value;

    const n = p.applyParamPicks([
      { dimNo: "SD_STORE_COND", code: "STGCHILL", label: "冷藏 0~5℃" },
      { dimNo: "SD_TASTE", code: "TSTSWEET", label: "清甜" },
    ]);

    expect(n, "填进去的条数要与实际改动一致 —— 提示语用的就是它").toBe(1);
    expect(p.paramValues.value.SD_STORE_COND.label, "他自己选的被盖掉了").toBe("常温");
    expect(p.paramValues.value.SD_TASTE.label).toBe("清甜");
  });

  it("★★★ 识别值不在模板/候选里 → 作自由参数落下，不再丢弃（AC4）", () => {
    // 行为变更:旧版把「维度不在模板」「值不在候选」两类都丢掉,于是识别得越准落得越少。
    // 现在模板是推荐不是上限——「这袋面 2.5kg」「原产地 云南昭通」都该落下(可删,见 AC1)。
    const p = setup();
    const n = p.applyParamPicks([
      { dimNo: "SD_STORE_COND", code: "STGICE", label: "冷鲜" }, // 模板维度,但值不在候选
      { dimNo: "SD_WEIGHT", name: "重量", label: "2.5kg" },      // 维度根本不在本类目模板
    ]);
    expect(n).toBe(2);
    // 模板维度、非候选值:落 label,带回传的 code
    expect(p.paramValues.value.SD_STORE_COND.label).toBe("冷鲜");
    expect(p.paramValues.value.SD_STORE_COND.code).toBe("STGICE");
    // 非模板维度:用识别带回的 name 命名,label 为准,无 code
    expect(p.paramValues.value.SD_WEIGHT.label).toBe("2.5kg");
    expect(p.paramValues.value.SD_WEIGHT.name).toBe("重量");
    expect(p.paramValues.value.SD_WEIGHT.code).toBeUndefined();
  });

  it("★★ 已填的仍不被识别覆盖 —— 放开过滤不等于放开覆盖", () => {
    const p = setup();
    p.paramValues.value = {
      SD_WEIGHT: { dimNo: "SD_WEIGHT", name: "重量", label: "1kg" },
    } as typeof p.paramValues.value;
    const n = p.applyParamPicks([{ dimNo: "SD_WEIGHT", name: "重量", label: "2.5kg" }]);
    expect(n).toBe(0);
    expect(p.paramValues.value.SD_WEIGHT.label, "商家已填的不动").toBe("1kg");
  });

  it("★★ removeParam 删一项,含不在模板里的孤儿参数（AC1）", () => {
    const p = setup();
    p.paramValues.value = {
      SD_TASTE: { dimNo: "SD_TASTE", name: "口感风味", code: "TSTSWEET", label: "清甜" },
      SD_WEIGHT: { dimNo: "SD_WEIGHT", name: "重量", label: "2.5kg" }, // 孤儿:不在 DIMS 里
    } as typeof p.paramValues.value;
    p.removeParam("SD_WEIGHT");
    expect(p.paramValues.value.SD_WEIGHT, "孤儿参数也能删").toBeUndefined();
    expect(p.paramValues.value.SD_TASTE.label, "没点的不动").toBe("清甜");
    p.removeParam("SD_TASTE");
    expect(Object.keys(p.paramValues.value)).toHaveLength(0);
  });

  it("★★ 没挑出来（空数组）不报错也不改动 —— 模型不可达是常态，不是异常", () => {
    const p = setup();
    p.paramValues.value = {
      SD_TASTE: { dimNo: "SD_TASTE", name: "口感风味", code: "TSTCRISP", label: "脆爽" },
    } as typeof p.paramValues.value;
    expect(p.applyParamPicks([])).toBe(0);
    expect(p.paramValues.value.SD_TASTE.label).toBe("脆爽");
  });

  /*
   * 2026-10-07 的真实报障：「输入 产地：山西运城临猗，弹框里识别到产地，但没有更新到系统」。
   * 值其实写进去了、保存也会带上 —— 只是这一行只画平台候选，他填的那个不在里面，
   * **一枚选中的 chip 都没有**，与「没识别到」长得一模一样。断言画出来的那几枚，不是存了没存。
   */
  it("★★★ 自己填的值要画出来：识别到的产地不在候选里，也得是一枚选中的 chip", () => {
    const p = setup();
    expect(p.applyParamPicks([{ dimNo: "SD_ORIGIN", name: "产地", label: "山西运城临猗" }])).toBe(1);
    const origin = DIMS.find((d) => d.templateNo === "SD_ORIGIN")!;
    const chips = p.paramChips(origin as never);
    expect(chips.map((o) => o.label), "候选之后补上他填的那个").toEqual(["本地", "国产", "进口", "山西运城临猗"]);
    // 选中判据是 label 相等（模板的那几枚都没被选中）
    expect(chips.filter((o) => o.label === p.paramValues.value.SD_ORIGIN?.label)).toHaveLength(1);
  });

  it("值就是候选之一时不重复画", () => {
    const p = setup();
    p.applyParamPicks([{ dimNo: "SD_ORIGIN", name: "产地", code: "ORIGINLOCAL", label: "本地" }]);
    const origin = DIMS.find((d) => d.templateNo === "SD_ORIGIN")!;
    expect(p.paramChips(origin as never).map((o) => o.label)).toEqual(["本地", "国产", "进口"]);
  });

  it("没填值的那一项照旧只画候选", () => {
    const p = setup();
    const origin = DIMS.find((d) => d.templateNo === "SD_ORIGIN")!;
    expect(p.paramChips(origin as never)).toHaveLength(3);
  });
});
