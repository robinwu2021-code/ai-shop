// 端能力的替身。**只补页面真正会碰到的那几个** ——
// 把整个 uni 对象照着文档补全，测试就变成了在测 mock 自己。
import { vi } from "vitest";

/** uni.showModal 默认「点确定」。要测「点取消」的用例自己覆盖它 */
export const uniMock = {
  showToast: vi.fn(),
  showModal: vi.fn((o: { success?: (r: { confirm: boolean }) => void }) =>
    o.success?.({ confirm: true }),
  ),
  navigateTo: vi.fn(),
  switchTab: vi.fn(),
  navigateBack: vi.fn(),
  getLocation: vi.fn(),
  /* 页面加载时改导航栏标题。**不补的话 load() 会在这一行抛出**，
     而抛在 await 链中间的表现是「测试照样绿、后面几步没跑」——
     断言仍然成立，只是成立的理由不是你以为的那个（商品页第一次挂测试时撞到）。 */
  setNavigationBarTitle: vi.fn(),
  setStorageSync: vi.fn(),
  getStorageSync: vi.fn(() => ""),
  removeStorageSync: vi.fn(),
};

// @ts-expect-error 测试环境里没有 uni，这里给一个
globalThis.uni = uniMock;
// 页面绑定后会读它判断返回栈深度
// @ts-expect-error 同上
globalThis.getCurrentPages = () => [{}];

// uni-app 的生命周期钩子在 node 下没有宿主，测试里手动调 load()
/*
 * **onLoad 直接把回调跑掉**，而不是 vi.fn() 空壳。
 *
 * 页面的初始化全在 `onLoad(load)` 里，空壳 mock 会让 mount 之后什么都没发生，
 * 于是每条用例都要去够 `<script setup>` 里的私有函数（够不到），
 * 或者把 load 导出来专门给测试用 —— 那就成了「为测试改产品代码」。
 * 直接执行才是真实时序：挂载即加载。
 */
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: () => unknown) => cb(),
  onShow: vi.fn(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
}));
