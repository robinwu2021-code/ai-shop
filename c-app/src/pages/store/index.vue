<script setup lang="ts">
// 门店门户（TDD-C端门店化与门店门户 s03–s07）。**单位是门店** —— 门头是门店名，
// 主体名只在「店铺」页签最后一行「经营主体与资质」里露面（电商法 §15 要求亮照）。
//
// 仍然是**交易页，不是介绍页**：老客三步下单（打开 → 我常买 → 结算）。左分类右列表是
// 买菜类门店最熟悉的样子；左栏第一格是「我常买」（买过的人才有），没买过从「热卖」开始。
//
// 进这一页的四条路，最后都落到**同一个门户**：
//   1. 店铺页 / 搜索 / 商品页进店 —— 带 `no`（门店号）与 `from`
//   2. 分享链接 —— `no` + `from=SHARE` + `inviterNo`
//   3. 扫印在店里的码 —— 微信把码里的 scene（店铺码）带回来，手上**只有码**
//   4. 老链接 / 旧版小程序 —— 带 `merchantNo`（主体号），服务端落到默认门店
// 不经过首页与选社区：扫码的人是来买东西的，游客可逛，加购时再引导登录。
import { computed, ref } from "vue";
import { onLoad, onShareAppMessage, onShareTimeline } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { fromE6, openLocation } from "@shared/ports/location";
import { requestSubscribe, SUBSCRIBE_TMPL } from "@shared/ports/push";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { useLocationStore } from "@/stores/location";
import { ROUTES } from "@shared/utils/constants";
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import { rememberStore } from "@/shared/store-choice";
import { money } from "@shared/utils/money";
import { distance } from "@shared/utils/format";
import { hourMinute, isoDate } from "@shared/utils/datetime";
import { buildShareMessage, buildShareTimeline, shareImageUrl } from "@shared/ports/share";
import { thumb } from "@shared/utils/media-thumb";
import type { FrequentItem, Goods, Review, StoreHome, StoreVisitSource } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();
const location = useLocationStore();

/** 链接上的编号：门店号（ST…）或老链接的主体号（M…）。取数后换成真正的门店号 */
const no = ref("");
const data = ref<StoreHome | null>(null);
const frequent = ref<FrequentItem[]>([]);
const keyword = ref("");
const busy = ref(false);
/** 这次没取到。**与「这家店不存在」是两件事** —— 拉不到就给重试，不给白屏 */
const failed = ref(false);

/** 门户的门店号。老链接进来时服务端已落到默认门店，以它回的为准 */
const storeNo = computed(() => data.value?.portal?.storeNo ?? "");
const entityNo = computed(() => data.value?.merchant.merchantNo ?? "");
/** 门头的名字：门店名；主体一家店都没有（portal 为空）时才退回主体名 */
const storeName = computed(() => data.value?.portal?.storeName ?? data.value?.merchant.name ?? "");

/**
 * 暂停营业（READONLY / 平台下线）。**扫码进来的老客要知道「店暂停了」，不是「链接坏了」** ——
 * 所以页面照开、商品压淡不可加购，并给同品牌最近的营业店（s07）。
 */
const closed = computed(() => !!data.value?.closed);

// ---------------------------------------------------------------- 标签页

type Tab = "goods" | "reviews" | "info";
const tab = ref<Tab>("goods");
const tabs = computed(() => [
  { key: "goods" as const, label: String(t("store.tabGoods")) },
  {
    key: "reviews" as const,
    label: data.value?.portal?.ratingCount
      ? `${t("store.tabReviews")} ${data.value.portal.ratingCount}`
      : String(t("store.tabReviews")),
  },
  { key: "info" as const, label: String(t("store.tabInfo")) },
]);

// ---------------------------------------------------------------- 商品：左分类右列表

const hasFrequent = computed(() => frequent.value.some((f) => f.times > 0));

/**
 * 左栏：「我常买」（买过的人才有）→「热卖」→ 店主排的货架。
 * 货架是店主自己排的顺序、自己起的名字（「本地时鲜」而不是「蔬菜」）。
 */
const rail = computed(() => {
  const out: { key: string; label: string }[] = [];
  if (hasFrequent.value) out.push({ key: "@frequent", label: String(t("store.frequent")) });
  out.push({ key: "@hot", label: String(t("store.hot")) });
  for (const c of data.value?.categories ?? []) out.push({ key: c.categoryNo, label: c.name });
  return out;
});
/** 空 = 还没点过，取左栏第一格（老客是「我常买」，新访客是「热卖」） */
const picked = ref("");
const current = computed(() => picked.value || rail.value[0]?.key || "@hot");

/**
 * 右栏的商品。店内搜索跨全部分类 —— 他搜「番茄」时不该因为左边停在「粮油」而搜不到。
 * 「热卖」按销量排；售罄的不藏，压淡（藏起来他会以为这家店没有）。
 */
const listed = computed(() => {
  const all = data.value?.goods ?? [];
  const k = keyword.value.trim().toLowerCase();
  if (k) {
    return all.filter((g) => g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k));
  }
  if (current.value === "@hot") return [...all].sort((a, b) => (b.sales ?? 0) - (a.sales ?? 0));
  return all.filter((g) => g.categoryNo === current.value);
});
const showFrequent = computed(() => !keyword.value.trim() && current.value === "@frequent");

function soldOut(g: Goods) {
  return g.skus.every((s) => (s.stock ?? 0) <= 0);
}

// ---------------------------------------------------------------- 评价（按门店）

const reviews = ref<Review[]>([]);
const reviewsLoaded = ref(false);
async function loadReviews() {
  if (reviewsLoaded.value || !storeNo.value) return;
  try {
    reviews.value = await api.reviewList({ storeNo: storeNo.value, size: 20 });
  } finally {
    reviewsLoaded.value = true;
  }
}
function switchTab(k: string) {
  tab.value = k as Tab;
  if (k === "reviews") void loadReviews();
}

// ---------------------------------------------------------------- 取数与进店

const fromParam = ref("");

/** 链接上的 from → 「这家店怎么进入他的列表」。认不出的按列表算，不猜成分享 */
function sourceOf(from: string): StoreVisitSource {
  switch (from) {
    case "SHARE":
      return "SHARE";
    case "QR":
    case "SCAN":
      return "SCAN";
    case "SEARCH":
      return "SEARCH";
    case "GOODS":
      return "GOODS";
    default:
      return "LIST";
  }
}

function buyerPoint() {
  const a = location.active;
  return a?.latE6 != null && a?.lngE6 != null ? { latE6: a.latE6, lngE6: a.lngE6 } : null;
}

async function load() {
  if (!no.value) return;
  try {
    const home = await api.storeHome(no.value, fromParam.value);
    data.value = home;
    failed.value = false;
    await afterLoad();
  } catch {
    failed.value = true;
  }
}

/** 取到门户之后的几件事：标题、记住在逛哪家店、进店记录、我常买 */
async function afterLoad() {
  const home = data.value;
  if (!home) return;
  uni.setNavigationBarTitle({ title: storeName.value });
  if (home.portal) {
    // 结算时这个主体的单优先落到这家店（§2.7）
    rememberStore(home.merchant.merchantNo, home.portal.storeNo);
    /*
     * 进店：记进「我的店」+ 归因。**分享的回报就在这一步**：点开商家分享的门店，
     * 这家店从此留在他店铺页的「我的店」里。没登录不记（要挂在具体的人身上）；失败不影响看店。
     */
    if (user.isLogin) {
      void api
        .storeEnter(home.portal.storeNo, {
          source: sourceOf(fromParam.value),
          inviterNo: inviterNo.value || undefined,
          storeCode: storeCode.value || undefined,
        })
        .catch(() => undefined);
    }
  }
  frequent.value = await api.frequentItems(storeNo.value || entityNo.value).catch(() => []);
}

const inviterNo = ref("");
const storeCode = ref("");
/** 海报组件（面板里点「生成海报」时打开） */
const poster = ref<{ open: () => void } | null>(null);

onLoad(async (q) => {
  // scene 是微信小程序码带回来的参数，可能被 URL 编码过；storeCode 是 H5 / 普通二维码那条
  const rawScene = (q?.scene as string) || (q?.storeCode as string) || "";
  storeCode.value = rawScene ? safeDecode(rawScene) : "";

  // 分享落地页之一：邀请人要在这里接住（登录页只读自己 query 上的那份）
  user.captureInviter(q?.inviterNo);
  inviterNo.value = (q?.inviterNo as string) || "";
  no.value = (q?.no as string) || (q?.merchantNo as string) || "";
  fromParam.value = (q?.from as string) || (storeCode.value ? "QR" : "");

  /*
   * 从商家发来的推送点进来（链接带 reach=<这一条的号>）：回写「来了」。
   * 没登录就不报 —— 服务端要核是不是本人；报不上也不影响进店。
   */
  const reachNo = (q?.reach as string) || "";
  if (reachNo && user.isLogin) {
    api.reachOpened(reachNo).catch(() => undefined);
  }

  if (storeCode.value && !no.value) {
    // 扫码：服务端在这条路上记匿名扫码埋点与归因，再回门户 —— 不能换成 storeHome
    try {
      data.value = await api.storeByCode(storeCode.value, deviceId());
      await afterLoad();
    } catch {
      failed.value = true;
    }
    return;
  }
  await load();
});

/** scene 没编码过时 decodeURIComponent 会对「%」抛错 —— 解不开就用原样，别整页崩掉。 */
function safeDecode(v: string) {
  try {
    return decodeURIComponent(v);
  } catch {
    return v;
  }
}

/** 匿名去重用的设备号。**取不到就不传** —— 传空串会让所有游客算成同一个人 */
function deviceId() {
  try {
    const k = "ng_device_id";
    let v = uni.getStorageSync(k) as string;
    if (!v) {
      v = `D${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
      uni.setStorageSync(k, v);
    }
    return v || undefined;
  } catch {
    return undefined;
  }
}

// ---------------------------------------------------------------- 加购与再来一单

function blockedByPause() {
  // 暂停的店先拦住：让他加完购、到结算才被拒，是把一次失望拖长了三步
  if (!closed.value) return false;
  uni.showToast({ title: t("store.closedTip"), icon: "none" });
  return true;
}

async function addGoods(g: Goods, e: unknown) {
  if (blockedByPause()) return;
  if (soldOut(g)) {
    uni.showToast({ title: t("store.itemInvalid"), icon: "none" });
    return;
  }
  try {
    await cart.add(g.goodsNo, firstBuyableSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

async function addOne(f: FrequentItem) {
  if (blockedByPause()) return;
  if (f.invalid) {
    uni.showToast({ title: t("store.itemInvalid"), icon: "none" });
    return;
  }
  try {
    await cart.add(f.goodsNo, f.skuNo, 1);
    uni.showToast({ title: t("common.added"), icon: "none" });
  } catch (e) {
    // 加购会被业务规则拒绝（过了截单、限购、超区…）。不接住的话点了没反应
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 一键再来一单：拿最近一笔订单整单复制（C-ST-03） */
async function reorder() {
  if (busy.value || blockedByPause()) return;
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  busy.value = true;
  try {
    const res = await api.orderList({ size: 20 });
    const last = res.records.find((o) => o.status !== "CANCELLED" && o.items.some((it) => !it.isGift));
    if (!last) {
      uni.showToast({ title: t("store.noHistory"), icon: "none" });
      return;
    }
    const r = await api.reorderFrom(last.orderNo);
    await cart.load();
    // 丢了什么、涨了什么都要说清楚 —— 静默少加是投诉源头
    const parts = [t("store.reorderAdded", { n: r.added })];
    if (r.dropped.length) parts.push(t("store.reorderDropped", { s: r.dropped.join("、") }));
    if (r.priceUp.length) parts.push(t("store.reorderPriceUp", { s: r.priceUp.join("、") }));
    void confirm({ title: String(t("store.reorder")), hint: parts.join("\n"), alert: true });
  } finally {
    busy.value = false;
  }
}

async function toggleFav() {
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  const { favorited: on } = await api.toggleFavoriteStore(entityNo.value);
  if (data.value) data.value.favorited = on;
  uni.showToast({ title: on ? t("store.faved") : t("store.unfaved"), icon: "none" });
  /*
   * 收藏成功 → 就地收集「新品开售提醒」的订阅授权。只在收藏时问；
   * 弹窗必须由点击触发（这里就在 tap 的调用栈里）；不 await —— 授权与否不影响收藏本身。
   */
  if (on) {
    requestSubscribe([SUBSCRIBE_TMPL.newGoods]).then((r) => {
      if (r.accepted.length) void api.subscribeReport(r.accepted, true);
      if (r.rejected.length) void api.subscribeReport(r.rejected, false);
    });
  }
}

function gotoGoods(goodsNo: string) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${goodsNo}&storeNo=${storeNo.value}` });
}

function gotoSibling() {
  const s = data.value?.sibling;
  if (s) uni.redirectTo({ url: `${ROUTES.store}?no=${s.storeNo}&from=LIST` });
}

/** 经营主体与资质：主体只在这里露面 */
function gotoEntity() {
  uni.navigateTo({ url: `${ROUTES.merchant}?merchantNo=${entityNo.value}` });
}

function navToStore() {
  const f = data.value?.store;
  const c = fromE6(f?.latE6, f?.lngE6);
  if (c) openLocation({ ...c, name: storeName.value, address: f?.address ?? "" });
}

/** 公告压成一行，点开看全文 */
function showNotice() {
  const s = data.value?.store;
  if (s?.announcement) void confirm({ title: String(t("store.notice")), hint: s.announcement, alert: true });
}

// ---------------------------------------------------------------- 门头的两行

/** 营业中 / 休息中 / 暂停营业。认不出营业时间就不说开没开 */
const statusText = computed(() => {
  const p = data.value?.portal;
  if (closed.value) return String(t("shops.paused"));
  if (p?.openNow === true) return String(t("shops.openNow"));
  if (p?.openNow === false) return String(t("shops.closedNow"));
  return "";
});

/** ★ 评分 · 距你 N。没人评过说「新店」，不说「暂无评价」（后者对新店是劝退） */
const statsText = computed(() => {
  const p = data.value?.portal;
  const parts: string[] = [];
  if (p && p.ratingCount > 0) parts.push(`★ ${p.rating.toFixed(1)}`);
  else parts.push(String(t("shops.newShop")));
  const d = p?.distanceM ?? null;
  if (d != null) parts.push(String(t("store.distanceTo", { d: distance(d) })));
  return parts.join(" · ");
});

/** 公告的更新时间：今天 09:12 / 昨天 / 08-20。过期的公告服务端连时间都不给 */
const noticeAt = computed(() => {
  const at = data.value?.store.announcementAt;
  if (!at) return "";
  const today = isoDate(Date.now());
  const day = isoDate(at);
  if (day === today) return t("store.noticeAt", { s: `${t("store.noticeToday")} ${hourMinute(at)}` });
  if (day === isoDate(Date.now() - 86_400_000)) return t("store.noticeAt", { s: t("store.noticeYesterday") });
  return t("store.noticeAt", { s: day.slice(5) });
});

// ---------------------------------------------------------------- 分享

/**
 * 分享路径带**门店号**：落地进的是这一家店，不是主体的默认店。
 * from=SHARE 让落地页把「逛过」记成分享来的 —— 那是给商家的回报。
 * 按钮与右上角「···」用同一份，否则同一次分享按哪个入口走会算成两种来源。
 */
const sharePath = computed(() => `${ROUTES.store}?no=${storeNo.value || no.value}&from=SHARE`);
const shareTitle = computed(() => {
  const a = data.value?.store.announcement;
  return a ? `${storeName.value} · ${a}` : storeName.value;
});

onShareAppMessage(() =>
  buildShareMessage({
    title: shareTitle.value,
    path: sharePath.value,
    merchantNo: entityNo.value,
  }),
);

/* 朋友圈落单页模式、只吃 query，所以门店号与 from=SHARE 都要拼进 query */
onShareTimeline(() =>
  buildShareTimeline({
    title: shareTitle.value,
    path: ROUTES.store,
    params: `no=${storeNo.value || no.value}&from=SHARE`,
    // 朋友圈卡片配图：店里第一件在售商品的图，没有就用品牌标
    imageUrl: shareImageUrl(thumb(data.value?.goods[0]?.cover, 375), data.value?.merchant.logo),
    merchantNo: entityNo.value,
    inviterNo: user.user?.cUserNo,
  }),
);
</script>

<template>
  <sh-scaffold :pending="!data" :failed="failed" @retry="load">
    <template v-if="data">
      <!-- 门头：主色浅底一条，店招卡压在上面（s03） -->
      <view class="top">
      <view class="band"></view>
      <view class="head sh-card">
        <view class="head__top sh-row">
          <biz-shop-avatar :name="storeName" :logo="data.merchant.logo" :size="104"></biz-shop-avatar>
          <view class="sh-fill head__main">
            <text class="txt-title head__name">{{ storeName }}</text>
            <view class="head__line sh-row">
              <text v-if="statusText" class="sh-chip" :class="{ 'sh-chip--primary': !closed }">{{ statusText }}</text>
              <text v-if="data.store.openHours" class="txt-caption txt-quiet sh-num">{{ data.store.openHours }}</text>
            </view>
          </view>
          <text class="fav" :class="{ 'is-on': data.favorited }" @tap="toggleFav">
            {{ data.favorited ? "★" : "☆" }}
          </text>
          <!-- 分享：面板里「发给朋友」/「生成海报」（s08）。链接与海报码都带门店号 -->
          <biz-share-act
            :path="sharePath"
            :inviter-no="user.user?.cUserNo"
            :merchant-no="entityNo"
            poster
            :sheet-title="String($t('share.sheetStore'))"
            @poster="poster?.open()"
          ></biz-share-act>
        </view>
        <text class="txt-caption txt-quiet head__stats sh-num">{{ statsText }}</text>

        <!-- 公告压成一行，点开看全文 -->
        <view v-if="data.store.announcement" class="notice sh-row" @tap="showNotice">
          <text class="sh-chip sh-chip--warning notice__tag">{{ $t("store.notice") }}</text>
          <text class="txt-sub sh-fill notice__text">{{ data.store.announcement }}</text>
          <text v-if="noticeAt" class="txt-caption txt-quiet notice__at">{{ noticeAt }}</text>
        </view>
      </view>
      </view>

      <!-- 从朋友圈卡片进来（单页模式）：只能看，下单要点底部「前往小程序」 -->
      <biz-single-page-tip></biz-single-page-tip>

      <!-- 暂停营业（s07）：照开、不可加购，给同品牌最近的营业店 -->
      <view v-if="closed" class="sh-notice sh-notice--muted paused">
        <text class="txt-sub">{{ $t("store.pausedNotice") }}</text>
        <text v-if="data.sibling" class="sh-btn sh-btn--sm sh-btn--soft paused__go" @tap="gotoSibling">
          {{ $t("store.goSibling", { name: data.sibling.storeName }) }}
        </text>
      </view>

      <!-- 领券：领了券再挑货 -->
      <biz-coupon-strip v-if="!closed" :merchant-no="entityNo"></biz-coupon-strip>

      <view class="tabs">
        <sh-tabs :items="tabs" :active="tab" @change="switchTab"></sh-tabs>
      </view>

      <!-- 商品：左分类右列表 -->
      <view v-if="tab === 'goods'" class="menu">
        <input v-model="keyword" maxlength="32" class="txt-sub search" :placeholder="$t('store.searchPh')" />
        <view class="menu__body sh-row">
          <scroll-view scroll-y class="rail">
            <text
              v-for="c in rail"
              :key="c.key"
              class="txt-sub rail__item"
              :class="{ 'is-on txt-bold': !keyword.trim() && current === c.key }"
              @tap="picked = c.key; keyword = ''"
            >{{ c.label }}</text>
          </scroll-view>

          <view class="sh-fill list">
            <template v-if="showFrequent">
              <view class="list__head sh-row">
                <text class="txt-caption txt-quiet sh-fill">{{ $t("store.frequentHint") }}</text>
                <text class="sh-btn sh-btn--sm sh-btn--soft" @tap="reorder">{{ $t("store.reorder") }}</text>
              </view>
              <view
                v-for="f in frequent"
                :key="f.skuNo"
                class="item sh-row"
                :class="{ 'is-off': f.invalid || closed }"
              >
                <sh-cover class="item__cover" :src="f.cover" :w="160"></sh-cover>
                <view class="sh-fill item__main" @tap="gotoGoods(f.goodsNo)">
                  <text class="txt-sub txt-bold item__title">{{ f.title }}</text>
                  <text class="txt-caption txt-quiet item__meta">{{
                    f.invalid ? $t("store.invalid") : $t("store.times", { n: f.times })
                  }}</text>
                  <text class="txt-price sh-num item__price">{{ money(f.price) }}</text>
                </view>
                <text class="add sh-hit" :class="{ 'is-off': f.invalid || closed }" @tap="addOne(f)">＋</text>
              </view>
            </template>

            <template v-else>
              <view
                v-for="g in listed"
                :key="g.goodsNo"
                class="item sh-row"
                :class="{ 'is-off': soldOut(g) || closed }"
              >
                <sh-cover class="item__cover" :src="g.cover" :w="160"></sh-cover>
                <view class="sh-fill item__main" @tap="gotoGoods(g.goodsNo)">
                  <text class="txt-sub txt-bold item__title">{{ g.title }}</text>
                  <text class="txt-caption txt-quiet item__meta">{{
                    closed ? $t("shops.paused") : soldOut(g) ? $t("store.soldOut") : g.subtitle
                  }}</text>
                  <text class="txt-price sh-num item__price">{{ money(g.price) }}</text>
                </view>
                <text class="add sh-hit" :class="{ 'is-off': soldOut(g) || closed }" @tap="addGoods(g, $event)">＋</text>
              </view>
              <sh-empty v-if="!listed.length" :text="String($t('store.noGoods'))"></sh-empty>
            </template>
          </view>
        </view>
      </view>

      <!-- 评价：这一家门店的，带上评的是哪件货（s05） -->
      <view v-else-if="tab === 'reviews'" class="sh-block">
        <biz-review v-for="r in reviews" :key="r.reviewNo" :review="r"></biz-review>
        <sh-empty v-if="!reviews.length" :pending="!reviewsLoaded" :text="String($t('store.noReviews'))"></sh-empty>
      </view>

      <!-- 店铺（s06）：营业时间、地址（点开导航）、公告全文，最后一行经营主体与资质 -->
      <view v-else class="sh-block">
        <view v-if="data.store.openHours" class="info sh-row">
          <text class="txt-sub txt-quiet info__k">{{ $t("store.hours") }}</text>
          <text class="txt-sub sh-fill sh-num">{{ data.store.openHours }}</text>
        </view>
        <view v-if="data.store.address" class="info sh-row">
          <text class="txt-sub txt-quiet info__k">{{ $t("store.address") }}</text>
          <text class="txt-sub sh-fill">{{ data.store.address }}</text>
          <text v-if="data.store.latE6 != null" class="sh-btn sh-btn--sm sh-btn--soft" @tap="navToStore">
            {{ $t("community.navigate") }}
          </text>
        </view>
        <view v-if="data.store.announcement" class="info">
          <text class="txt-sub txt-quiet info__k">{{ $t("store.notice") }}</text>
          <text class="txt-sub info__full">{{ data.store.announcement }}</text>
        </view>
        <view class="info sh-row" @tap="gotoEntity">
          <text class="txt-sub sh-fill">{{ $t("store.entityInfo") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>

      <!-- 悬浮购物车：加购的落点，也是去结算的入口 -->
      <biz-cart-fab></biz-cart-fab>
      <!-- 门店海报：店名 · 公告 · 门店码（s09）。码扫出来进的是这一家店 -->
      <biz-poster
        v-if="storeNo"
        ref="poster"
        :store="{ storeNo, storeName, announcement: data.store.announcement }"
      ></biz-poster>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 门头：主色浅底一条，店招卡压上去一半 */
.band {
  height: 120rpx;
  background: var(--sh-primary-tint);
}
/* 底与圆角由 sh-card 给；这里只把它往上压到色条上 */
.head {
  position: relative;
  margin: -80rpx 24rpx 16rpx;
}
.head__top {
  gap: 20rpx;
}
.head__main {
  min-width: 0;
}
.head__name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head__line {
  gap: 12rpx;
  margin-top: 8rpx;
}
.head__stats {
  display: block;
  margin-top: 16rpx;
}
.fav {
  font-size: 40rpx;
  color: var(--sh-sub);
}
.fav.is-on {
  color: var(--sh-star);
}
.notice {
  gap: 12rpx;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--sh-line);
}
.notice__tag {
  flex-shrink: 0;
}
.notice__text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notice__at {
  flex-shrink: 0;
}
.paused {
  margin: 0 24rpx 16rpx;
}
.paused__go {
  display: inline-block;
  margin-top: 12rpx;
}
.tabs {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 8rpx 24rpx;
  background: var(--sh-bg);
}
.search {
  height: 72rpx;
  margin: 8rpx 24rpx 12rpx;
  padding: 0 24rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  color: var(--sh-ink);
}
.menu__body {
  align-items: flex-start;
}
/* 左栏：窄一列、浅底；选中项白底 + 主色竖条 */
.rail {
  width: 176rpx;
  flex-shrink: 0;
  max-height: 70vh;
  background: var(--sh-faint);
}
/* 竖条用起始边框画：常驻 6rpx 透明边，选中时只换颜色 —— 文字不因选中而跳一下。
   用 border-inline-start 而不是 border-left：阿拉伯语下整条轨道会翻到右侧，
   写死 left 的话竖条留在左边、与选中项对不上（第五道闸 check-rtl-physical 扫的就是这个）。 */
.rail__item {
  display: block;
  /* 起始侧少 6rpx，让出竖条的宽度；同样用逻辑属性，阿语下跟着翻 */
  padding-block: 28rpx;
  padding-inline: 12rpx 16rpx;
  border-inline-start: 6rpx solid transparent;
  text-align: center;
  color: var(--sh-sub);
}
/* 选中态的加重走 .txt-bold（模板上加），不在这儿自写 font-weight ——
   字阶把字号与字重绑死，而 .txt-bold 正是为「状态加重」留的那个修饰类 */
.rail__item.is-on {
  border-inline-start-color: var(--sh-primary);
  background: var(--sh-surface);
  color: var(--sh-ink);
}
.list {
  min-width: 0;
  min-height: 60vh;
  background: var(--sh-surface);
}
.list__head {
  gap: 12rpx;
  padding: 16rpx 20rpx 4rpx;
}
.item {
  gap: 16rpx;
  padding: 20rpx;
}
.item.is-off .item__cover,
.item.is-off .item__main {
  opacity: 0.5;
}
.item__cover {
  width: 128rpx;
  height: 128rpx;
  flex-shrink: 0;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.item__main {
  min-width: 0;
}
.item__title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item__meta {
  display: block;
  margin-top: 4rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item__price {
  display: block;
  margin-top: 8rpx;
}
.add {
  width: 52rpx;
  height: 52rpx;
  flex-shrink: 0;
  align-self: flex-end;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  /* 居中用 flex，不用 line-height 顶高 —— 行高是字阶的一部分，
     借它做垂直居中等于在这一处偷偷改字阶，而且换个字号就歪 */
  display: flex;
  align-items: center;
  justify-content: center;
}
.add.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.info {
  gap: 16rpx;
  padding: 24rpx;
  border-top: 1rpx solid var(--sh-line);
}
.info:first-child {
  border-top: 0;
}
.info__k {
  flex-shrink: 0;
  width: 120rpx;
}
.info__full {
  display: block;
  margin-top: 8rpx;
}
</style>
