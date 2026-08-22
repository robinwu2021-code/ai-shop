// 端能力：定位 —— 选社区、服务范围判定。
// 拒绝授权时降级为手动搜索，功能不阻塞。
export interface Coords {
  lat: number;
  lng: number;
}

export function getLocation(): Promise<Coords | null> {
  /*
   * App 端走系统定位（离线包不带高德/百度 SDK），而系统定位只给 wgs84 ——
   * 传 gcj02 会被运行时直接拒，表现为 fail 回调、拿不到任何坐标。
   * 两套坐标系在国内差几十到几百米，社区围栏是 1000 米，落点判定够用。
   */
  let type: "gcj02" | "wgs84" = "gcj02";
  // #ifdef APP-PLUS
  type = "wgs84";
  // #endif
  return new Promise((resolve) => {
    uni.getLocation({
      type,
      success: (res) => resolve({ lat: res.latitude, lng: res.longitude }),
      fail: () => resolve(null), // 降级：由用户手动选社区
    });
  });
}
