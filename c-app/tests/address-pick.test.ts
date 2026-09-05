import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pickedAddress, pickedPlace, placeFrom } from "@/shared/address-pick";
import { metersBetweenE6, withinDeliveryRange } from "@shared/utils/geo";
import { ADDRESS_RULES } from "@shared/utils/constants";

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
  /*
   * **按花括号配平找结尾，不要找 `\n}`。**
   *
   * 早先是 `rest.indexOf("\n}")` —— 那只认顶格的闭合。pinia store 里的方法
   * 缩进四格、以 `\n    },` 结尾，于是取到的「函数体」一路延伸到文件末尾。
   * 正向断言（toContain）碰巧还对，而**每一条否定断言都成了虚的**：
   * 断言「这个方法里没有 switchActiveAddress」，实际扫的是整个 store。
   * 一条永远为真的守卫比没有守卫更糟 —— 它让人以为那件事有人看着。
   */
  /*
   * **先跳过参数表再数花括号。** 直接从签名处数的话，
   * `suggestNearest(at: { lat: number })` 里那个**内联对象类型**的花括号
   * 会被当成函数体的开头，于是「函数体」在参数表就结束了 ——
   * 而那同样让否定断言变成空转。
   */
  let paren = 0;
  let i = 0;
  for (; i < rest.length; i++) {
    if (rest[i] === "(") paren++;
    else if (rest[i] === ")") {
      paren--;
      if (paren === 0) break;
    }
  }
  let depth = 0;
  let started = false;
  for (; i < rest.length; i++) {
    const ch = rest[i];
    if (ch === "{") {
      depth++;
      started = true;
    } else if (ch === "}") {
      depth--;
      if (started && depth === 0) return rest.slice(0, i + 1);
    }
  }
  return rest;
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

  it("★★★ 不是选址模式时，点一整张卡就是「切到这儿」", () => {
    /*
     * 这一页的注释一直写着「点一下就切，不弹窗不追问」，而 `pick()` 第一行
     * 曾是 `if (!picking.value) return;` —— 从「我的」进来点地址什么也不会发生，
     * 切换只藏在一个小字按钮上。**说的和做的不是一回事**，
     * 而症状是「点了没反应」：不报错、不留痕，只能靠人来说。
     */
    const body = bodyOf(addressPage, "function pick(");
    expect(body, "地址页没有 pick 函数了").not.toBeNull();
    expect(body, "非选址模式下点卡片要切换 —— 直接 return 就是「点了没反应」")
      .toContain("useHere");
    // 对照量：选址模式那条路还在，别把它一起改没了
    expect(body).toContain("pickedAddress.offer");
  });

  it("★★★ 切完要回上一页 —— 这一页看不见货，留在这儿等于让他白点一下", () => {
    /*
     * 切换是手段不是目的：他要的是「看那一片的货」，而地址簿**看不见货**。
     * 切完留在原地，他只能看到一个 toast 再自己按返回 ——
     * 中间那一步没有任何信息量。首页 onShow 里有 load()，回去就是新的那一片。
     */
    const body = bodyOf(addressPage, "async function useHere(");
    expect(body, "useHere 找不到了").not.toBeNull();
    expect(body, "切完不回去 = 他得自己按返回，而这一页什么也没变")
      .toContain("navigateBack");
    // 对照量：那句「没有定位点」的提示还在，别为了跳转把它顺手删了
    expect(body).toContain("nowHereNoCoord");
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
     *
     * 断言的是**这条性质**，不是某一种写法：canPick 为假时开表单、为真时才跳选点页。
     * （canPick 自己由那两个端能力算出来，另一条守卫在管。）
     */
    expect(body).toMatch(/!canPick\.value[\s\S]{0,80}openNew\(\)/);
    expect(body).toContain("ROUTES.addressPick");
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

/**
 * 送不到就别让他填完再撞墙。
 *
 * <p>后端在**创建订单那一刻**拦（`requireWithinDeliveryRadius`），
 * 而此前端上完全不知道这件事：用户挑地址、填完整页、点提交，才收到
 * `OUT_OF_DELIVERY_RANGE` —— 那时他既不知道是哪家送不到，也不知道该换哪个地址。
 *
 * <p><b>这几条直接跑算法，不扫源码</b>：要守的是「与后端同一个口径」，
 * 而口径是算出来的，不是写出来的。
 */
describe("送不到要提前说 —— 且口径与后端一字不差", () => {
  const origin = { deliveryLatE6: 30_000_000, deliveryLngE6: 120_000_000, deliveryRadiusM: 3000 };

  it("★★★ 三条放行必须与后端一致 —— 端上比后端严会把好单挡在门外", () => {
    // ① 地址没坐标（存量地址全是这样）
    expect(withinDeliveryRange(origin, { latE6: null, lngE6: null })).toBe(true);
    // ② 门店没在地图上标过点
    expect(withinDeliveryRange({}, { latE6: 31_000_000, lngE6: 121_000_000 })).toBe(true);
    // ③ 半径 ≤ 0 = 不限距离
    expect(withinDeliveryRange({ ...origin, deliveryRadiusM: 0 }, { latE6: 31_000_000, lngE6: 121_000_000 }))
      .toBe(true);
  });

  it("★★★ 圈内放行、圈外拦下", () => {
    // 约 1 公里：0.009 度纬度 ≈ 1002 米
    expect(withinDeliveryRange(origin, { latE6: 30_009_000, lngE6: 120_000_000 })).toBe(true);
    // 约 5.6 公里，超出 3 公里
    expect(withinDeliveryRange(origin, { latE6: 30_050_000, lngE6: 120_000_000 })).toBe(false);
  });

  it("★★★ 经度间距要随纬度收缩 —— 不乘 cos 会在高纬度多算出几百米", () => {
    /*
     * 后端 metersBetween 乘了 cos(midLat)，端上不乘的话同一个点会被算远，
     * 于是「后端说送得到、端上说送不到」——用户看着一个自相矛盾的界面。
     * 在北纬 60 度，一度经度只有赤道的一半。
     */
    const far = metersBetweenE6(60_000_000, 0, 60_000_000, 1_000_000);
    const equator = metersBetweenE6(0, 0, 0, 1_000_000);
    expect(far).toBeLessThan(equator * 0.6);
    expect(far).toBeGreaterThan(equator * 0.4);
  });
});

/**
 * 门牌号是**端上必填、后端不必填**。
 *
 * <p>后端要着 `@NotBlank` 的话，还没更新的老版本 App（它压根不发这个字段）
 * 连「改个手机号」都保存不了 —— 一个纯粹由这次改动造成的故障，
 * 而用户那边只看到「保存失败」。
 */
describe("门牌号与地址簿上限", () => {
  const addressPage = code("src/pages/address/index.vue");

  it("★★★ 门牌号在端上必填", () => {
    const body = bodyOf(addressPage, "const valid = computed(");
    expect(body, "valid 不见了").not.toBeNull();
    expect(body).toContain("draft.value.houseNo");
  });

  it("★★★ 地址主体只在**能选点的端**上只读 —— 否则 H5 用户永远存不了地址", () => {
    /*
     * 地址主体是跟坐标一起来的。在表单里随手改几个字，坐标不会跟着动 ——
     * 于是「文字写着 A、坐标指着 B」，而页面上完全看不出来。
     * 但没有任何选点路的端（H5）必须保持可输入，否则他连存量地址都改不了。
     */
    expect(addressPage).toMatch(/:disabled="canPick"/);
    expect(addressPage).toMatch(/canSearchPlaces\(\) \|\| canChooseLocation\(\)/);
  });

  it("★★ 到上限时按钮上就说清楚，不等他填完才拒", () => {
    expect(addressPage).toContain("ADDRESS_RULES.maxCount");
    expect(addressPage).toMatch(/atLimit/);
  });

  it("★★★ 上限端上后端各一份，且必须是同一个数", () => {
    /*
     * 端上这份只管把按钮提前置灰，后端那份才是真闸（老版本 App 不知道有这回事）。
     * 两处对不上的表现：按钮还亮着，点下去被拒，而用户看不出自己哪里做错了。
     */
    const backend = readFileSync(
      resolve(__dirname, "../../backend/shop-core/src/main/java/ai/neargo/shop/user/service/AddressService.java"),
      "utf-8",
    );
    const m = backend.match(/int MAX_ADDRESSES = (\d+)/);
    expect(m, "后端的 MAX_ADDRESSES 不见了").not.toBeNull();
    expect(Number(m![1])).toBe(ADDRESS_RULES.maxCount);
  });
});

/**
 * 标签：预设三个 + 自定义。
 *
 * <p><b>i18n 那道闸看不见这几个词条</b>：它们是动态键（`$t(\`address.${k}\`)`），
 * 把整段 chip 删掉之后 `check-i18n-orphan` 照样是 6 条，一个字都不报。
 * 所以「词条对账 0 条新增」在这里**不构成证据** —— 真正确认它们能渲染出来，
 * 靠的是在 H5 上看见那三个 chip，以及存量的「家」把第一个 chip 点亮。
 * 这几条守卫补的是另一半：结构别被人顺手改回去。
 */
describe("标签：预设 chip，输入框仍是唯一真源", () => {
  const addressPage = code("src/pages/address/index.vue");

  it("★★ 三个预设都在，且 chip 只写 draft.tag", () => {
    expect(addressPage).toContain("TAG_PRESETS");
    expect(addressPage).toMatch(/tagHome[\s\S]{0,40}tagWork[\s\S]{0,40}tagSchool/);
    /*
     * chip 是快捷方式、输入框是真源 —— 不做成「预设/自定义」两种模式，
     * 于是没有「我现在处在哪种模式」这个问题，也没有两者对不上的中间态。
     */
    expect(addressPage).toMatch(/@tap="draft\.tag = String\(\$t\(/);
  });

  it("★★★ 标签长度上限 8 —— 顶栏的短名直接显示它", () => {
    /*
     * 此前是 16。`location` store 的 label getter 把 tag 原样交给顶栏那一行，
     * 16 个字会把它撑爆，而那一行还要放定位图标与箭头。
     */
    const tagInput = addressPage.match(/<input[^>]*draft\.tag[^>]*>/s);
    expect(tagInput, "标签那个输入框不见了").not.toBeNull();
    expect(tagInput![0]).toContain('maxlength="8"');
  });
});

/**
 * 粘贴识别与「这个地址没有定位点」。
 *
 * <p>解析本身在 `packages/shared/tests/address-paste.test.ts` 里逐条行为测过。
 * 这里守的是**接线上的两条性质**，它们都属于「错了也不会响」那一类。
 */
describe("粘贴识别：只填空格子，且不冒充选点", () => {
  const addressPage = code("src/pages/address/index.vue");

  it("★★★ 只填空着的格子，不覆盖他已经敲的字", () => {
    const body = bodyOf(addressPage, "async function pasteAndFill(");
    expect(body, "粘贴入口不见了").not.toBeNull();
    /*
     * 他可能先手填了一半才想起来有这个按钮。一键把刚敲的字冲掉，
     * 是最让人恼火的那种「贴心」——旁边 fillFromWx 也是这条规矩。
     */
    expect(body).toMatch(/!String\(draft\.value\[k\] \?\? ""\)\.trim\(\)/);
    expect(body, "省市区那一组也要判空再填").toMatch(/!draft\.value\.region\.trim\(\)/);
  });

  it("★★★ 认不出来要说一声，不能静默什么都不做", () => {
    const body = bodyOf(addressPage, "async function pasteAndFill(");
    expect(body).toContain("pasteFailed");
    expect(body, "剪贴板空着与认不出来是两回事，文案也该是两句").toContain("pasteEmpty");
  });

  it("★★★ 没有坐标时必须说一句，但**不许拦保存**", () => {
    /*
     * 手填、微信导入、粘贴三条路都只给字不给坐标。没坐标的地址上
     * 商家自送半径判不了（后端明写着「没坐标就放行」）、导航也打不开 ——
     * 三件事在界面上都看不出区别。
     *
     * 拦保存同样不行：存量地址、POI 搜不到的地方本来就没有坐标，
     * 拦了等于让一部分人存不了地址。与 regionUnsplit 那句同一种口径。
     */
    expect(addressPage).toMatch(/v-if="!picked"[\s\S]{0,200}noCoordHint/);
    const valid = bodyOf(addressPage, "const valid = computed(");
    expect(valid, "valid 里出现 picked = 把提示变成了闸").not.toContain("picked");
  });
});

/**
 * 模糊定位不参与聚落匹配 · 切换没坐标的地址要说一句。
 *
 * <p>两条都属于「不做会怎样看不出来」那一类：
 * 模糊坐标匹配出来的聚落**看起来完全正常**（顶栏一样显示小区名、商品一样列出来），
 * 只是全都不是他那一带的；而切到没坐标的地址时归属不变是对的，
 * 但一声不吭他就分不清「设计如此」与「坏了」。
 */
describe("模糊定位与无坐标地址：都要说话", () => {
  const communityPage = code("src/pages/community/index.vue");
  const addressPage = code("src/pages/address/index.vue");
  const store = code("src/stores/location.ts");

  it("★★★ 模糊坐标不喂给聚落匹配 —— 藏起距离挡不住选错", () => {
    /*
     * 此前只是「模糊时不显示距离」。藏数字挡不住他照着一个隔壁片区的自提点选下去，
     * 而那个点他根本走不到。围栏 1000 米量级 vs 模糊定位 5 公里误差 ——
     * 这一档的坐标只够把人落到区。
     */
    expect(communityPage).toMatch(/loadNearby\([\s\S]{0,120}fuzzy \? undefined/);
  });

  it("★★ 模糊时仍然不显示距离（原有行为不许退化）", () => {
    expect(communityPage).toMatch(/v-if="!coarse"/);
  });

  it("★★★ 切到没坐标的地址：归属不变，但**必须说一句**", () => {
    // 归属不变是对的——清掉的话他会发现「换了个地址，商品全没了」
    expect(store).toMatch(/latE6 == null \|\| a\.lngE6 == null\) return false/);
    // 而不说话同样糟：顶栏变了、商品没变，他无从判断
    const body = bodyOf(addressPage, "async function useHere(");
    expect(body, "useHere 不见了").not.toBeNull();
    expect(body).toContain("nowHereNoCoord");
    expect(body, "要能区分换成没换成，否则两句话没法分").toContain("rebound");
  });

  it("★★ 没换成时提示要停得久一点 —— 那句话比「已切到」长得多", () => {
    const body = bodyOf(addressPage, "async function useHere(");
    expect(body).toMatch(/duration: rebound \? \d+ : \d+/);
  });
});

/**
 * **定位只做一件事：匹配用户自己的收货地址。**（PRD §6.1.0）
 *
 * <p>位置永远是一条地址，聚落匹配是那条地址的下游 —— 定位不直接选聚落。
 * 把它做成第二条并列的入口，用户就要理解两套东西，
 * 而归属、下单预填、送不送得到全都挂在地址上。
 *
 * <p>本组替换了早先「首页主动弹一句『切过去吗』」的那几条：
 * 那个弹窗是自造的形状，不是这条链路该有的样子 —— 匹配到就在列表里标出来，
 * 他点一下就切，不追问。
 */
describe("定位只匹配收货地址", () => {
  const home = code("src/pages/home/index.vue");
  const addressPage = code("src/pages/address/index.vue");
  const store = code("src/stores/location.ts");

  it("★★★ 匹配到的那条只标出来，点一下即切 —— 不弹窗", () => {
    expect(addressPage).toContain("locatedMatch");
    expect(addressPage).toMatch(/a\.addressId === locatedMatch/);
    expect(home, "首页不许再主动弹窗问「切过去吗」").not.toContain("switchAsk");
  });

  it("★★★ 一条都没匹配到 → 以当前位置为准，**不是回落到无位置**", () => {
    /*
     * 「没匹配到」不是死路：他照样能逛、能下单。
     * 回落到无位置首屏只留给**连定位都拿不到**的情况。
     */
    expect(addressPage).toMatch(/v-if="locatedAt && !locatedMatch"/);
    const body = bodyOf(addressPage, "async function useCurrentLocation(");
    expect(body, "没有 useCurrentLocation").not.toBeNull();
    expect(body).toContain("location.useTransient");
  });

  it("★★★ 当前位置**不入地址簿、不写服务端** —— 它是上下文不是资料", () => {
    const body = bodyOf(store, "async useTransient(");
    expect(body, "store 里没有 useTransient").not.toBeNull();
    expect(body, "存进地址簿会很快把 20 条上限塞满").not.toContain("saveAddress");
    expect(body, "写服务端等于把一次性上下文变成长期偏好")
      .not.toContain("switchActiveAddress");
  });

  it("★★★ 模糊坐标不参与地址匹配 —— 5 公里误差配 1 公里判据是噪音", () => {
    const body = bodyOf(addressPage, "async function detectHere(");
    expect(body, "没有 detectHere").not.toBeNull();
    expect(body).toMatch(/!r\.ok \|\| r\.fuzzy\) return/);
  });

  it("★★ 匹配要足够近 —— 再远就是「附近碰巧存过一个地址」", () => {
    const body = bodyOf(store, "suggestNearest(");
    expect(body, "store 里没有 suggestNearest").not.toBeNull();
    expect(body).toMatch(/bestM <= MATCH_NEAR_M/);
  });

  it("★★ 顶栏 chip 不含当前那个，且最多两个", () => {
    const body = bodyOf(home, "const quickPlaces = computed(");
    expect(body, "quickPlaces 不见了").not.toBeNull();
    expect(body).toContain("!== location.active?.addressId");
    expect(body, "顶栏那一行还要放定位图标、地名与搜索").toContain("slice(0, 2)");
  });
});
