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
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
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
  /*
   * **按新模型判**（ADR-013 阶段二：fulfillmentReach + serviceAreas）。
   *
   * 此前读的是 `serviceScope / serviceCommunityNos` —— 那两个字段已 deprecated，
   * 店铺页保存的是 `serviceAreas`，后端也不再回填旧字段。
   * 于是店主选完小区、保存成功、C 端确实可见了，**工作台那条红字还在**，
   * 点进去一看又是已经选好的 —— 一条永远消不掉、也无从消起的告警。
   *
   * 空数组的含义由 reach 决定：PICKUP 空 = 谁也看不到；ONSITE / SHIPPING 空 = 不限。
   */
  if (st.fulfillmentReach || st.serviceAreas) {
    return st.fulfillmentReach !== FULFILLMENT_REACH.PICKUP
      || (st.serviceAreas?.length ?? 0) > 0;
  }
  // 老数据回落：后端 V33 之前存的店只有老三档
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
  /*
   * **还没交证照 —— 这条排在最前，也是唯一一条「店开着但一单也不会来」的。**
   *
   * 无证照快速开店的人，店建好了、货也能录，但买家看不到他（后端可见性闸门
   * 按主体状态挡）。不常驻这一条的话，他会以为已经开张了，过几天才发现
   * 一单没有 —— 而那时他既不知道差什么，也不知道去哪补。
   *
   * 不判 `can()`：这是主体级的事，店员看到也无妨（他确实在一家还没开张的店里干活），
   * 而下面两条是权限相关的，见方法注释。
   */
  if (merchant.pendingLicense) {
    list.push({ key: "license", route: ROUTES.apply });
  }
  if (merchant.can("biz:finance") && !canReceive.value) {
    list.push({ key: "payment", route: ROUTES.payment });
  }
  if (merchant.can("biz:store") && !visible.value) {
    list.push({ key: "scope", route: ROUTES.storeScope });
  }
  return list;
});

/**
 * 公告入口右侧那一句：现在挂着什么、什么时候没。
 *
 * <p><b>为什么值得占这一行</b>：公告是这一屏唯一的日频内容，最常见的故障是
 * 「早上挂的今日到货，晚上忘了撤」。此前工作台上只有「公告」两个字，
 * 挂没挂、挂的是哪句，只有点进去才知道 —— 于是没人会去点，也就没人会去撤。
 *
 * <p>审核中优先于正文：那时店铺页上挂的和他以为的不是同一句，这件事更要紧。
 * `store` 是这一页本来就要拉的（三条开张告警都读它），不多发请求。
 */
const noticeValue = computed(() => {
  const st = store.value;
  if (!st) return "";
  if (st.noticePending) return String(t("store.noticeAuditing"));
  const text = (st.announcement ?? "").trim();
  if (!text) return String(t("home.noticeNone"));
  const head = text.length > 10 ? `${text.slice(0, 10)}…` : text;
  const at = st.announcementUntil;
  if (!at) return head;
  const d = new Date(at);
  const sameDay = d.toDateString() === new Date().toDateString();
  return `${head} · ${t("home.noticeUntil", {
    s: sameDay ? String(t("store.ttl.todayAt")) : `${d.getMonth() + 1}/${d.getDate()}`,
  })}`;
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
  if (!merchant.canOperate) return;
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

/**
 * 先开店：填个店名就把店建起来，执照以后再补。
 *
 * <p><b>把它摆在「去入驻」旁边，而不是藏在里面</b>：那张入驻表单要填行业、
 * 主体类型、经营范围、结算账户、传执照 —— 对一个只想先看看这东西能不能用的
 * 街边小店老板，那是一道劝退墙。这条路只问一句「店叫什么」。
 *
 * <p>补证照走的还是入驻表单，服务端会认领这家店 —— 他现在录的商品都还在。
 */
const opening = ref(false);
async function goQuickStart() {
  if (!merchant.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  if (opening.value) return;
  const name = await new Promise<string>((resolve) => {
    uni.showModal({
      title: String(t("home.quickStartTitle")),
      content: String(t("home.quickStartBody")),
      editable: true,
      placeholderText: String(t("home.quickStartPh")),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!name.trim()) return;
  opening.value = true;
  try {
    await api.mQuickStart({ storeName: name.trim() });
    // 重新拉一次资料 —— 状态、门店、权限全变了
    await merchant.loadProfile();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    opening.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="tab.home" tab="home">
    <!-- 未入驻：整屏只讲一件事 —— 去开张 -->
    <view v-if="!merchant.canOperate" class="empty">
      <text class="sh-h1">{{ $t("home.notMerchant") }}</text>
      <text class="sh-muted mt">{{ $t("home.notMerchantHint") }}</text>
      <view class="sh-btn go" @tap="goQuickStart">
        {{ opening ? $t("common.loading") : $t("home.quickStart") }}
      </view>
      <text class="applylink" @tap="goApply">{{ $t("home.goApplyWithLicense") }}</text>
    </view>

    <template v-else>
      <!-- 门店这件事的唯一入口：显示在看哪家店，点进门店管理（切店/改名/开新店） -->
      <biz-store-tag></biz-store-tag>

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

        **两道工序、两个数字、两个去处，不是一张卡赌一个目的地**：
        分拣（备货中→标到货）在前、核销（等人来取）在后，是同一条流水线上
        前后相邻的两步。此前这张卡不管点谁都固定跳核销页——同时有分拣活
        没有核销活时，点进去正好是句"当前没有待核销的订单"，分拣入口
        反而要回首页从待办格子里单独找。数字复用 `todo`（已经在拉了，不多发请求）。
      -->
      <view
        v-if="merchant.isPickupPoint && (merchant.can('biz:verify') || merchant.can('biz:receive'))"
        class="sh-card entry fulfill"
      >
        <text class="sh-h2 fulfill__title">{{ $t("home.fulfillEntry") }}</text>
        <view class="fulfill__row">
          <view v-if="merchant.can('biz:receive')" class="fulfill__half" @tap="open(ROUTES.picking)">
            <text class="fulfill__n sh-num" :class="{ 'is-zero': !todo?.toPick }">{{ todo?.toPick ?? 0 }}</text>
            <text class="sh-muted">{{ $t("home.toPick") }}</text>
          </view>
          <view v-if="merchant.can('biz:verify')" class="fulfill__half" @tap="open(ROUTES.verify)">
            <text class="fulfill__n sh-num" :class="{ 'is-zero': !todo?.toVerify }">{{ todo?.toVerify ?? 0 }}</text>
            <text class="sh-muted">{{ $t("home.toVerify") }}</text>
          </view>
        </view>
      </view>

      <!-- 拆两页（方案 v3）：范围与送货是开店的两个决策；装修与获客是日常内容 -->
      <view v-if="merchant.can('biz:store')" class="sh-card entry entry--kv" @tap="open(ROUTES.storeNotice)">
        <text class="sh-h2">{{ $t("home.noticeEntry") }}</text>
        <text v-if="noticeValue" class="entry__v">{{ noticeValue }}</text>
      </view>

      <view v-if="merchant.can('biz:store')" class="sh-card entry" @tap="open(ROUTES.storeScope)">
        <text class="sh-h2">{{ $t("home.scopeEntry") }}</text>
      </view>
      <view v-if="merchant.can('biz:store')" class="sh-card entry" @tap="open(ROUTES.store)">
        <text class="sh-h2">{{ $t("home.storeEntry") }}</text>
      </view>

      <!--
        **类目与规格并排两个入口。**它们曾经合成一个（「规格配置在分类页里」），
        那时规格页确实只是类目页的附属；现在它已经长成独立的一块 ——
        每个类目用哪几个规格、每个规格用哪几档、还能自己建，都在那一页。
        埋在二级的结果是「找不到」：进类目页、再找一张卡、才到得了。
      -->
      <view v-if="merchant.can('biz:store:admin')" class="sh-card entry" @tap="open(ROUTES.storeCategories)">
        <text class="sh-h2">{{ $t("home.catalogEntry") }}</text>
        <text class="sh-muted">{{ $t("home.catalogEntryHint") }}</text>
      </view>

      <view v-if="merchant.can('biz:goods')" class="sh-card entry" @tap="open(ROUTES.mySpecs)">
        <text class="sh-h2">{{ $t("home.specsEntry") }}</text>
        <text class="sh-muted">{{ $t("home.specsEntryHint") }}</text>
      </view>







      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.marketing)">
        <text class="sh-h2">{{ $t("home.marketingEntry") }}</text>
      </view>

      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.groups)">
        <text class="sh-h2">{{ $t("home.groupEntry") }}</text>
      </view>

      <view v-if="merchant.can('biz:campaign')" class="sh-card entry" @tap="open(ROUTES.quotes)">
        <text class="sh-h2">{{ $t("home.quoteEntry") }}</text>
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
  /* 48rpx 是一整行字的高度，作为「主按钮与上方内容」的距离过头了 */
  margin-top: 28rpx;
}
.blocker {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 14rpx;
  padding: 24rpx;
  border-radius: 32rpx;
  /* 真名是 --sh-warning-tint（此前拼成 --sh-warn-tint，恒走兜底的中性灰 ——
     「还不能收款」这类拦路提示整块退化成灰，看不出是警示） */
  background: var(--sh-warning-tint);
}
.blocker__main {
  flex: 1;
  min-width: 0;
}
.blocker__t {
  display: block;
  font-size: 28rpx;
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
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin: 20rpx 0;
}
.grid__cell {
  flex: 1 1 calc(33.33% - 14rpx);
  min-width: calc(33.33% - 14rpx);
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 20rpx 16rpx;
  text-align: center;
}
.grid__n {
  display: block;
  font-size: 48rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
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
  margin-bottom: 16rpx;
}
.stats__row {
  display: flex;
  margin-top: 16rpx;
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
  margin-bottom: 16rpx;
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
  color: var(--sh-primary-text);
}
/* 入口卡之间只留一条缝：这一列有 6+ 张卡，每张多 12rpx 就少露大半张 */
.entry {
  margin-bottom: 12rpx;
}
/* 带右值的入口：标题在左、现状在右。右边那句可能被挤，所以给它单独收缩 */
.entry--kv {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.fulfill__title {
  display: block;
}
.fulfill__row {
  display: flex;
  margin-top: 16rpx;
}
.fulfill__half {
  flex: 1;
  text-align: center;
}
.fulfill__n {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
  margin-bottom: 6rpx;
}
.fulfill__n.is-zero {
  color: var(--sh-sub);
}
.entry__v {
  flex: 1;
  min-width: 0;
  text-align: right;
  font-size: 24rpx;
  color: var(--sh-sub);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.entry .sh-muted {
  display: block;
  margin-top: 8rpx;
}
/* 未入驻的整屏空态：它带标题与主按钮，不是通用空态那一行灰字，所以留在页面里 */
.applylink {
  display: block;
  margin-top: 24rpx;
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.empty {
  text-align: center;
  padding: 80rpx 40rpx;
}
</style>
