<script setup lang="ts">
import { onLaunch } from "@dcloudio/uni-app";
import { configureShell } from "@ai-shop/ui/shell";
import { TABS } from "@/shared/nav";
import { useThemeStore } from "@ai-shop/ui/stores/theme";
import { useAppStore } from "@ai-shop/ui/stores/app";
import { useMarketStore } from "@ai-shop/ui/stores/market";
import { useMerchantStore } from "@/stores/merchant";
import { initFonts } from "@shared/ports/font";
import { setUnauthorizedHandler } from "@shared/net/http-client";
import { USE_MOCK } from "@/api";
import { restoreDb } from "@shared/mock/db";
import { ensureDemoMerchant, ensureDemoOrders } from "@/api/demo-orders";

onLaunch(() => {
  // B 端外壳只需要一份导航：没有购物车，也就没有角标、飞入动效与落点登记。
  // 切语言后各页 onShow 自然重取服务端文案，不需要额外副作用
  configureShell({ tabs: TABS });

  // mock 的种子数据与 C 端同源（@shared/mock/db）；运行时状态按 origin 隔离，
  // 两端各持一份（见 TDD-b-app §4.4）。
  if (USE_MOCK) restoreDb();
  useMarketStore().init(); // 货币与时区，money/datetime 依赖它
  useThemeStore().init(); // 皮肤 + 明暗
  useAppStore().init(); // 语言 + RTL
  const merchant = useMerchantStore();
  merchant.restore(); // 商家登录态

  /*
   * 登录失效时去登录页。**注册在壳上，因为 401 可能从任何一个请求回来** ——
   * 原先这段挂在 `loadScope` 的 catch 里，只有 `/biz/scope` 那一个请求算数：
   * 从首页进来会跳，而在商品页点保存收到的 401 什么也不发生。同一件事两种表现。
   *
   * reLaunch 而不是 navigateTo：登录态没了，栈里剩下的页面每一张都会渲染成
   * 「这页不归你管 —— 让店主给你加个角色」，而真相只是要重新登录一次。
   *
   * 再延一个宏任务：最常见的触发点是下面那两个 ensure，那一刻首页还没挂载，
   * 此时发起的跳转会被直接丢掉 —— 实测两次，navigateTo 无效，reLaunch 也无效。
   */
  setUnauthorizedHandler(() => {
    merchant.logout();
    uni.showToast({ title: "登录已失效，请重新登录", icon: "none" });
    setTimeout(() => uni.reLaunch({ url: "/pages/login/index" }), 0);
  });
  /*
   * 权限跟着登录态一起恢复。**必须在这里，不能靠页面自己调** ——
   * 它原先只挂在首页的 loadStores 上，于是刷新在商品页时 perms 是空的，
   * 而空 perms 下 can() 全 false：老板的新建/编辑/上下架/改库存一起消失。
   * 判权的默认值是拒绝，所以「判权状态没加载」会把界面自己锁死。
   */
  void merchant.ensureScope();
  /*
   * 门店列表同理：它只有首页会拉，于是刷新在商品页时 stores 是空的 ——
   * multiStore 变 false，门店切换条整条消失，而当前门店号还在本地存着照发。
   * 页面显示的是那家店的库存，界面上却没有一处说明「你在看哪家店」。
   */
  void merchant.ensureStores();
  // 演示态：先有店、再有会话、最后补单 —— 顺序反了的话补单时还不知道是哪家店
  if (USE_MOCK) {
    ensureDemoMerchant();
    void merchant.useDemoSession();
    ensureDemoOrders();
  }

  initFonts(); // 远程字体，失败静默降级
});
</script>

<style>
/* 全局样式基座两端共用，见 packages/ui/src/styles/base.css */
@import "@ai-shop/ui/styles/base.css";

/* ============================================================================
   B 端表单件（label + 控件 + 提示）

   为什么是全局而不是组件：这套类名原先在 **11 个页面**里各写一份，且已经开始漂移 ——
   4 个页面是 88rpx/30rpx，3 个页面是 84rpx/28rpx，同一个表单在不同页面高度不一样。
   做成组件的话，控件本身（input/textarea/picker 形态各异）仍要由页面提供，
   而**插槽内容吃不到组件的 scoped 样式**，最后还是要一份全局类 —— 那就直接给全局类。

   为什么只在 B 端：C 端几乎没有表单（下单流程是选择而非填写），
   放进 packages/ui 等于为「只有一端用的东西」建平台资产。
   ============================================================================ */
/* 字段间距 20rpx：28 是「一行字的高度」，一屏表单排下来会白掉小半屏。
   商家填表多在店里站着填，一屏能看到几个字段直接决定要不要来回滚 */
.field {
  margin-top: 20rpx;
}
.field__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-bottom: 12rpx;
}
/* 88rpx ≈ 44pt，是点按目标的下限；缩到 84 省不出什么，却贴着下限走 */
.field__input {
  height: 88rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 30rpx;
  color: var(--sh-ink);
}
.field__area {
  width: 100%;
  box-sizing: border-box;
  height: 140rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.field__hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
}
</style>
