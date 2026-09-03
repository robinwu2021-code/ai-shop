// 端能力：定位 —— 选社区、服务范围判定、门店/取货点标点。
// 拒绝授权时降级为手动搜索/手动填，功能不阻塞。
export interface Coords {
  lat: number;
  lng: number;
}

/**
 * 定位失败的两类原因，端上要分开提示：
 * - denied：用户没给权限 / 系统定位没开 —— 能引导去设置，再试就能好
 * - unavailable：环境或网络问题（高德 2/4/6、超时…）—— 引导也没用，退回手动
 */
export type LocationFailReason = "denied" | "unavailable";

export type LocationOutcome = { ok: true; coords: Coords } | { ok: false; reason: LocationFailReason; detail: string };

/** 高德原生错误码里「权限/开关」那几个：12 缺权限、13 定位服务未开；DCloud 系统定位被拒时 errMsg 带 permission */
const DENIED_RE = /\[geolocation:1[23]\]|permission|denied|auth|未授权|权限|not allowed/i;

/**
 * 定位最多等多久。**这不是性能考虑，是「界面不能永远转圈」。**
 *
 * `uni.getLocation` 的两个回调都可能一个都不来：小程序端涉隐私的接口在
 * 「用户隐私保护指引」未配置 / 未授权时会**挂起**（既不 success 也不 fail），
 * 地理位置接口权限没在后台申请时也有同样表现。
 *
 * 后果不是「定位失败」，而是**选社区页永远停在「定位中…」** ——
 * 页面在，但底下什么都没有，用户看到的是「打开就跳过来、还点不了」。
 * 而这一页恰好是新用户的第一屏。
 *
 * 8 秒：真机冷启动定位偶尔要五六秒，给足；再长用户已经在反复点了。
 */
const LOCATE_TIMEOUT_MS = 8000;

export function getLocationDetailed(): Promise<LocationOutcome> {
  /*
   * 三端统一 gcj02：App 端定位已切高德提供方（manifest sdkConfigs.geolocation.amap，
   * 离线包带 AMap SDK），高德原生就是 gcj02，与后端逆地理/围栏用的坐标系一致。
   * 历史：之前 App 用系统定位只给 wgs84，传 gcj02 会被运行时直接拒（fail 回调）——
   * 若哪天又换回 system 提供方，这里要跟着回 wgs84。
   */
  return new Promise((resolve) => {
    let settled = false;
    const done = (r: LocationOutcome) => {
      if (settled) return;
      settled = true;
      resolve(r);
    };
    // 兜底：回调一个都不来时，把它当「拿不到」处理，让调用方照常降级
    const timer = setTimeout(
      () => done({ ok: false, reason: "unavailable", detail: "timeout" }),
      LOCATE_TIMEOUT_MS,
    );
    const finish = (r: LocationOutcome) => {
      clearTimeout(timer);
      done(r);
    };
    uni.getLocation({
      type: "gcj02",
      success: (res) => finish({ ok: true, coords: { lat: res.latitude, lng: res.longitude } }),
      fail: (e) => {
        const detail = String((e as { errMsg?: string })?.errMsg ?? "");
        finish({ ok: false, reason: DENIED_RE.test(detail) ? "denied" : "unavailable", detail });
      },
    });
  });
}

/** 老签名：拿不到就 null，调用方自行降级 */
export async function getLocation(): Promise<Coords | null> {
  const r = await getLocationDetailed();
  return r.ok ? r.coords : null;
}

export interface PickedLocation extends Coords {
  /** POI 名（「市民中心」），可能为空 */
  name: string;
  /** 标准地址（「广东省深圳市福田区福中三路」） */
  address: string;
}

export type ChooseOutcome = { ok: true; picked: PickedLocation } | { ok: false; reason: "cancel" | "unsupported" };

/**
 * 地图选点：App 走高德原生选点页（搜索 + 拖图钉），小程序走微信自带。
 * H5 **不配** JS API key（2026-08-28 拍板：店主用 App，B 端 H5 只我们自己调试用），
 * 于是 `uni.chooseLocation` 直接 fail —— 返回 unsupported，调用方退回「定位一次」。
 * 这是决定，不是待办：别看见它就去申请 key，理由写在 b-app/.env.local.example 里。
 *
 * 为什么不用「定位一次」代替：商家多半是在店里填表，不是站在取货点上，
 * 「当前位置」≠「要标的那个点」，而 withinRadius / 导航全靠这个坐标。
 */
export function chooseLocation(init?: Coords | null): Promise<ChooseOutcome> {
  return new Promise((resolve) => {
    uni.chooseLocation({
      ...(init ? { latitude: init.lat, longitude: init.lng } : {}),
      success: (r) =>
        resolve({
          ok: true,
          picked: { lat: r.latitude, lng: r.longitude, name: r.name ?? "", address: r.address ?? "" },
        }),
      fail: (e) => {
        const msg = String((e as { errMsg?: string })?.errMsg ?? "");
        resolve({ ok: false, reason: /cancel/i.test(msg) ? "cancel" : "unsupported" });
      },
    });
  });
}

/** 把用户带去本应用的权限设置页。老运行时没这个 API 时静默返回 false，调用方改用文字提示 */
export function openLocationSettings(): boolean {
  const u = uni as unknown as { openAppAuthorizeSetting?: (o: object) => void };
  if (typeof u.openAppAuthorizeSetting !== "function") return false;
  try {
    u.openAppAuthorizeSetting({});
    return true;
  } catch {
    return false;
  }
}

/**
 * 打开系统地图导航到某个点。App 走高德/系统地图，小程序走微信内置地图。
 *
 * 为什么必须有坐标：`uni.openLocation` 只认经纬度，光有一串地址文字打不开地图 ——
 * 这也是买家侧此前只能干看着地址、自己抄进导航软件的原因。
 * 没坐标时**不要显示入口**：一个点了只会打开一片空白（或落在城市中心）的按钮比没有更糟。
 */
export function openLocation(p: { lat: number; lng: number; name?: string; address?: string }): void {
  uni.openLocation({
    latitude: p.lat,
    longitude: p.lng,
    name: p.name ?? "",
    address: p.address ?? "",
    scale: 18,
  });
}

/** E6 整数坐标转小数；任一为空就返回 null（调用方据此隐藏导航入口） */
export function fromE6(latE6?: number | null, lngE6?: number | null): Coords | null {
  return latE6 == null || lngE6 == null ? null : { lat: latE6 / 1e6, lng: lngE6 / 1e6 };
}
