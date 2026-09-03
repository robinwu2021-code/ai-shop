/**
 * 两点间距离（米）。
 *
 * <b>这是后端 `OrderServiceImpl.metersBetween` 的同一套算法，逐行对齐。</b>
 * 端上用它把「送不到的地址」提前置灰，后端用它在下单那一刻拦 ——
 * 两处算得不一样的后果是：页面上说能送，点提交被拒，
 * 而用户看到的是一个自相矛盾的界面，没有任何线索。
 *
 * 经度间距随纬度收缩，**不乘 cos 会让高纬度地区多算出几百米** ——
 * 那正好是「送得到」与「送不到」的分界。
 */
const METERS_PER_DEGREE = 111_320;

export function metersBetweenE6(latE6: number, lngE6: number, otherLatE6: number, otherLngE6: number): number {
  const dLat = ((latE6 - otherLatE6) / 1e6) * METERS_PER_DEGREE;
  const midLat = (((latE6 + otherLatE6) / 2e6) * Math.PI) / 180;
  const dLng = ((lngE6 - otherLngE6) / 1e6) * METERS_PER_DEGREE * Math.cos(midLat);
  return Math.round(Math.sqrt(dLat * dLat + dLng * dLng));
}

/** 收货点与自送圆心的最小形状。三个都可能缺 —— 缺就是「这条规则不成立」 */
export interface DeliveryOrigin {
  deliveryLatE6?: number | null;
  deliveryLngE6?: number | null;
  deliveryRadiusM?: number | null;
}

/**
 * 这个收货点在不在这家店的自送范围内。
 *
 * <b>三条放行必须与后端 `requireWithinDeliveryRadius` 一字不差</b>：
 * 地址没坐标、门店没标点、半径 ≤ 0（那是「不限距离」的表达）——一律 true。
 * 端上比后端严，会把本来下得成的单挡在门外，
 * 而那种单用户永远查不出为什么下不了。
 */
export function withinDeliveryRange(
  origin: DeliveryOrigin,
  addr: { latE6?: number | null; lngE6?: number | null },
): boolean {
  if (addr.latE6 == null || addr.lngE6 == null) return true;
  if (origin.deliveryLatE6 == null || origin.deliveryLngE6 == null) return true;
  const radius = origin.deliveryRadiusM ?? 0;
  if (radius <= 0) return true;
  return metersBetweenE6(origin.deliveryLatE6, origin.deliveryLngE6, addr.latE6, addr.lngE6) <= radius;
}
