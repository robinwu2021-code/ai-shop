<script setup lang="ts">
// 门店门户（TDD-C端门店化与门店门户 s03–s07）。**单位是门店** —— 门头是门店名，
// 主体名只在「店铺」页签最后一行「经营主体与资质」里露面（电商法 §15 要求亮照）。
//
// 仍然是**交易页，不是介绍页**：老客三步下单（打开 → 我常买 → 结算）。
// 版式（2026-09-29 用户定）：头图顶到状态栏 → 公告与券 → 页签 → 搜索 → 我常买 → 分类 → 单列列表。
// 不做左右分栏。
//
// 进这一页的四条路，最后都落到**同一个门户**：
//   1. 店铺页 / 搜索 / 商品页进店 —— 带 `no`（门店号）与 `from`
//   2. 分享链接 —— `no` + `from=SHARE` + `inviterNo`
//   3. 扫印在店里的码 —— 微信把码里的 scene（店铺码）带回来，手上**只有码**
//   4. 老链接 / 旧版小程序 —— 带 `merchantNo`（主体号），服务端落到默认门店
// 不经过首页与选社区：扫码的人是来买东西的，游客可逛，加购时再引导登录。
import { computed, ref } from "vue";
import { onLoad, onPageScroll, onShareAppMessage, onShareTimeline } from "@dcloudio/uni-app";
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
import { navBox as readNavBox } from "@shared/ports/capsule";
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

// ---------------------------------------------------------------- 头图与顶部浮层

/** 浮层按钮的位置：小程序上对齐微信胶囊，H5 / App 从状态栏往下（端差异在 ports/capsule） */
const navBox = readNavBox();
/** 浮层整条的高度（到胶囊下沿再留 6px）。分类吸顶也吸在它下面 */
const barH = navBox.top + navBox.height + 6;
const topbarStyle = { height: `${barH}px` };
const topRowStyle = {
  top: `${navBox.top}px`,
  height: `${navBox.height}px`,
  paddingRight: `${navBox.right + 8}px`,
};
const btnStyle = { width: `${navBox.height}px`, height: `${navBox.height}px` };

/** 头图高度（px）：440rpx 按屏宽换算。滑过它，顶部换成白底标题条 */
const HERO_RPX = 440;
const heroPx = (HERO_RPX * navBox.winW) / 750;
const solid = ref(false);
onPageScroll((e: { scrollTop: number }) => {
  solid.value = e.scrollTop > heroPx - barH;
});

/**
 * 头图的底：本店卖得最好、且有真图的那件商品。只收 http(s) —— 种子与老数据里 cover 可能是 emoji，
 * 拿 emoji 当 image 的 src 是一张裂图。没有就不给，头图退回品牌色渐变。
 * 取小图（375 宽）：它要被虚化，清晰度用不上，省流量。
 */
const heroImg = computed(() => {
  const hit = [...(data.value?.goods ?? [])]
    .sort((a, b) => (b.sales ?? 0) - (a.sales ?? 0))
    .find((g) => /^https?:\/\//.test(g.cover ?? ""));
  return hit ? thumb(hit.cover, 375) : "";
});

/** 券条取回来几张（它自己取）。0 张且没有公告时，头图下面那张白卡不画 */
const couponCount = ref(0);

/** 分享 / 扫码进来时栈里只有这一页，返回键点了没反应 —— 那时回首页 */
function goBack() {
  if (getCurrentPages().length > 1) uni.navigateBack();
  else uni.switchTab({ url: ROUTES.home });
}

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

// ---------------------------------------------------------------- 商品：常买一栏 + 分类 + 双列

const hasFrequent = computed(() => frequent.value.some((f) => f.times > 0));

/**
 * 分类（横排，不做左右分栏 —— 2026-09-29 用户定）：「全部」+ 店主排的货架。
 * 货架是店主自己排的顺序、自己起的名字（「本地时鲜」而不是「蔬菜」）；
 * 少于一个货架时不画这一排：一个恒真的筛选只是占地方。
 */
const ALL = "@all";
const cats = computed(() => [
  { key: ALL, label: String(t("store.allCats")) },
  ...(data.value?.categories ?? []).map((c) => ({ key: c.categoryNo, label: c.name })),
]);
const cat = ref(ALL);

/**
 * 网格里的商品。「全部」按销量排（热卖在前）；店内搜索跨全部分类 ——
 * 他搜「番茄」时不该因为停在「粮油」而搜不到。售罄的不藏（格子上写售罄、没有加号）。
 */
const listed = computed(() => {
  const all = data.value?.goods ?? [];
  const k = keyword.value.trim().toLowerCase();
  if (k) {
    return all.filter((g) => g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k));
  }
  if (cat.value === ALL) return [...all].sort((a, b) => (b.sales ?? 0) - (a.sales ?? 0));
  return all.filter((g) => g.categoryNo === cat.value);
});
/** 「我常买」一栏：买过的人才有；搜索时收起，别和搜索结果抢位置 */
const showFrequent = computed(() => hasFrequent.value && !keyword.value.trim());

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
  <!-- immersive：不画标题栏，头图顶到状态栏；返回 / 收藏 / 分享由下面的浮层画（与商品详情同一种做法） -->
  <sh-scaffold immersive :padded="false" :pending="!data" :failed="failed" @retry="load">
    <template v-if="data">
      <!--
        顶部浮层：压在头图上时是半透明圆钮；滑过头图变白底标题条（返回 + 店名）。
        右边让出微信胶囊（navBox.right）。收藏与分享只在压在图上时出现 ——
        白底标题条上只留「我在哪家店」这一件事。
      -->
      <view class="topbar" :class="{ 'is-solid': solid }" :style="topbarStyle">
        <view class="topbar__row sh-row" :style="topRowStyle">
          <view class="topbar__btn sh-center sh-hit" :style="btnStyle" @tap="goBack">
            <sh-icon name="chevronLeft" :size="34" :color="solid ? 'var(--sh-ink)' : '#fff'"></sh-icon>
          </view>
          <text v-if="solid" class="txt-title sh-fill topbar__title">{{ storeName }}</text>
          <template v-else>
            <view class="sh-fill"></view>
            <text class="txt-caption sh-center topbar__fav" :style="{ height: navBox.height + 'px' }" @tap="toggleFav">
              {{ data.favorited ? "★ " + $t("store.favOn") : "☆ " + $t("store.favAct") }}
            </text>
            <!-- 分享：面板里「发给朋友」/「生成海报」（s08）。链接与海报码都带门店号 -->
            <biz-share-act
              :path="sharePath"
              :inviter-no="user.user?.cUserNo"
              :merchant-no="entityNo"
              poster
              :image-box="navBox.height"
              :sheet-title="String($t('share.sheetStore'))"
              @poster="poster?.open()"
            ></biz-share-act>
          </template>
        </view>
      </view>

      <!--
        头图（2026-09-29 用户定「加背景图，高端一点」）。店招图还没有字段（下一步 B 端上传），
        现在用**本店卖得最好那件商品的主图**放大虚化当底，再压一层品牌色 —— 每家店都不一样，
        店主什么都不用做。一张能用的图都没有时退回纯品牌色渐变，不出现灰块。
      -->
      <view class="hero" :class="{ 'has-img': !!heroImg }">
        <image v-if="heroImg" class="hero__img" :src="heroImg" mode="aspectFill"></image>
        <view v-if="heroImg" class="hero__tint"></view>
        <view class="hero__shade"></view>
        <view class="hero__foot sh-row">
          <!-- 白底框包在外面：小程序里调用点写的 class 停在宿主节点上，改不到头像自己的底色 -->
          <view class="hero__logo sh-center">
            <biz-shop-avatar :name="storeName" :logo="data.merchant.logo" :size="88"></biz-shop-avatar>
          </view>
          <view class="sh-fill hero__main">
            <text class="txt-title head__name">{{ storeName }}</text>
            <view class="hero__line sh-row">
              <text v-if="statusText" class="txt-caption hero__status" :class="{ 'is-off': closed || data.portal?.openNow === false }">{{ statusText }}</text>
              <text v-if="data.store.openHours" class="txt-caption sh-num hero__meta">{{ data.store.openHours }}</text>
              <text class="txt-caption sh-num hero__meta">{{ statsText }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 公告与券收进一张压在头图上的白卡；两样都没有时不画这张卡（券是组件自己取的，取回来报个数） -->
      <view v-show="!!data.store.announcement || couponCount > 0" class="sh-card head">
        <view v-if="data.store.announcement" class="notice sh-row" @tap="showNotice">
          <text class="sh-chip sh-chip--warning notice__tag">{{ $t("store.notice") }}</text>
          <text class="txt-sub sh-fill notice__text">{{ data.store.announcement }}</text>
          <text v-if="noticeAt" class="txt-caption txt-quiet notice__at">{{ noticeAt }}</text>
        </view>
        <!-- 领券：领了券再挑货 -->
        <biz-coupon-strip
          v-if="!closed"
          bare
          :class="{ 'head__coupons': !!data.store.announcement }"
          :merchant-no="entityNo"
          @count="(n: number) => (couponCount = n)"
        ></biz-coupon-strip>
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

      <!-- 三个页签用分段控件：下面的分类是 chip，两排长得一样就分不出哪排是页、哪排是筛选 -->
      <view class="tabs sh-row">
        <text
          v-for="it in tabs"
          :key="it.key"
          class="sh-seg sh-seg--fill"
          :class="{ 'sh-seg--on': tab === it.key }"
          @tap="switchTab(it.key)"
        >{{ it.label }}</text>
      </view>

      <!--
        商品（2026-09-29 用户定 B 版：不做左右分栏、单列列表）：搜索 → 我常买（老客才有，横滑）→ 分类 → 单列。
        单列与首页、搜索同一个件（biz-goods-card），第二行写截单 / 规格，落款行不写店名（整页都是这一家）。
      -->
      <view v-if="tab === 'goods'" class="shelf" :class="{ 'is-paused': closed }">
        <view class="sh-searchbox search">
          <sh-icon name="search" :size="28" color="var(--sh-sub)"></sh-icon>
          <input v-model="keyword" maxlength="32" class="txt-sub sh-fill" :placeholder="$t('store.searchPh')" />
        </view>

        <view v-if="showFrequent" class="sh-block">
          <view class="sh-block__head">
            <text class="txt-title sh-fill">{{ $t("store.frequent") }}</text>
            <text class="sh-btn sh-btn--sm sh-btn--soft" @tap="reorder">{{ $t("store.reorder") }}</text>
          </view>
          <scroll-view scroll-x class="freq" :show-scrollbar="false">
            <view class="freq__row">
              <view
                v-for="f in frequent"
                :key="f.skuNo"
                class="freq__card"
                :class="{ 'is-off': f.invalid || closed }"
                @tap="gotoGoods(f.goodsNo)"
              >
                <sh-cover class="freq__cover" :src="f.cover" :w="200"></sh-cover>
                <text class="txt-sub freq__title">{{ f.title }}</text>
                <text class="txt-caption txt-quiet">{{
                  f.invalid ? $t("store.invalid") : $t("store.times", { n: f.times })
                }}</text>
                <view class="sh-row freq__foot">
                  <text class="txt-price sh-num sh-fill">{{ money(f.price) }}</text>
                  <view class="sh-center add sh-hit" @tap.stop="addOne(f)">
                    <text>＋</text>
                  </view>
                </view>
              </view>
            </view>
          </scroll-view>
        </view>

        <!-- 分类吸在顶部标题条下面（往下滑时仍能换类）；分类与列表在同一块白底上 -->
        <view class="sh-block goods">
          <view v-if="cats.length > 1 && !keyword.trim()" class="cats" :style="{ top: barH + 'px' }">
            <sh-tabs :items="cats" :active="cat" @change="(k: string) => (cat = k)"></sh-tabs>
          </view>
          <view class="list">
            <biz-goods-card
              v-for="g in listed"
              :key="g.goodsNo"
              :goods="g"
              in-store
              @tap="gotoGoods(g.goodsNo)"
              @add="addGoods(g, $event)"
            ></biz-goods-card>
          </view>
          <sh-empty v-if="!listed.length" :text="String($t('store.noGoods'))"></sh-empty>
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
/* 顶部浮层：固定在屏顶。压在头图上时透明，滑过头图后变白底标题条 */
.topbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: var(--sh-z-nav);
  pointer-events: none;
}
.topbar.is-solid {
  background: var(--sh-surface);
}
.topbar__row {
  position: absolute;
  left: 0;
  right: 0;
  gap: 12rpx;
  padding-inline-start: 24rpx;
  box-sizing: border-box;
  pointer-events: auto;
}
/* 圆钮：压在图上是半透明深底 + 白图标，任何颜色的图上都看得清 */
.topbar__btn {
  flex-shrink: 0;
  border-radius: 9999px;
  background: var(--sh-scrim);
}
.topbar.is-solid .topbar__btn {
  background: transparent;
}
.topbar__title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.topbar__fav {
  flex-shrink: 0;
  padding: 0 20rpx;
  border-radius: 9999px;
  background: var(--sh-scrim);
  color: #fff;
  white-space: nowrap;
}
/*
 * 头图：顶到状态栏。底是虚化的商品图 + 一层品牌色；没有图时就是品牌色渐变。
 * 下半截压暗，白字店名压在上面 —— 浅色图（白菜、大米）上也读得清。
 */
/* 顶层块之间的那道缝（base.css `.sh-scaffold > * + *`）会把头图从屏顶推下来一截：它前面是固定定位的浮层 */
.hero {
  position: relative;
  margin-top: 0;
  height: 440rpx;
  overflow: hidden;
  background: linear-gradient(160deg, var(--sh-primary), var(--sh-primary-text));
}
.hero__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  filter: blur(36rpx) saturate(1.4);
  transform: scale(1.3);
}
.hero__shade {
  position: absolute;
  inset: 0;
  /* 上沿一点暗托住浮层按钮，下半截压暗托住白字。色只用 scrim（守卫：style 块不写死颜色） */
  background: linear-gradient(180deg, var(--sh-scrim) 0%, transparent 32%, transparent 46%, var(--sh-scrim) 100%);
}
/*
 * 有图时多压一层品牌色：虚化后的图单独看是一团灰绿，压上品牌色才像「这家店的」。
 * 单独一层 + opacity，不用 color-mix —— 老一些的微信内核不认它，整条 background 会静默失效
 */
.hero__tint {
  position: absolute;
  inset: 0;
  background: var(--sh-primary);
  opacity: 0.38;
}
.hero__foot {
  position: absolute;
  left: 24rpx;
  right: 24rpx;
  bottom: 56rpx;
  gap: 20rpx;
  color: #fff;
}
.hero__logo {
  flex-shrink: 0;
  width: 96rpx;
  height: 96rpx;
  overflow: hidden;
  border-radius: 24rpx;
  background: #fff;
}
.hero__main {
  min-width: 0;
}
.head__name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #fff;
}
.hero__line {
  gap: 12rpx;
  margin-top: 8rpx;
  white-space: nowrap;
  opacity: 0.92;
}
/* 两个类压过 .txt-caption 自带的次要灰：压在深色图上要白字 */
.hero__line .hero__meta {
  color: #fff;
}
.hero__status {
  padding: 2rpx 12rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
/* 休息 / 暂停：透明底白描边。深色 scrim 压在已经压暗的图上等于看不见 */
.hero__status.is-off {
  background: transparent;
  border: 2rpx solid #fff;
  color: #fff;
}
/* 公告与券：一张白卡压在头图下沿上 */
.head {
  position: relative;
  margin: -32rpx 24rpx 16rpx;
}
.head__coupons {
  display: block;
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: var(--sh-hairline);
}
.notice {
  gap: 12rpx;
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
/* 页签不吸顶：往下滑时吸住的是分类（换类比换页签常用得多），两条都吸会占掉一屏的五分之一 */
.tabs {
  gap: 12rpx;
  padding: 12rpx 24rpx;
}
.search {
  margin: 8rpx 24rpx 0;
}
.search input {
  color: var(--sh-ink);
}
/* 暂停营业：整片商品压淡（加购另有拦截与提示） */
.shelf.is-paused .list,
.shelf.is-paused .freq {
  opacity: 0.5;
}
/* 我常买：一栏横滑的小卡，老客三步下单（打开 → 常买 → 结算） */
.freq {
  white-space: nowrap;
}
.freq__row {
  display: inline-flex;
  gap: 16rpx;
  padding: 0 24rpx 24rpx;
}
.freq__card {
  width: 224rpx;
  white-space: normal;
}
.freq__card.is-off {
  opacity: 0.5;
}
.freq__cover {
  width: 224rpx;
  height: 224rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.freq__title {
  display: block;
  margin-top: 12rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.freq__foot {
  margin-top: 8rpx;
}
.add {
  width: 52rpx;
  height: 52rpx;
  flex-shrink: 0;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
/*
 * sh-block 自带 overflow: hidden（裁圆角用），而**任何一层祖先 overflow 不是 visible，sticky 就静默不吸**。
 * 这一块放开；圆角由分类行自己补（它是块里最上面那一行）
 */
.sh-block.goods {
  overflow: visible;
  padding-top: 0;
}
/* 分类吸在顶部标题条下面（top 由模板按胶囊高度给）。要有底色，列表从它下面滚过去 */
.cats {
  border-radius: 32rpx 32rpx 0 0;
  position: sticky;
  z-index: 2;
  padding: 24rpx 24rpx 8rpx;
  background: var(--sh-surface);
}
.list {
  padding: 0 4rpx 16rpx;
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
