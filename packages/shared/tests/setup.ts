// shared 层大量代码跑在 uni 运行时里（storage / 系统信息）。
// 测试环境没有 uni，这里给一个最小内存实现 —— 只需满足被测代码真正调用的那几个方法。
const store = new Map<string, string>();

const uniStub = {
  getStorageSync: (k: string) => store.get(k) ?? "",
  setStorageSync: (k: string, v: string) => void store.set(k, v),
  removeStorageSync: (k: string) => void store.delete(k),
  getSystemInfoSync: () => ({ platform: "devtools" }),
};

// eslint-disable-next-line @typescript-eslint/no-explicit-any
(globalThis as any).uni = uniStub;

/** 供测试清空「storage」，避免用例之间互相污染 */
export function clearStorage(): void {
  store.clear();
}
