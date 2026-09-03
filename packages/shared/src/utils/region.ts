/**
 * 省市区的**拆分与拼接**。
 *
 * 为什么需要它：`usr_address` 有 `province` / `city` / `district` 三列，
 * 而端上一直只填 `region` 那一整串（地图选点回来的就是
 * 「浙江省杭州市西湖区文三路 100 号」这种形态）。三列于是永远是 null ——
 * 而页面上一切正常：那一串照样显示、照样能下单。
 * 缺的是**看不见的那一半**：按省算运费、按区派单、按市做经营范围校验，
 * 全都在 null 上求值，静默地一条都不命中。
 *
 * 这里刻意不引区划码表：拆分只认后缀，是**尽力而为**的兜底，
 * 给「用户手填 / 地图回填」这两条没有 code 的路用。
 * 走 `/mp/regions` 三级选择器的那条路是有 code 的，不必也不该经过这里。
 */

/** 四个直辖市。它们「市即省」，第二级直接是区 —— 单独列出来是因为后缀「市」判不出来 */
const MUNICIPALITIES = ["北京市", "天津市", "上海市", "重庆市"];

const PROVINCE_SUFFIX = ["特别行政区", "自治区", "省"];
const CITY_SUFFIX = ["自治州", "地区", "盟", "市"];
const DISTRICT_SUFFIX = ["自治县", "区", "县", "市", "旗"];

/**
 * 以这些结尾的**不是区县**。
 *
 * 这条不是洁癖：「XX 小区」在存量地址里比真区县还常见，而它以「区」结尾 ——
 * 「XX 小区 3 栋 201」会被整整齐齐地拆成 district=「XX 小区」，
 * 然后按区派单时凭空多出一个不存在的区，且**看起来完全正常**。
 */
const NOT_DISTRICT = ["小区", "社区", "园区", "厂区", "校区", "景区", "开发区", "工业区", "度假区"];

/**
 * 34 个省级单位的**规范名**（含直辖市与两个特别行政区）。
 *
 * <p>这份表与本文件开头那句「刻意不引区划码表」不冲突：它不是码表，
 * 只是最顶一级的名字，而且只服务一件事 —— 回答
 * {@link regionStart}「这一串里地址从第几个字开始」。
 *
 * <p>为什么必须精确而不能靠后缀猜：{@link head} 取的是**最短**的以后缀结尾的前缀，
 * 于是「张三浙江省杭州市…」里它会认出 province=「张三浙江省」——
 * 而那一串正是粘贴识别最常见的输入形状（姓名与地址之间没有分隔符）。
 */
const PROVINCES = [
  "北京市", "天津市", "上海市", "重庆市",
  "河北省", "山西省", "辽宁省", "吉林省", "黑龙江省", "江苏省", "浙江省", "安徽省",
  "福建省", "江西省", "山东省", "河南省", "湖北省", "湖南省", "广东省", "海南省",
  "四川省", "贵州省", "云南省", "陕西省", "甘肃省", "青海省", "台湾省",
  "内蒙古自治区", "广西壮族自治区", "西藏自治区", "宁夏回族自治区", "新疆维吾尔自治区",
  "香港特别行政区", "澳门特别行政区",
];

/**
 * 这一串里**地址从第几个字开始**。找不到省级单位名就返回 -1。
 *
 * <p>给粘贴识别用：它要把「张三13800138000浙江省杭州市…」里的姓名与地址分开，
 * 而这件事没法靠后缀猜（理由见 {@link PROVINCES}）。
 *
 * <p>只在**前 {@code within} 个字**里找：姓名就那么长，
 * 窗口开大了只会把「XX 省份路」这种地名当成省。
 */
export function regionStart(s: string, within = 8): number {
  const window = (s ?? "").slice(0, within + 6);
  let best = -1;
  for (const p of PROVINCES) {
    const i = window.indexOf(p);
    if (i >= 0 && i <= within && (best < 0 || i < best)) best = i;
  }
  return best;
}

export interface RegionParts {
  province: string;
  city: string;
  district: string;
  /** 省市区之后剩下的部分（街道门牌）。拆不动时整串都在这里 */
  rest: string;
}

/**
 * 取**最短的**、以某个后缀结尾的前缀。
 *
 * 「最短」是关键：「黑龙江省哈尔滨市」里「省」和「市」都能结尾，
 * 取最长会把整串当成省。最少两个字 —— 没有一个字的省市区。
 */
function head(s: string, suffixes: string[], max: number): string {
  for (let end = 2; end <= Math.min(s.length, max); end++) {
    const seg = s.slice(0, end);
    if (suffixes.some((x) => seg.endsWith(x))) return seg;
  }
  return "";
}

/**
 * 把一整串地址拆成省 / 市 / 区 / 其余。拆不出来的那几级返回空串，**不抛异常** ——
 * 存量地址里什么都有（只有门牌的、写「XX 小区 3 栋」的），
 * 拆不动是常态，不是错误。
 */
export function splitRegion(raw?: string | null): RegionParts {
  let s = (raw ?? "").trim();
  if (!s) return { province: "", city: "", district: "", rest: "" };

  let province = MUNICIPALITIES.find((m) => s.startsWith(m)) ?? "";
  if (!province) province = head(s, PROVINCE_SUFFIX, 9);
  // 每一级之后都 trim：存量地址里「浙江省 杭州市 西湖区」这种带空格的写法很常见，
  // 不 trim 的话拆出来的是「 杭州市」（前面挂着一个空格），存进库里跟别处对不上
  s = s.slice(province.length).trimStart();

  // 直辖市：province 与 city 填同一个值。按省统计和按市统计都要能命中，
  // 而「北京市」在两张口径里都是它自己
  let city = MUNICIPALITIES.includes(province) ? province : head(s, CITY_SUFFIX, 10);
  if (city !== province) s = s.slice(city.length).trimStart();

  /*
   * 区县只在**已经认出省或市**之后才取。
   * 光凭一个「区」字就断言是区县，「XX 小区 3 栋」这种纯门牌串会被吃掉一截 ——
   * 而省直辖县级市（济源、仙桃…）落在 city 上而不是 district 上，
   * 那正是它们在国标里的位置，不是拆错。
   */
  let district = "";
  if (province || city) {
    const d = head(s, DISTRICT_SUFFIX, 10);
    if (d && !NOT_DISTRICT.some((x) => d.endsWith(x))) {
      district = d;
      s = s.slice(d.length).trimStart();
    }
  }

  return { province, city, district, rest: s };
}

/**
 * 拼 / 判用的入参。**允许 null** —— 契约里这三列是 `string | null`
 * （存量地址拆不出来就是 null），不放开的话每个调用点都要先 `?? ""` 一遍，
 * 而漏掉的那处是编译期报错，改起来又只会再加一个 `?? ""`。
 */
export type RegionPartsLike = { [K in keyof RegionParts]?: string | null };

/**
 * 反过来拼成给人看的一串。直辖市不重复写两遍「北京市北京市」。
 */
export function joinRegion(p: RegionPartsLike): string {
  const province = p.province ?? "";
  const city = p.city ?? "";
  const district = p.district ?? "";
  return province + (city === province ? "" : city) + district;
}

/**
 * 三级是否都齐。**不含 rest** —— 门牌是另一个字段的事。
 *
 * 不地道地把「有 region 那一串」当成填好了：那串可以是「随便写点什么」，
 * 而三列还是空的。
 */
export function isCompleteRegion(p: RegionPartsLike): boolean {
  return !!(p.province?.trim() && p.district?.trim());
}
