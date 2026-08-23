/**
 * 定位/选点的端上壳：把 ports/location 的结果翻译成给店主看的提示。
 *
 * 为什么不是一条「定位失败」：高德把原因分得很清楚 —— 没权限（12/13）再试一百次也一样，
 * 要带他去设置；网络/环境问题（2/4/6）去设置也没用，只能手动填。混成一条提示，
 * 店主只会反复点那个按钮。
 */
import type { Coords, PickedLocation } from "@shared/ports/location";
import { chooseLocation, getLocationDetailed, openLocationSettings } from "@shared/ports/location";
import type { PlaceHit } from "@shared/ports/geo-search";
import { canSearchPlaces, searchPlacesNative, searchPlacesNearNative } from "@shared/ports/geo-search";
import { api } from "@/api";

type T = (key: string, named?: Record<string, unknown>) => string;

/** 定位一次；失败时已经提示过了，调用方只管 null */
export async function locateWithFeedback(t: T): Promise<Coords | null> {
  const r = await getLocationDetailed();
  if (r.ok) return r.coords;
  if (r.reason === "denied") {
    uni.showModal({
      title: t("geo.deniedTitle"),
      content: t("geo.deniedBody"),
      confirmText: t("geo.goSettings"),
      cancelText: t("common.cancel"),
      success: (m) => {
        if (m.confirm && !openLocationSettings()) uni.showToast({ title: t("geo.settingsHint"), icon: "none" });
      },
    });
  } else {
    uni.showToast({ title: t("geo.unavailable"), icon: "none" });
  }
  return null;
}

/**
 * 地图选点；这个端不支持（H5 没配 JS key）时退回定位一次，返回的 name/address 为空。
 * 用户取消返回 null 且不提示 —— 取消不是错误。
 */
export async function pickOnMap(t: T, init?: Coords | null): Promise<PickedLocation | null> {
  const r = await chooseLocation(init);
  if (r.ok) return r.picked;
  if (r.reason === "cancel") return null;
  const c = await locateWithFeedback(t);
  return c ? { ...c, name: "", address: "" } : null;
}

/** 选点结果拼成一行门牌地址：标准地址 + POI 名；POI 名已在地址里就不重复 */
export function composeAddress(p: PickedLocation): string {
  const addr = p.address.trim();
  const name = p.name.trim();
  if (!name) return addr;
  if (!addr || addr.includes(name)) return addr || name;
  return `${addr}${name}`;
}

/**
 * 地点联想：输入一串地名（「深圳市龙华区福城街道」也行），拿回带坐标的候选。
 *
 * 两条路：App 用包里的**原生高德 SDK**（不需要后端 Web 服务 key，今天就能用）；
 * 其它端退到后端 `/biz/geo/tips`（要 `AMAP_WEB_KEY`，没配就是空列表）。
 * 两条都失败时返回空数组 —— 联想是加分项，不该把输入框卡住。
 */
export async function searchPlaces(keyword: string, city?: string): Promise<PlaceHit[]> {
  const kw = keyword.trim();
  if (kw.length < 2) return [];
  if (canSearchPlaces()) {
    const r = await searchPlacesNative(kw, city);
    if (r) return r;
  }
  try {
    const tips = await api.mGeoTips(kw, city);
    return tips
      .filter((x) => x.latE6 != null && x.lngE6 != null)
      .map((x) => ({
        name: x.name,
        address: x.address ?? "",
        city: "",
        lat: x.latE6! / 1e6,
        lng: x.lngE6! / 1e6,
      }));
  } catch {
    return [];
  }
}

/**
 * 在一个点周围找地方。**输名字找小区就该走这条** ——
 * 按城市搜时城市只是偏好（在深圳搜「福安」会返回福建福安市），
 * 而把街道名拼进关键词会把「XX街道办事处」顶到前面、真小区一个都排不上（都实测过）。
 * 不支持原生搜索的端退回按城市搜，聊胜于无。
 */
export async function searchPlacesNear(
  keyword: string,
  center: Coords,
  radiusM = 5000,
  city?: string,
): Promise<PlaceHit[]> {
  const kw = keyword.trim();
  if (kw.length < 2) return [];
  if (canSearchPlaces()) {
    const r = await searchPlacesNearNative(kw, center, radiusM);
    if (r) return r;
  }
  return searchPlaces(kw, city);
}

/**
 * 地图上这类名字不是小区：公交站、停车场、门店、公共设施。
 *
 * 周边搜索会把它们一起带回来（「福安雅园(公交站)」「福安雅园水果店」），
 * 而商家要挑的是**住的地方** —— 让他在一堆快递柜里找自己的小区，等于没做联想。
 */
export function looksLikeEstate(name: string): boolean {
  const noise = /(公交站|地铁站|停车场|超市|便利店|水果|药店|换电|快递|驿站|丰巢|菜鸟|公厕|公共厕所|公园|学校|幼儿园|中学|小学|医院|诊所|卫生|银行|酒店|宾馆|餐厅|饭店|商铺|档口|工业园|办事处|居委会|村委会|工作站|党群|警务|服务中心|充电)/;
  if (noise.test(name)) return false;
  // 去掉「A区 / 二期 / 3栋」这类后缀再看是不是住宅名
  const base = name.replace(/[A-Za-z\u4e00-\u9fa5\d]{0,3}(区|期|栋|号楼)$/, "");
  return /(小区|花园|家园|新村|公寓|苑|园|城|湾|府|庭|邸|里|村|大厦|广场|山庄|名居|世家)$/.test(base) || /小区/.test(name);
}

/**
 * 区域中心：把面包屑（「广东省 › 深圳市 › 龙华区 › 福城街道」）当成一个地名去搜，取第一条的坐标。
 *
 * 为什么要它：地图选点默认落在**当前设备位置**，而商家常常在店里给另一个区配范围 ——
 * 开局就在几百公里外，等于每次都要先手动挪地图。`sys_region` 没有坐标列，
 * 所以只能靠搜索把「区域名」换成坐标。同一条路径只搜一次（进程内缓存）。
 */
const centerCache = new Map<string, Coords | null>();
export async function regionCenter(names: string[]): Promise<Coords | null> {
  const q = names.filter(Boolean).join("");
  if (q.length < 2) return null;
  if (centerCache.has(q)) return centerCache.get(q) ?? null;
  const hits = await searchPlaces(q, names.find((n) => n.endsWith("市")));
  const top = hits[0];
  const c = top ? { lat: top.lat, lng: top.lng } : null;
  centerCache.set(q, c);
  return c;
}

/** 从一条地址里抠出街道/镇/乡的名字（「广东省深圳市龙华区福城街道…」→「福城街道」），抠不到给 null */
export function streetOf(address: string): string | null {
  const m = address.match(/([\u4e00-\u9fa5]{2,8}(?:街道|镇|乡))/);
  return m?.[1] ?? null;
}
