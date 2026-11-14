import { describe, expect, it } from "vitest";
import { requirePointsNames } from "../src/utils/points-name";

describe("points display names", () => {
  it("uses the supplied name without changing ledger identity or amounts", () => {
    const original = { typeCode: "SYS_TOKEN", name: " 商城积分 ", balance: 25 };
    expect(requirePointsNames([original])).toEqual([{ ...original, name: "商城积分" }]);
    expect(original.name).toBe(" 商城积分 ");
  });
  it.each([undefined, null, "", "  "])("rejects a missing name %s instead of using the code", name => {
    expect(() => requirePointsNames([{ typeCode: "SYS_TOKEN", name }])).toThrow("POINTS_NAME_MISSING");
  });
  it("does not return a partial multi-type list when one name is missing", () => {
    expect(() => requirePointsNames([{ name: "商城积分" }, { name: "" }])).toThrow();
  });
  it("allows a genuine empty list", () => expect(requirePointsNames([])).toEqual([]));
});
