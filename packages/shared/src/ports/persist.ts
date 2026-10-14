// 端能力：pinia 持久化用的存储。
//
// `pinia-plugin-persistedstate` 默认写 **`localStorage`**，而 **App 运行时没有它** ——
// 于是所有走这个插件持久化的 store 在打包的 App 里**静默失效**（存不下、也读不回）：
// H5 里一切正常，只有 App 冷启后掉登录态、清空购物车、社区选择重置。
//
// `uni.getStorageSync/setStorageSync` 三端都在，用它做适配器 → App / H5 / 小程序一致。
// 两个 app 的 `main.ts` 都用这一份，别各写各的（写歪一处就是一端悄悄不持久化）。
export const uniPersistStorage = {
  getItem: (key: string): string | null => {
    const v = uni.getStorageSync(key);
    // uni 取不到时返回空串；插件要 null 才当「没存过」，返回 "" 会被当成空 JSON 解析失败
    return v === "" || v == null ? null : (v as string);
  },
  setItem: (key: string, value: string): void => {
    uni.setStorageSync(key, value);
  },
};
