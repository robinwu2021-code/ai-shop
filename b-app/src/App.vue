<script setup lang="ts">
import { onHide, onLaunch, onShow } from "@dcloudio/uni-app";
import { configureShell } from "@ai-shop/ui/shell";
import { startUnreadPolling, stopUnreadPolling, unreadCount } from "@/stores/messages";
import { initPush } from "@shared/ports/push";
import { ROUTES, TABS, TAB_ROUTES } from "@/shared/nav";
import { useThemeStore } from "@ai-shop/ui/stores/theme";
import { useAppStore } from "@ai-shop/ui/stores/app";
import { useMarketStore } from "@ai-shop/ui/stores/market";
import { useMerchantStore } from "@/stores/merchant";
import { useI18n } from "vue-i18n";
import { initFonts } from "@shared/ports/font";
import { setForbiddenHandler, setUnauthorizedHandler } from "@shared/net/http-client";
import { USE_MOCK } from "@/api";
import { restoreDb } from "@shared/mock/db";
import { ensureDemoMerchant, ensureDemoOrders } from "@/api/demo-orders";

const { t } = useI18n();

onLaunch(() => {
  // B 端外壳的角标只有一个：「我的」上的未读消息数（新订单/售后/评价的落点）。
  // 切语言后各页 onShow 自然重取服务端文案，不需要额外副作用
  // defaultSkin=brand：B 端第一次打开就是品牌红（虹选）。已经切过皮肤的用户不受影响 ——
  // 存过的值优先级更高。C 端不传这一项，仍是 fresh。
  configureShell({
    tabs: TABS,
    defaultSkin: "brand",
    badge: (key) => (key === "me" ? unreadCount.value : 0),
  });

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
    uni.showToast({ title: String(t("common.sessionExpired")), icon: "none" });
    setTimeout(() => uni.reLaunch({ url: "/pages/login/index" }), 0);
  });
  /*
   * 被拒了：**多半是老板刚收回了他的权限，而这一页的入口还是旧的**。
   *
   * 后端判权是现算的（老板改完，店员下一个请求就被拦），而端上的 `perms`
   * 只在启动与切店时拉。中间这个窗口里，界面上会留着一个点不动的按钮。
   *
   * 所以被拒的那一下要做两件事：说清楚，然后**把入口收掉** ——
   * 重拉一次 scope，那个按钮就自己消失了，他不会对着它反复点，
   * 也不会以为是功能坏了去找老板。
   */
  setForbiddenHandler(() => {
    /*
     * **这里不弹提示**。实测过：弹了会被页面自己的错误提示盖掉
     * （后端那句话更具体），两条 toast 抢同一个位置只是噪音。
     *
     * 重拉之后页面当场变成「这页不归你管」—— **入口收掉本身就是反馈**，
     * 比多一句话有用：他看得见状态变了，不会对着按钮反复点。
     *
     * **loadScope 而不是 ensureScope**：后者拿到过权限就直接返回，
     * 而这里要的恰恰是「再问一次」—— 幂等在别处是优点，在这里是不刷新。
     */
    void merchant.loadScope();
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
  void merchant.ensureStores().then(() => {
    // 冷启动与登录同一条规则：多店且当前这家不是人选的 → 先去选店页
    if (merchant.isLogin && merchant.needsStorePick) {
      uni.reLaunch({ url: `${ROUTES.storePick}?entry=1` });
    }
  });
  /*
   * 商家资料同理，而且它不只是店名：`isActive` 从 `profile.status` 推出来，
   * 好几处「能不能操作」看的是它 —— 没拉到的时候，求团报价页一个报价入口都没有。
   */
  void merchant.ensureProfile();
  // 演示态：先有店、再有会话、最后补单 —— 顺序反了的话补单时还不知道是哪家店
  if (USE_MOCK) {
    ensureDemoMerchant();
    void merchant.useDemoSession();
    ensureDemoOrders();
  }

  initFonts(); // 远程字体，失败静默降级

  /*
   * 推送点击的落点（ADR-018）。**一条「新订单」点开却停在首页，
   * 与没推没有区别** —— 商家还得自己去翻订单列表。
   *
   * tab 页要用 switchTab：navigateTo 打不开 tab 页，表现是点了没反应，
   * 而「新订单」的落点 /pages/orders/index 恰好就是 tab 页。
   */
  initPush((link) => {
    const path = link.split("?")[0] ?? link;
    if (TAB_ROUTES.has(path)) {
      uni.switchTab({ url: path });
    } else {
      uni.navigateTo({ url: link });
    }
  });
});

// 未读角标 30s 轮询：app 在前台才跑（onHide 停，后台空转是白耗电量与请求），
// 回前台立即刷一次 —— 商家最常见的动作就是「听见手机响，点亮屏幕看一眼」
onShow(startUnreadPolling);
onHide(stopUnreadPolling);
</script>

<style>
/* 全局样式基座两端共用，见 packages/ui/src/styles/base.css */
@import "@ai-shop/ui/styles/base.css";

/* ============================================================================
   B 端密度：比 C 端紧一档

   **为什么只在 B 端**：设计语言原本定的是「留白偏松（西式），信息密度低于国内
   电商惯例」（见 base.css 顶部）—— 那个取舍对 C 端成立：顾客逛店，松一点显精致。
   但 B 端是**作业台**：店主一天要扫几十次订单与商品列表，一屏多一行就少滚一次，
   密度直接等于效率。同一套原语、两种密度，靠变量分开，不复制样式。

   数值只动这四个总开关，不逐页去调 —— 页面里的间距是各写各的，
   改总开关能一次覆盖 28 个页面，且以后再调只改这一处。
   ============================================================================ */
:root,
.sh-root {
  /* 卡片内边距 32→24rpx：375pt 版心里，32rpx(16px) 左右各吃掉 16px，
     加上页边距后内容可用宽只剩 315pt。降一档每张卡横向多出 8pt */
  --sh-pad-card: 24rpx;
  /* 页面左右边距 28→24rpx，与卡片内边距取同值：两个数不一致时，
     卡片边缘与页面边缘会形成一道看不出规律的错位 */
  --sh-pad-page: 24rpx;
  /* 空态 72→48rpx：「没东西可看」的状态本就不该再占掉大半屏 */
  --sh-pad-empty: 48rpx;
  /* 分栏与内容的距离 20→16rpx：chip 自带内边距，20rpx 之后是第二道留白 */
  --sh-gap-tabs: 16rpx;
  /* 次要文字 26→24rpx。正文是 page 默认的 28rpx —— 26 与它只差 1px，
     「商品名」和「下单时间」看着一样重；降到 24 拉开 2px，主次一眼分得出。
     24rpx(12px) 是这套界面的最小字号下限（见 .sh-chip 注释），不再往下。 */
  --sh-fs-sub: 24rpx;
}

/* ============================================================================
   B 端表单件（label + 控件 + 提示）

   为什么是全局而不是组件：这套类名原先在 **11 个页面**里各写一份，且已经开始漂移 ——
   4 个页面是 88rpx/30rpx，3 个页面是 84rpx/28rpx，同一个表单在不同页面高度不一样。
   做成组件的话，控件本身（input/textarea/picker 形态各异）仍要由页面提供，
   而**插槽内容吃不到组件的 scoped 样式**，最后还是要一份全局类 —— 那就直接给全局类。

   ⚠️ **原先这里写着「为什么只在 B 端：C 端几乎没有表单」—— 这个前提是错的。**
   2026-08-27 数了一遍：C 端 6 个页面共 16 个输入框，只是**没走这套类**，
   而是各自定义了一个同名的 `.field`，圆角 24/32、底色 faint/surface、
   字号 26/28/30 三处不一致。它们现在都改用共用的 `.field__input` 了。

   所以现在的分工是：`.field__label / __input / __area / __hint / __head`
   在 `packages/ui` 的 base.css，**两端共用**；只有下面这条 `.field`
   （字段行之间的纵向间距）留在 B 端 —— 它是「一屏排十几个字段」才有的问题。
   ============================================================================ */
/* 字段间距 20rpx：28 是「一行字的高度」，一屏表单排下来会白掉小半屏。
   商家填表多在店里站着填，一屏能看到几个字段直接决定要不要来回滚 */
.field {
  margin-top: 20rpx;
}
/* 88rpx ≈ 44pt，是点按目标的下限；缩到 84 省不出什么，却贴着下限走 */
</style>
