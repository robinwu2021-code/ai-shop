// 端能力：定位 —— 选社区、服务范围判定。
// 拒绝授权时降级为手动搜索，功能不阻塞。
export interface Coords {
  lat: number;
  lng: number;
}

export function getLocation(): Promise<Coords | null> {
  return new Promise((resolve) => {
    uni.getLocation({
      type: "gcj02",
      success: (res) => resolve({ lat: res.latitude, lng: res.longitude }),
      fail: () => resolve(null), // 降级：由用户手动选社区
    });
  });
}
