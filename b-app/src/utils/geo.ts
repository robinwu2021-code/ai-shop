/**
 * 定位/选点的端上壳：把 ports/location 的结果翻译成给店主看的提示。
 *
 * ── 2026-09-06 删掉三个死导出 ──────────────────────────────────────────────
 * `searchPlacesNear` / `looksLikeEstate` / `streetOf` 曾被 `biz-region-picker.vue`
 * 用着，`f8994811a` 重写那个组件（1573 行）时把调用点全删了、函数留在了这里。
 * 小区候选现在由**服务端读穿透**给，街道抽取在后端 `RegionServiceImpl`
 * （四级逐级剥离：省 → 市 → 区 → 街道，每级在剩下的串上匹配）。
 *
 * 不是「留着以后可能用」：`streetOf` 对它自己文档写的输入就是错的 ——
 * 「广东省深圳市龙华区福城街道福安雅园」它给出「深圳市龙华区福城街道」而不是
 * 「福城街道」（`{2,8}` 贪婪往前吞了两级）。一个没人调、又会给出确定错答案的
 * 导出比没有更危险：下一个人会照它的文档用它。
 *
 * 为什么不是一条「定位失败」：高德把原因分得很清楚 —— 没权限（12/13）再试一百次也一样，
 * 要带他去设置；网络/环境问题（2/4/6）去设置也没用，只能手动填。混成一条提示，
 * 店主只会反复点那个按钮。
 */
import type { Coords, PickedLocation } from "@shared/ports/location";
import { chooseLocation, getLocationDetailed, openLocationSettings } from "@shared/ports/location";
import type { PlaceHit } from "@shared/ports/geo-search";
import { canSearchPlaces, searchPlacesNative } from "@shared/ports/geo-search";
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
 * 地图选点；这个端不支持时退回定位一次，返回的 name/address 为空。
 * H5 就是那个不支持的端 —— **有意不配** JS API key（店主用 App，H5 只我们调试用），
 * 见 ports/location.ts 与 b-app/.env.local.example。
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
