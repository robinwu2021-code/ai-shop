// 端能力：地点搜索（联想）—— 输入一串地名，拿回带坐标的候选。
//
// App 端走**包里的原生高德 SDK**（`plus.maps.Search`），这条路不需要后端的高德
// Web 服务 key：离线包已经带了 AMap3DMap/map-amap 两个 aar，key 在原生清单里。
// 其它端（H5 / 小程序）没有 plus，调用方自己回退到后端 `/biz/geo/tips`。

export interface PlaceHit {
  name: string;
  address: string;
  city: string;
  /** gcj02 —— 与 getLocation、后端围栏同一套坐标系 */
  lat: number;
  lng: number;
}

interface PlusPoint {
  latitude: number;
  longitude: number;
}
interface PlusPoi {
  name?: string;
  address?: string;
  city?: string;
  point?: PlusPoint;
}
interface PlusPointCtor {
  new (lng: number, lat: number): object;
}
interface PlusSearch {
  onPoiSearchComplete: ((state: number, result: { poiList?: PlusPoi[] }) => void) | null;
  setPageCapacity?: (n: number) => void;
  poiSearchInCity: (city: string, keyword: string) => void;
  poiSearchNearBy: (keyword: string, point: object, radius: number) => void;
}

/**
 * 一个进程一个 Search 实例：每次 new 都会拉起一次原生对象，输入框逐字联想时会堆很多。
 * 代价是回调是**实例级**的（后一次查询会顶掉前一次的 handler），所以下面用 seq 丢弃过期结果。
 */
let search: PlusSearch | null | undefined;
let seq = 0;

function instance(): PlusSearch | null {
  if (search !== undefined) return search;
  search = null;
  // #ifdef APP-PLUS
  try {
    const maps = (plus as unknown as { maps?: { Search?: new () => PlusSearch } }).maps;
    if (maps?.Search) {
      const s = new maps.Search();
      s.setPageCapacity?.(10);
      search = s;
    }
  } catch {
    search = null;
  }
  // #endif
  return search;
}

/**
 * 在一个点周围搜。**这是「输名字找小区」的正确形状** ——
 * `poiSearchInCity` 的 city 只是偏好不是约束：在深圳搜「福安」会返回福建的福安市；
 * 而把街道名拼进关键词又会把「XX街道办事处」顶到前面，真小区一个都排不进来（实测）。
 * 用居委会/街道的坐标当圆心、几公里为半径，「福安」才会返回福安雅园。
 */
export function searchPlacesNearNative(
  keyword: string,
  center: { lat: number; lng: number },
  radiusM = 5000,
): Promise<PlaceHit[] | null> {
  const s = instance();
  const kw = keyword.trim();
  if (!s || !kw) return Promise.resolve(s ? [] : null);

  const mine = ++seq;
  return new Promise((resolve) => {
    let done = false;
    const finish = (v: PlaceHit[] | null) => {
      if (done) return;
      done = true;
      resolve(v);
    };
    s.onPoiSearchComplete = (state, result) => {
      if (mine !== seq) return;
      if (state !== 0) return finish([]);
      finish(toHits(result?.poiList ?? []));
    };
    try {
      const maps = (plus as unknown as { maps?: { Point?: PlusPointCtor } }).maps;
      if (!maps?.Point) return finish([]);
      // 高德的点是 (经度, 纬度) —— 顺序与本仓库到处用的 lat/lng 相反
      s.poiSearchNearBy(kw, new maps.Point(center.lng, center.lat), radiusM);
    } catch {
      finish([]);
    }
    setTimeout(() => finish([]), 8000);
  });
}

function toHits(list: PlusPoi[]): PlaceHit[] {
  return list
    .filter((p) => p.point && typeof p.point.latitude === "number")
    .map((p) => ({
      name: p.name ?? "",
      address: p.address ?? "",
      city: p.city ?? "",
      lat: p.point!.latitude,
      lng: p.point!.longitude,
    }));
}

/** 这个端支不支持原生搜索。false 时调用方该回退到后端 tips */
export function canSearchPlaces(): boolean {
  return instance() !== null;
}

/**
 * 按城市搜地点。`city` 给空就全国搜 —— 高德允许，但结果会散，能给城市尽量给。
 *
 * 返回 `null` 表示**这个端不支持**（与「搜到了 0 条」区分开：前者要回退到后端，后者是真没有）。
 * 超时 8 秒兜底：原生回调偶尔不来（无网络时），不兜的话联想框会一直转。
 */
export function searchPlacesNative(keyword: string, city?: string): Promise<PlaceHit[] | null> {
  const s = instance();
  const kw = keyword.trim();
  if (!s || !kw) return Promise.resolve(s ? [] : null);

  const mine = ++seq;
  return new Promise((resolve) => {
    let done = false;
    const finish = (v: PlaceHit[] | null) => {
      if (done) return;
      done = true;
      resolve(v);
    };
    s.onPoiSearchComplete = (state, result) => {
      // 过期的那次查询：用户又敲了一个字，结果已经没人要了
      if (mine !== seq) return;
      if (state !== 0) return finish([]);
      finish(toHits(result?.poiList ?? []));
    };
    try {
      s.poiSearchInCity(city ?? "", kw);
    } catch {
      finish([]);
    }
    setTimeout(() => finish([]), 8000);
  });
}
