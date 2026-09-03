// 端能力：读剪贴板 —— 地址页的「粘贴识别」用。
//
// **永不 reject。** 读不到剪贴板的原因太多（用户拒权、浏览器要一次手势、
// 剪贴板本来就是空的），而调用方对这几种的处理是同一个：当作没读到，让他手填。
// 抛异常只会逼每个调用方写一遍同样的 try/catch。
export function readClipboard(): Promise<string> {
  return new Promise((resolve) => {
    try {
      uni.getClipboardData({
        success: (res) => resolve(String(res?.data ?? "")),
        fail: () => resolve(""),
      });
    } catch {
      resolve("");
    }
  });
}
