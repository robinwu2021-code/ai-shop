/**
 * TEXT 维度（配料 / 厂名厂址 / 生产许可证 / 执行标准）的录入（TDD-商品属性维度补全 AC6）。
 *
 * <p>与枚举/量纲维度的根本区别：**不入平台值池**。入池只会堆满永不复用的唯一串，
 * 而值池的理由是跨店聚合。所以 `setParamText` 填的字直接成为参数 label、不拿 code、
 * **绝不调 `mAddSpecValue`** —— 后者是入池那条路。消融判据就在这最后一条上。
 */
import { describe, expect, it, vi } from "vitest";
import { ref } from "vue";

const { mAddSpecValue } = vi.hoisted(() => ({
  mAddSpecValue: vi.fn(async () => ({ valueNo: "SV_X", code: "X", label: "x" })),
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@/api", () => ({ api: { mSpecProps: vi.fn(async () => []), mAddSpecValue } }));
vi.mock("@/utils/spec-override", () => ({ buildSpecOverride: vi.fn(() => ({})) }));

import { useGoodsParams } from "@/pages/goods-edit/params";

const TEXT_DIM = { templateNo: "SD_INGREDIENTS", name: "配料", valueType: "TEXT", options: [] };
const ENUM_DIM = {
  templateNo: "SD_STORE_COND", name: "储存条件", valueType: "ENUM",
  options: [{ code: "STGROOM", label: "常温" }],
};

function setup() {
  const p = useGoodsParams(ref("CAT130"));
  p.propDims.value = [TEXT_DIM, ENUM_DIM] as unknown as typeof p.propDims.value;
  return p;
}

describe("TEXT 维度录入", () => {
  it("★★ isTextDim 只认 valueType===TEXT", () => {
    const p = setup();
    expect(p.isTextDim(TEXT_DIM as never)).toBe(true);
    expect(p.isTextDim(ENUM_DIM as never)).toBe(false);
  });

  it("★★★ 填一行字直接成为 label、带 dimNo+name、**不带 code**、不入池", () => {
    const p = setup();
    p.setParamText(TEXT_DIM as never, "  小麦粉、白砂糖、食用盐  ");
    const v = p.paramValues.value.SD_INGREDIENTS;
    expect(v.label, "首尾空白要去掉").toBe("小麦粉、白砂糖、食用盐");
    expect(v.dimNo).toBe("SD_INGREDIENTS");
    expect(v.name).toBe("配料");
    expect(v.code, "TEXT 不入池：code 留空正是「这是快照不是库里一档」的标记").toBeUndefined();
    // 消融点：走入池路径会调 mAddSpecValue。它被调过就说明 TEXT 的「不入池」没成立
    expect(mAddSpecValue, "TEXT 维度绝不调入池接口").not.toHaveBeenCalled();
  });

  it("★★ 清空（填空字符串/纯空白）= 删掉这一项，与 chip 再点一次取消同一口径", () => {
    const p = setup();
    p.setParamText(TEXT_DIM as never, "配料表");
    expect(p.paramValues.value.SD_INGREDIENTS).toBeTruthy();
    p.setParamText(TEXT_DIM as never, "   ");
    expect(p.paramValues.value.SD_INGREDIENTS, "空白视同删").toBeUndefined();
  });
});
