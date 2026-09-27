/**
 * 「自动生成」顺带挑好的商品参数，落到表单里那一步（§2.B）。
 *
 * <p>两条判据都是**保护商家已有输入**与**不让编造的值落进来**：
 * 模型挑的是草稿，而草稿最容易犯的两个错就是「盖掉人已经选好的」和「选一个不存在的值」。
 * 后端返回前已经核验过一遍，端上再拦一次 —— 因为端上的模板是另取的一份（loadProps），
 * 两边可能差着一个类目。
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

  it("★★★ 不在候选里的值丢掉 —— 模型编一个「冷鲜」出来，看起来像正常选项", () => {
    const p = setup();
    const n = p.applyParamPicks([
      { dimNo: "SD_STORE_COND", code: "STGICE", label: "冷鲜" },
      { dimNo: "SD_NOPE", code: "X", label: "某值" },
    ]);
    expect(n).toBe(0);
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
});
