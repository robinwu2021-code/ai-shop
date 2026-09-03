import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pickedAddress, pickedPlace, placeFrom } from "@/shared/address-pick";

/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

/**
 * 取一个函数的函数体。**取不到要让调用方能断言**（返回 null 而不是空串）——
 * 函数被改名之后静默返回空串的话，下面每一条断言都会「通过」，
 * 而被守的那件事一行都没被检查到。
 */
function bodyOf(src: string, signature: string): string | null {
  const at = src.indexOf(signature);
  if (at < 0) return null;
  const rest = src.slice(at);
  const end = rest.indexOf("\n}");
  return end < 0 ? rest : rest.slice(0, end + 2);
}

/**
 * **这一单送到哪儿 ≠ 我的默认收货地址。**
 *
 * <p>它们常常是同一条记录，于是「点一条就 setDefaultAddress，结算页读默认」
 * 是极自然的省事写法 —— 少一个接口、少一处状态，而且**看起来完全正常**。
 * 代价是给父母寄一次东西，从此每一单都预填父母家。
 */
describe("结算页选地址 ≠ 改默认地址", () => {
  const addressPage = code("src/pages/address/index.vue");
  const confirmPage = code("src/pages/order-confirm/index.vue");

  it("★★★ 地址簿的 picking 分支不许改默认地址", () => {
    const body = bodyOf(addressPage, "function pick(");
    expect(body, "地址页没有 pick 函数了 —— 守卫失去了扫描对象，先修守卫再说").not.toBeNull();
    expect(body, "用「改默认」来传「这一单选谁」：长期偏好被一单改写")
      .not.toContain("setDefaultAddress");
  });

  it("★★★ 选中的那条要交回给结算页，而不是写进服务端", () => {
    const body = bodyOf(addressPage, "function pick(");
    expect(body).toContain("pickedAddress.offer");
    expect(body, "交完就返回").toContain("navigateBack");
  });

  it("★★★ 结算页必须接住 —— 不接就等于他白选了一次", () => {
    expect(confirmPage).toContain("pickedAddress.take");
    const body = bodyOf(confirmPage, "onShow(");
    expect(body, "结算页没有 onShow 了 —— 从地址簿返回时这一页不会更新").not.toBeNull();
    expect(body).toContain("pickedAddress.take");
    // 接住之后要真的落到 addressId 上，否则拿到了也没用在这一单
    expect(body).toMatch(/addressId\.value = picked/);
  });

  it("★★ 返回时要重取地址簿 —— 他可能在那边新增了一条", () => {
    const body = bodyOf(confirmPage, "onShow(");
    expect(body).toContain("loadAddresses");
    /*
     * 顺序有讲究：新增的那条要先进 addresses，`address` 这个 computed 才找得到它。
     * 反过来写不会报错，只会让新地址「选了但页面上没变」。
     */
    expect(body).toMatch(/loadAddresses\(\)[\s\S]{0,200}addressId\.value = picked/);
  });
});

/**
 * 交接是**一次性**的 —— 这一条直接跑，不扫源码。
 *
 * <p>不清掉的话，下一次进页面会莫名其妙跳到上次选的那条：
 * 一个没人能复现、也没人会联想到这里的缺陷。
 */
describe("一次性交接：读一次就没了", () => {
  it("★★★ 地址 id 取过之后就没了", () => {
    pickedAddress.offer("AD001");
    expect(pickedAddress.take()).toBe("AD001");
    expect(pickedAddress.take(), "第二次还能取到 = 下一次进页面会被上一次的选择劫持").toBeNull();
  });

  it("★★★ 地点取过之后就没了", () => {
    pickedPlace.offer({ kind: "manual" });
    expect(pickedPlace.take()).toEqual({ kind: "manual" });
    expect(pickedPlace.take()).toBeNull();
  });

  it("★★ 没人交过就是 null —— 那是常态（用户点了系统返回）", () => {
    expect(pickedPlace.take()).toBeNull();
  });
});

/**
 * `placeFrom`：三种来源收敛成一个形状。
 *
 * <p>**省市区必须拆开**。`region` 是展示用的一串，而 province/city/district
 * 是能拿来算的那份（按省算运费、按区派单）。不拆的话那三列永远是 null，
 * 那些规则全在 null 上求值、一条都不命中，**而页面上完全正常**。
 */
describe("placeFrom：地点 → 可入库的地址", () => {
  it("★★★ 坐标转成 E6 整数 —— 全站一律 gcj02 的 E6", () => {
    const p = placeFrom({ name: "阳光里小区", address: "浙江省杭州市西湖区文一西路 100 号", lat: 30.123456, lng: 120.654321 });
    expect(p.latE6).toBe(30123456);
    expect(p.lngE6).toBe(120654321);
  });

  it("★★★ 省市区拆成三列，不是只留一串", () => {
    const p = placeFrom({ name: "阳光里小区", address: "浙江省杭州市西湖区文一西路 100 号", lat: 30, lng: 120 });
    expect(p.province).toBe("浙江省");
    expect(p.city).toBe("杭州市");
    expect(p.district).toBe("西湖区");
  });

  it("★★ 拆不出省市区时保留原串，不许把三列清空又塞一整串进 region", () => {
    const p = placeFrom({ name: "3 号楼", address: "阳光里小区 3 号楼", lat: 30, lng: 120 });
    expect(p.province).toBe("");
    expect(p.region, "拆不动就原样留着，总比丢掉强").toBe("阳光里小区 3 号楼");
  });

  it("★★ 地址主体优先用 POI 名 —— 那才是用户会写的写法", () => {
    const p = placeFrom({ name: "阳光里小区", address: "浙江省杭州市西湖区文一西路 100 号", lat: 30, lng: 120 });
    expect(p.name).toBe("阳光里小区");
  });

  it("★★ 没有 POI 名时退到门牌那一段，别把整条省市区又抄一遍进 detail", () => {
    const p = placeFrom({ address: "浙江省杭州市西湖区文一西路 100 号", lat: 30, lng: 120 });
    expect(p.name).toBe("文一西路 100 号");
  });
});

/**
 * 选点页存在的**唯一理由是让地址带上坐标**。
 *
 * <p>手打出来的地址只是一串字：后端那条自送半径的闸明写着「没坐标就放行」，
 * 于是商家以为自己限了三公里，实际什么单都进来 —— 而这件事在界面上看不出区别。
 */
describe("选点页：把「选」提为主路", () => {
  const pickPage = code("src/pages/address-pick/index.vue");
  const addressPage = code("src/pages/address/index.vue");

  it("★★★ 「新增地址」要先去选点页", () => {
    const body = bodyOf(addressPage, "function addNew(");
    expect(body, "地址簿没有 addNew 了 —— 守卫失去了扫描对象").not.toBeNull();
    expect(body).toContain("ROUTES.addressPick");
  });

  it("★★★ 这个端一条选点路都没有时，直接开表单", () => {
    const body = bodyOf(addressPage, "function addNew(");
    /*
     * H5 既没有原生搜索、也没配地图 JS key。不判这一下的话，
     * 那一页对他只剩一行「手动填写」—— 白挡一次点击，比改造前更差。
     */
    expect(body).toMatch(/canSearchPlaces\(\)[\s\S]{0,60}canChooseLocation\(\)/);
    expect(body).toContain("openNew()");
  });

  it("★★★ 选点页交回来的地点要带坐标落进草稿", () => {
    const body = bodyOf(addressPage, "onShow(");
    expect(body, "地址簿没有 onShow 了 —— 从选点页回来什么都不会发生").not.toBeNull();
    expect(body).toContain("pickedPlace.take");
    // 什么都没交回来（点了系统返回）时不许开表单：否则每次退出选点页都被塞一张
    expect(body).toMatch(/if \(!p\) return/);
    const openNew = bodyOf(addressPage, "function openNew(");
    expect(openNew, "openNew 不见了").not.toBeNull();
    expect(openNew, "预填了地址却把坐标丢了 = 这一趟白走").toContain("latE6");
  });

  it("★★★ 「手动填写」要交回显式的 manual，不能只是返回", () => {
    const body = bodyOf(pickPage, "function manual(");
    expect(body, "选点页没有 manual 了").not.toBeNull();
    /*
     * 只 navigateBack 的话，与「用户点了系统返回」分不开 ——
     * 而那两种情况该做的事正好相反：一个要开空表单，一个要什么都不做。
     */
    expect(body).toContain('kind: "manual"');
  });

  it("★★ 每一段都自检：这个端给不了的整段不显示", () => {
    expect(pickPage, "搜索段要看 canSearchPlaces").toContain("canSearchPlaces()");
    expect(pickPage, "地图选点要看 canChooseLocation").toContain("canChooseLocation()");
    expect(pickPage).toMatch(/v-if="canSearch"/);
    expect(pickPage).toMatch(/v-if="canMap"/);
  });

  it("★★★ 没坐标的社区不许出现在列表里，**整段的显隐也看过滤之后的条数**", () => {
    /*
     * 选了它等于又得到一条没有坐标的地址 —— 这一页就白来了。
     *
     * 第二句是实测补的：过滤只放在行上时，「附近」这张卡片照样带着标题渲染出来、
     * 底下一条没有（mock 的社区都没坐标）—— 一个承诺了内容的空标题，而守卫全绿。
     */
    expect(pickPage).toMatch(/nearby\.value\.filter\([\s\S]{0,80}latE6 != null/);
    expect(pickPage, "整段的 v-if 要看过滤之后的条数").toMatch(/v-if="nearbyPickable\.length"/);
    expect(pickPage, "别再按原始条数判显隐").not.toMatch(/v-if="nearby\.length"/);
  });

  it("★★ 模糊定位时不许显示距离 —— 假精确比不显示更糟", () => {
    // 区级坐标误差约 5 公里，而用户会照着「733m」去挑最近的那个
    expect(pickPage).toMatch(/v-if="!coarse && c\.distance"/);
  });
});
