<script setup lang="ts">
// 工作台（B-10.1 + B-11 汇总）。
//
// 设计要点：**数字即入口**。商家早上打开 App 只想知道「有几件事要我做」，
// 不需要 Banner、不需要推荐。所以第一屏是待办数字网格，点数字直接进对应列表。
// 这与 C 端首页（逛）是相反的信息架构，不复用页面。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useI18n } from "vue-i18n";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { SERVICE_SCOPE } from "@shared/utils/constants";
import type { MerchantStats, MerchantTodo, PaymentApplyment, StoreProfile } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const todo = ref<MerchantTodo | null>(null);
const stats = ref<MerchantStats | null>(null);

/*
 * 开张之后有**三件互相独立的事**，商家最常问的也正是这三个问题：
 *   我能开张了吗？ → 主体 ACTIVE
 *   我能收钱了吗？ → 进件 ACTIVE
 *   我的店能被看到吗？ → 服务范围非空
 *
 * 它们各自可失败、互不阻塞：进件没过照样能上架，范围空着照样能收钱。
 * 此前这三个状态散在三个页面里，工作台一个都不提示 ——
 * 于是商家上完架等订单，实际卡在其中一条，而**两者都不报错**。
 */
const payments = ref<PaymentApplyment[]>([]);
const store = ref<StoreProfile | null>(null);

const canReceive = computed(() => payments.value.some((p) => p.canReceiveMoney));
const visible = computed(() => {
  const st = store.value;
  if (!st) return false;
  // 「仅本社区」却一个都没选 = 对谁都不可见；其余两档天然可见
  return st.serviceScope !== SERVICE_SCOPE.COMMUNITY || st.serviceCommunityNos.length > 0;
});

/**
 * 只在「有问题」时出现。全通过还挂一张绿卡，是每天都要划过去的噪音。
 *
 * <b>只给能处理它的人看</b>。这两张卡的数据来自 `/biz/merchant/payment` 与 `/biz/store`，
 * 而店员对这两个端点必被 70006 拒 —— 拒绝会 catch 成空值，空值又恰好长得像
 * 「没进件」「没选社区」。于是店员的工作台上永远挂着两条他既看不懂也点不开的红字，
 * 点「去处理」进去还是 70006。
 *
 * **把权限不足渲染成业务待办是最坏的一种失败**：它不像故障，像是店里真出了事。
 */
const blockers = computed(() => {
  const list: { key: string; route: string }[] = [];
  if (merchant.can("biz:finance") && !canReceive.value) {
    list.push({ key: "payment", route: ROUTES.payment });
  }
  if (merchant.can("biz:store") && !visible.value) {
    list.push({ key: "scope", route: ROUTES.store });
  }
  return list;
});

/**
 * 待办格子。数字为 0 的也留着 —— 位置固定，商家才能形成肌肉记忆。
 *
 * <b>每个格子跟着它自己的权限走</b>，不是整块一起给。这 7 个数分属 5 个权限，
 * 而点进去的页面各有各的判权：画一个点进去报 70006 的格子，
 * 比不画它更糟 —— 它每天都在那儿，每天都点不开。
 */
const cells = computed(() => {
  const t = todo.value;
  if (!t) return [];
  // 显式标注：否则 TS 会把 route 收窄成 base 里那几个字面量，splice 进来的核销/分拣路由报错
  const base: { key: string; n: number; route: string; perm: string }[] = [
    { key: "toShip", n: t.toShip, route: ROUTES.orders, perm: "biz:ship" },
    { key: "toDeliver", n: t.toDeliver, route: ROUTES.delivery, perm: "biz:ship" },
    /*
     * 待备货：把货送到买家选的那个自提点去。**与「待分拣」是两码事** ——
     * 分拣是在自己的点上分货，备货是把货送出门，而买家常常选别家的点。
     * 之前把 toPick 改成按自提点算之后，这件事在工作台上完全消失了：
     * 有活、没数字、也没入口。
     */
    { key: "toStock", n: t.toStock, route: ROUTES.orders, perm: "biz:ship" },
    { key: "afterSale", n: t.afterSale, route: ROUTES.afterSale, perm: "biz:aftersale" },
    { key: "toReply", n: t.toReply, route: ROUTES.reviews, perm: "biz:review" },
  ];
  // 不承接自提点的商家不该看到核销/分拣 —— 那是自提点承接方的活（ADR-005）
  if (merchant.isPickupPoint) {
    base.splice(2, 0, { key: "toVerify", n: t.toVerify, route: ROUTES.verify, perm: "biz:verify" });
    base.splice(3, 0, { key: "toPick", n: t.toPick, route: ROUTES.picking, perm: "biz:receive" });
  }
  return base.filter((c) => merchant.can(c.perm));
});

const ownedRate = computed(() =>
  stats.value ? `${Math.round(stats.value.ownedTrafficRate * 100)}%` : "—",
);

async function load() {
  await merchant.loadProfile().catch(() => null);
  if (!merchant.isActive) return;
  // 门店要先定下来：它决定后面这一屏所有数字属于哪家店
  await merchant.loadStores();
  /*
   * 再等权限到位。`loadStores` 里的 `switchStore` 只是**触发**了 loadScope
   * （`void this.loadScope()`，没有 await），所以紧接着读 `can()` 是一场竞态 ——
   * 输了的表现是：老板的经营数据/进件状态这一屏静悄悄地少几块，刷新一下又有了。
   */
  await merchant.ensureScope();
  /*
   * 三段状态与待办一起取：分开取的话「能不能收钱」会晚一拍出现，
   * 而那一拍里工作台看着是全绿的。
   *
   * **四条都要各自 catch**。前两条原先没有，而它们各需要一个权限
   * （待办要 biz:order:view、经营数据要 biz:customer）——
   * 于是理货员一进工作台，mTodo 被 70006 拒，Promise.all 整体 reject，
   * 四个值一个都赋不上，**整屏空白**。没有报错，也没有入口，
   * 看起来就像这家店什么都没有。
   *
   * 拿不到就是拿不到：下面每一块都自己判空，少一块比整屏没了强得多。
   */
  /*
   * **没权限的先别发**。catch 已经保证了不会整屏空白，但对店员来说
   * 后三条是每次进首页都必然 403 的请求 —— 日志里三条噪音、首屏多三个来回，
   * 而它们的结果本来就不会被画出来（`blockers` 与 `stats` 卡片各自判过 `can()`）。
   */
  [todo.value, stats.value, payments.value, store.value] = await Promise.all([
    api.mTodo().catch(() => null),
    merchant.can("biz:customer") ? api.mStats().catch(() => null) : null,
    merchant.can("biz:finance") ? api.mPayments().catch(() => []) : [],
    merchant.can("biz:store") ? api.mStore().catch(() => null) : null,
  ]);
}

async function pickStore(storeNo: string) {
  if (storeNo === merchant.storeNo) return;
  merchant.switchStore(storeNo);
  // 切完立刻重取：不重取的话数字还是上一家店的，而人已经在看新店了
  await load();
}

function open(route: string) {
  if (!route) {
    // 未交付的格子给明确说法，不做静默无响应 —— 点了没反应会被当成 bug
    uni.showToast({ title: t("home.laterBatch"), icon: "none" });
    return;
  }
  // tabBar 页只能 switchTab，普通页只能 navigateTo，用错会静默失败
  if (route === ROUTES.orders) uni.switchTab({ url: route });
  else uni.navigateTo({ url: route });
}

function goApply() {
  uni.navigateTo({ url: merchant.isLogin ? ROUTES.apply : ROUTES.login });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="tab.home" tab="home">
    <!-- 未入驻：整屏只讲一件事 —— 去开张 -->
    <view v-if="!merchant.isActive" class="empty">
      <text class="sh-h1">{{ $t("home.notMerchant") }}</text>
      <text class="sh-muted mt">{{ $t("home.notMerchantHint") }}</text>
      <view class="sh-btn go" @tap="goApply">{{ $t("home.goApply") }}</view>
    </view>

    <template v-else>
      <!--
        门店切换器放在工作台**最上面**：这一屏所有数字都属于某一家店，
        不先说清是哪家，「今天 3 单」这种话就没有意义。
        只有一家店时不显示 —— 永远只有一个选项的切换器是纯噪音。
      -->
      <view v-if="merchant.multiStore" class="stores">
        <text
          v-for="s in merchant.stores"
          :key="s.storeNo"
          class="sh-chip stores__i"
          :class="{ 'is-on': s.storeNo === merchant.storeNo }"
          @tap="pickStore(s.storeNo)"
        >
          {{ s.name }}
        </text>
      </view>

      <text class="sh-h1">{{ $t("home.greeting") }}</text>

      <!--
        开张之后卡在哪，这里直说。**只在有问题时出现** ——
        全通过还挂一张绿卡，是每天都要划过去的噪音。
      -->
      <view v-for="b in blockers" :key="b.key" class="blocker" @tap="open(b.route)">
        <view class="blocker__main">
          <text class="blocker__t">{{ $t(`home.blocker.${b.key}`) }}</text>
          <text class="blocker__d">{{ $t(`home.blockerHint.${b.key}`) }}</text>
        </view>
        <text class="blocker__go">{{ $t("home.blockerGo") }}</text>
      </view>

      <view class="grid">
        <view v-for="c in cells" :key="c.key" class="grid__cell" @tap="open(c.route)">
          <text class="grid__n sh-num" :class="{ 'is-zero': !c.n }">{{ c.n }}</text>
          <text class="grid__label">{{ $t(`home.${c.key}`) }}</text>
        </view>
      </view>

      <view v-if="stats" class="sh-card stats">
        <text class="sh-h2">{{ $t("home.today") }}</text>
        <view class="stats__row">
          <view class="stats__item">
            <text class="stats__v sh-num">{{ stats.todayOrders }}</text>
            <text class="sh-muted">{{ $t("home.orders") }}</text>
          </view>
          <view class="stats__item">
            <text class="stats__v sh-num">{{ money(stats.todayGmvMinor, stats.currency) }}</text>
            <text class="sh-muted">{{ $t("home.gmv") }}</text>
          </view>
          <view class="stats__item">
            <text class="stats__v sh-num">{{ stats.rating || "—" }}</text>
            <text class="sh-muted">{{ $t("home.rating") }}</text>
          </view>
        </view>
      </view>

      <!-- 自带客流占比：这是商家最该关心的数字，它直接决定费率档（ADR-004 §6） -->
      <view v-if="stats" class="sh-card owned">
        <view class="owned__row">
          <text class="sh-h2">{{ $t("home.ownedTraffic") }}</text>
          <text class="owned__v sh-num">{{ ownedRate }}</text>
        </view>
        <text class="sh-muted">{{ $t("home.ownedTrafficHint") }}</text>
      </view>

      <!--
        入口按 perms 裁剪。**后端拒绝是安全边界，这里只是体验** ——
        两者都要有：只做后端，员工会看到一堆点了报 70006 的入口；
        只做这里，那不是安全。

        判权一律用 merchant.can()，不要按角色名自己推 ——
        两处各推一次迟早分岔，而分岔的表现是「看得见但点了报错」。
      -->
      <!--
        履约台把核销、分拣、到货确认放在一起，而这三件事是**三个权限**。
        入口只判 biz:verify 的话，理货员（只有 biz:receive）一个入口都看不到 ——
        而分拣正是他今天唯一要干的活。有权限没有入口，和没权限一样。
        落地页也要跟着挑：他打不开核销页。
      -->
      <view
        v-if="merchant.isPickupPoint && (merchant.can('biz:verify') || merchant.can('biz:receive'))"
        class="sh-card entry"
        @tap="open(merchant.can('biz:verify') ? ROUTES.verify : ROUTES.picking)"
      >
        <text class="sh-h2">{{ $t("home.fulfillEntry") }}</text>
        <text class="sh-muted">{{ $t("home.fulfillEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:store')" class="sh-card entry" @tap="open(ROUTES.store)">
        <text class="sh-h2">{{ $t("home.storeEntry") }}</text>
        <text class="sh-muted">{{ $t("home.storeEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:store:admin')" class="sh-card entry" @tap="open(ROUTES.stores)">
        <text class="sh-h2">{{ $t("home.storesEntry") }}</text>
        <text class="sh-muted">{{ $t("home.storesEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:store:admin')" class="sh-card entry" @tap="open(ROUTES.staff)">
        <text class="sh-h2">{{ $t("home.staffEntry") }}</text>
        <text class="sh-muted">{{ $t("home.staffEntryHint") }}</text>
      </view>

      <!-- 收款设置：与店铺设置并列而不是塞在里面 —— 「店能开」与「钱能收」是两件事 -->
      <view v-if="merchant.can('biz:finance')" class="sh-card entry" @tap="open(ROUTES.payment)">
        <text class="sh-h2">{{ $t("home.paymentEntry") }}</text>
        <text class="sh-muted">{{ $t("home.paymentEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.marketing)">
        <text class="sh-h2">{{ $t("home.marketingEntry") }}</text>
        <text class="sh-muted">{{ $t("home.marketingEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.groups)">
        <text class="sh-h2">{{ $t("home.groupEntry") }}</text>
        <text class="sh-muted">{{ $t("home.groupEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.quotes)">
        <text class="sh-h2">{{ $t("home.quoteEntry") }}</text>
        <text class="sh-muted">{{ $t("home.quoteEntryHint") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  display: block;
  margin-top: 16rpx;
}
.go {
  margin-top: 48rpx;
}
.stores {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-bottom: 24rpx;
}
.stores__i.is-on {
  background: var(--sh-primary);
  color: #fff;
}
.blocker {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 24rpx;
  padding: 24rpx;
  border-radius: 32rpx;
  background: var(--sh-warn-tint, var(--sh-faint));
}
.blocker__main {
  flex: 1;
  min-width: 0;
}
.blocker__t {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.blocker__d {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.blocker__go {
  flex-shrink: 0;
  font-size: 26rpx;
  color: var(--sh-primary);
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  margin: 28rpx 0;
}
.grid__cell {
  flex: 1 1 calc(33.33% - 14rpx);
  min-width: calc(33.33% - 14rpx);
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx 20rpx;
  text-align: center;
}
.grid__n {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-primary);
  line-height: 1.2;
}
.grid__n.is-zero {
  color: var(--sh-faint);
}
.grid__label {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.stats {
  margin-bottom: 24rpx;
}
.stats__row {
  display: flex;
  margin-top: 24rpx;
}
.stats__item {
  flex: 1;
  text-align: center;
}
.stats__v {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.owned {
  margin-bottom: 24rpx;
  background: var(--sh-primary-tint);
}
.owned__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.owned__v {
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-primary);
}
.entry {
  margin-bottom: 24rpx;
}
.entry .sh-muted {
  display: block;
  margin-top: 8rpx;
}
/* 未入驻的整屏空态：它带标题与主按钮，不是通用空态那一行灰字，所以留在页面里 */
.empty {
  text-align: center;
  padding: 120rpx 40rpx;
}
</style>
