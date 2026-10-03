import { describe, expect, it } from "vitest";
import { isCompleteRegion, joinRegion, splitRegion } from "../src/utils/region";

/**
 * 价值全在**反例**与**边角**：正例（浙江省杭州市西湖区）任何写法都能过，
 * 真正会出事的是直辖市、自治区、不设区的市，以及「根本拆不动」的存量串。
 */
describe("splitRegion", () => {
  it("普通三级", () => {
    expect(splitRegion("浙江省杭州市西湖区文三路 100 号")).toEqual({
      province: "浙江省",
      city: "杭州市",
      district: "西湖区",
      rest: "文三路 100 号",
    });
  });

  it("直辖市：省与市填同一个值，第二级直接是区", () => {
    // 拆成 province=北京 / city=朝阳区 / district=空 的话，
    // 按市统计会把「朝阳区」当成一个市，报表上凭空多出 16 个城市
    expect(splitRegion("北京市朝阳区建国路 1 号")).toEqual({
      province: "北京市",
      city: "北京市",
      district: "朝阳区",
      rest: "建国路 1 号",
    });
  });

  it("自治区与自治州：后缀比「省」「市」长，最短匹配不能把它们切碎", () => {
    expect(splitRegion("广西壮族自治区南宁市青秀区")).toEqual({
      province: "广西壮族自治区",
      city: "南宁市",
      district: "青秀区",
      rest: "",
    });
    expect(splitRegion("湖北省恩施土家族苗族自治州利川市")).toMatchObject({
      province: "湖北省",
      city: "恩施土家族苗族自治州",
      district: "利川市",
    });
  });

  it("最短匹配：黑龙江省不能被当成「黑龙江省哈尔滨市」一整块", () => {
    expect(splitRegion("黑龙江省哈尔滨市南岗区").province).toBe("黑龙江省");
  });

  it("不设区的地级市：区县为空而不是硬凑一个", () => {
    expect(splitRegion("广东省东莞市南城街道")).toEqual({
      province: "广东省",
      city: "东莞市",
      district: "",
      rest: "南城街道",
    });
  });

  it("带空格的写法（存量地址里最常见的一种）不能把空格拆进值里", () => {
    // 拆出「 杭州市」（前面挂个空格）存进库，按市聚合时它是独立的一档，跟别处永远对不上
    expect(splitRegion("浙江省 杭州市 西湖区 阳光里小区 3 幢")).toEqual({
      province: "浙江省",
      city: "杭州市",
      district: "西湖区",
      rest: "阳光里小区 3 幢",
    });
  });

  it("「小区」不是区县 —— 认出市之后也不能把它当第三级", () => {
    // 吃掉一截后 district=「XX 小区」，按区派单时凭空多出一个不存在的区
    expect(splitRegion("浙江省杭州市 XX 小区 3 栋")).toMatchObject({
      city: "杭州市",
      district: "",
      rest: "XX 小区 3 栋",
    });
    expect(splitRegion("上海市张江高科技园区")).toMatchObject({ district: "", rest: "张江高科技园区" });
  });

  it("拆不动的存量串原样落在 rest，不抛异常", () => {
    expect(splitRegion("XX 小区 3 栋 201")).toEqual({
      province: "",
      city: "",
      district: "",
      rest: "XX 小区 3 栋 201",
    });
    expect(splitRegion("")).toEqual({ province: "", city: "", district: "", rest: "" });
    expect(splitRegion(null)).toEqual({ province: "", city: "", district: "", rest: "" });
  });
});

describe("joinRegion", () => {
  it("直辖市不写成「北京市北京市朝阳区」", () => {
    expect(joinRegion({ province: "北京市", city: "北京市", district: "朝阳区" })).toBe("北京市朝阳区");
  });

  it("缺级也能拼，不出现 undefined", () => {
    expect(joinRegion({ province: "广东省", city: "东莞市" })).toBe("广东省东莞市");
    expect(joinRegion({})).toBe("");
  });

  it("拆了再拼回去，省市区那一段不变", () => {
    const s = "浙江省杭州市西湖区";
    expect(joinRegion(splitRegion(s))).toBe(s);
  });
});

describe("isCompleteRegion", () => {
  it("只有 region 那一串不算填好 —— 三列还是空的", () => {
    expect(isCompleteRegion(splitRegion("随便写点什么"))).toBe(false);
  });

  it("空白不算有值", () => {
    expect(isCompleteRegion({ province: "  ", district: "西湖区" })).toBe(false);
  });

  it("不设区的地级市缺 district，判为不完整 —— 让人再确认一次", () => {
    expect(isCompleteRegion(splitRegion("广东省东莞市南城街道"))).toBe(false);
  });
});
