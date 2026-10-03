import { describe, expect, it } from "vitest";
import { parsePastedAddress } from "../src/utils/address-paste";

/**
 * 粘贴识别。**这一组全是行为测试** —— 解析这种东西，唯一说得清的验证
 * 就是把真实会被粘进来的字符串喂进去、看它拆成什么。
 *
 * <p>贯穿全组的一条：**认不出来就留空，绝不猜**。
 * 一个猜错的收货人姓名会静默寄给别人；空着的格子用户自己会填。
 * 后者只是麻烦，前者是事故。
 */
describe("粘贴识别收货信息", () => {
  it("★★★ 最常见的那种：姓名 + 手机 + 一整条地址", () => {
    const r = parsePastedAddress("张三 13800138000 浙江省杭州市西湖区阳光里小区3幢2单元601")!;
    expect(r.name).toBe("张三");
    expect(r.phone).toBe("13800138000");
    expect(r.province).toBe("浙江省");
    expect(r.city).toBe("杭州市");
    expect(r.district).toBe("西湖区");
    expect(r.detail).toBe("阳光里小区");
    expect(r.houseNo).toBe("3幢2单元601");
  });

  it("★★★ 一整串没有分隔符也要能拆 —— 直接喂 splitRegion 会把「张三浙江省」当省名", () => {
    const r = parsePastedAddress("张三13800138000浙江省杭州市西湖区阳光里小区3幢601")!;
    expect(r.name).toBe("张三");
    expect(r.phone).toBe("13800138000");
    expect(r.province).toBe("浙江省");
    expect(r.detail).toBe("阳光里小区");
  });

  it("★★ 带字段标签的（从别的订单复制来的多半是这种）", () => {
    const r = parsePastedAddress("收货人：李四 联系电话：13900139000 地址：浙江省杭州市余杭区未来科技城8幢502")!;
    expect(r.name).toBe("李四");
    expect(r.phone).toBe("13900139000");
    expect(r.district).toBe("余杭区");
    expect(r.houseNo).toBe("8幢502");
  });

  it("★★ 手机号里夹空格/横杠、以及全角数字", () => {
    expect(parsePastedAddress("张三 138 0013 8000 浙江省杭州市西湖区文一西路1号")!.phone)
      .toBe("13800138000");
    expect(parsePastedAddress("张三 138-0013-8000 浙江省杭州市西湖区文一西路1号")!.phone)
      .toBe("13800138000");
    expect(parsePastedAddress("张三 １３８００１３８０００ 浙江省杭州市西湖区文一西路1号")!.phone)
      .toBe("13800138000");
  });

  it("★★★ 手机号要查号段 —— 只认 11 位数字会把假号收进地址簿", () => {
    /*
     * 00000000000 不是手机号，不该被摘进 phone。
     *
     * 号码刻意放在**末尾**：放在姓名与地址之间的话，这一条会顺带考到
     * 「姓名窗口有多宽」——那是另一件事，混在一条用例里，红了也说不清是哪半边错。
     */
    const r = parsePastedAddress("张三 浙江省杭州市西湖区文一西路1号 00000000000")!;
    expect(r.phone).toBe("");
    expect(r.name, "别的字段照样要认出来").toBe("张三");
  });

  it("★★ 姓名与地址之间夹了括号也要能认", () => {
    const r = parsePastedAddress("张三（13800138000）浙江省杭州市西湖区文一西路1号")!;
    expect(r.name).toBe("张三");
    expect(r.phone).toBe("13800138000");
    expect(r.province).toBe("浙江省");
  });

  it("★★★ 门牌只在强信号上切：`号` 与 `楼` 不算", () => {
    /*
     * 「文一西路100号」里的「100号」是路名门牌、是地址主体的一部分。
     * 切在那里会把地址拦腰截断，而用户很可能不会注意到 ——
     * 直到快递员打电话。
     */
    const r = parsePastedAddress("张三 13800138000 浙江省杭州市西湖区文一西路100号")!;
    expect(r.detail).toBe("文一西路100号");
    expect(r.houseNo).toBe("");
  });

  it("★★★ 拆不出省市区时姓名留空，不拿第一个词当名字", () => {
    /*
     * 姓名是靠「地址从哪儿开始」反推的。反推不出来时宁可空着 ——
     * 把「阳光里小区」当成收货人姓名，比空着糟得多。
     */
    const r = parsePastedAddress("13800138000 阳光里小区3幢601")!;
    expect(r.name).toBe("");
    expect(r.phone).toBe("13800138000");
    expect(r.region).toBe("");
  });

  it("★★★ 什么都认不出来时返回 null，而不是一个全空的对象", () => {
    // 两者混为一谈的话，调用方没法区分「认出来了但都是空」与「没认出来」
    expect(parsePastedAddress("")).toBeNull();
    expect(parsePastedAddress("   ")).toBeNull();
    expect(parsePastedAddress("你好啊")).toBeNull();
  });

  it("★★ 只有手机号也算认出来了 —— 省一次输入就是有价值的", () => {
    const r = parsePastedAddress("13800138000")!;
    expect(r.phone).toBe("13800138000");
    expect(r.name).toBe("");
  });

  it("★★★ 一个字都不给坐标 —— 它不是选点页的替代", () => {
    /*
     * 粘贴出来的地址与微信地址簿导入的一样：只有字，没有经纬度。
     * 返回类型里连坐标字段都没有，从形状上就说明了这件事。
     */
    const r = parsePastedAddress("张三 13800138000 浙江省杭州市西湖区阳光里小区3幢601")!;
    expect(Object.keys(r)).not.toContain("latE6");
    expect(Object.keys(r)).not.toContain("lngE6");
  });
});
