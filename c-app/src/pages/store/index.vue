<script setup lang="ts">
// 门店主页（C-ST-01~10）。**一期主获客路径**：商家把店铺码印在包装袋、发进自己的
// 客户群，老客扫码直达这里（ADR-004 决策 3）。
//
// 关键：**这是交易页，不是店铺介绍页**。
// 粮油副食不是「逛」出来的，复购路径必须压到三步 —— 打开 → 常买 → 下单。
// 所以登录用户第一屏是「我买过的」，店招和简介往后放；未登录才退化成店铺热销。
//
// 另一条：**不经过首页与选社区**。老客扫码是来买东西的，中间插一个「请先选择你的社区」
// 会把人挡在门外 —— 游客可逛，加购时再引导登录。
import { computed, ref } from "vue";
import { onLoad, onShareAppMessage } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { fromE6, openLocation } from "@shared/ports/location";
import { api } from "@/api";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { ROUTES, MERCHANT_LOGO_FALLBACK } from "@shared/utils/constants";
import { firstSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import { money } from "@shared/utils/money";
import { hourMinute, isoDate } from "@shared/utils/datetime";
import { buildShareMessage } from "@shared/ports/share";
import type { FrequentItem, Goods, StoreHome } from "@shared/types";

const { t } = useI18n();
const cart = useCartStore();
const user = useUserStore();

const merchantNo = ref("");
const data = ref<StoreHome | null>(null);
const frequent = ref<FrequentItem[]>([]);
const keyword = ref("");
const busy = ref(false);

/**
 * 已停业。**扫码进来的老客要知道「店关了」，不是「链接坏了」** ——
 * 所以不做 404，而是盖一条横幅并禁掉加购与再来一单。
 */
const closed = computed(() => !!data.value?.closed);

/**
 * 本店货架 —— **店主自己排的顺序、自己改的名字**。
 *
 * <p>此前买家侧一处都用不到它：店主在 B 端「我的类目」里摆哪几类、叫什么、什么顺序，
 * 到了这一页全被拍平成一个「全部商品」的长列表。
 *
 * <p>少于两条不画这一行：一个类目时它是个恒真的开关，占一行却什么都不让人选。
 */
const shelves = computed(() => data.value?.categories ?? []);
const showShelves = computed(() => shelves.value.length > 1);

/** 当前选中的类目；空 = 全部 */
const pickedCat = ref("");

/**
 * 店内搜索 + 类目筛选（C-ST-06）。
 *
 * <p>两者叠加而不是二选一：买家先点「本地时鲜」再搜「番茄」是最自然的路径，
 * 而互斥的话第二步会把第一步悄悄清掉。
 */
const goods = computed(() => {
  let list = data.value?.goods ?? [];
  if (pickedCat.value) {
    list = list.filter((g) => g.categoryNo === pickedCat.value);
  }
  const k = keyword.value.trim().toLowerCase();
  if (!k) return list;
  return list.filter(
    (g) =>
      g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k),
  );
});

const hasFrequent = computed(() => frequent.value.some((f) => f.times > 0));

async function load() {
  if (!merchantNo.value) return;
  const [home, freq] = await Promise.all([
    api.storeHome(merchantNo.value, fromParam.value),
    api.frequentItems(merchantNo.value),
  ]);
  data.value = home;
  frequent.value = freq;
}

const fromParam = ref("");

onLoad(async (q) => {
  merchantNo.value = (q?.merchantNo as string) || "";
  // from=QR 表示扫码进店 —— 归因写在服务端，决定订单的 trafficSource 与商家费率档
  fromParam.value = (q?.from as string) || "";
  await load();
});

/**
 * 商品卡的加购。**这一页此前没接 `@add`** —— 卡片把事件 emit 出来，没人接，
 * 于是「全部商品」区的 ＋ 点了完全没反应：不是报错，是什么都不发生。
 * 首页、分类、商家页、搜索页四处都接了，只有这里漏了。
 */
async function addGoods(g: Goods, e: unknown) {
  if (closed.value) {
    uni.showToast({ title: t("store.closedTip"), icon: "none" });
    return;
  }
  try {
    await cart.add(g.goodsNo, firstSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

async function addOne(f: FrequentItem) {
  // 停业的店先拦住：让他加完购、到结算才被拒，是把一次失望拖长了三步
  if (closed.value) {
    uni.showToast({ title: t("store.closedTip"), icon: "none" });
    return;
  }
  if (f.invalid) {
    uni.showToast({ title: t("store.itemInvalid"), icon: "none" });
    return;
  }
  try {
    await cart.add(f.goodsNo, f.skuNo, 1);
    uni.showToast({ title: t("common.added"), icon: "none" });
  } catch (e) {
    // 加购会被业务规则拒绝（生鲜过了当日截单、限购、超区…）。
    // 不接住的话点了没反应，用户只会以为按钮坏了
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 一键再来一单：拿最近一笔本店订单整单复制（C-ST-03） */
async function reorder() {
  if (busy.value) return;
  if (closed.value) {
    uni.showToast({ title: t("store.closedTip"), icon: "none" });
    return;
  }
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  busy.value = true;
  try {
    const res = await api.orderList({ size: 20 });
    const last = res.records.find(
      (o) => o.status !== "CANCELLED" && o.items.some((it) => !it.isGift),
    );
    if (!last) {
      uni.showToast({ title: t("store.noHistory"), icon: "none" });
      return;
    }
    const r = await api.reorderFrom(last.orderNo);
    await cart.load();
    // 丢了什么、涨了什么都要说清楚 —— 静默少加是投诉源头
    const parts = [t("store.reorderAdded", { n: r.added })];
    if (r.dropped.length)
      parts.push(t("store.reorderDropped", { s: r.dropped.join("、") }));
    if (r.priceUp.length)
      parts.push(t("store.reorderPriceUp", { s: r.priceUp.join("、") }));
    uni.showModal({
      title: t("store.reorder"),
      content: parts.join("\n"),
      showCancel: false,
    });
  } finally {
    busy.value = false;
  }
}

async function toggleFav() {
  if (!user.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  const on = await api.toggleFavoriteStore(merchantNo.value);
  if (data.value) data.value.favorited = on;
  uni.showToast({
    title: on ? t("store.faved") : t("store.unfaved"),
    icon: "none",
  });
}

function gotoGoods(goodsNo: string) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${goodsNo}` });
}

// 分享出去的链接必须带 merchantNo，否则进店归因断掉（ADR-004 §5.4）
/**
 * 公告的更新时间，人话版。
 *
 * <p>只给到「今天 09:12 / 昨天 / 08-20」这个精度：更细没有意义（老客要判断的是
 * 「这句话还算不算数」），更粗又回到了「不知道是不是上个月的」。
 * 过期的公告服务端连时间都不下发 —— 那一行整个不该出现。
 */
const noticeAt = computed(() => {
  const at = data.value?.store.announcementAt;
  if (!at) return "";
  const today = isoDate(Date.now());
  const day = isoDate(at);
  if (day === today) return t("store.noticeAt", { s: `${t("store.noticeToday")} ${hourMinute(at)}` });
  if (day === isoDate(Date.now() - 86_400_000)) return t("store.noticeAt", { s: t("store.noticeYesterday") });
  return t("store.noticeAt", { s: day.slice(5) });
});

onShareAppMessage(() =>
  buildShareMessage({
    title: data.value
      ? `${data.value.merchant.name} · ${data.value.store.announcement}`
      : "",
    // from=SHARE 让落地页知道这是分享进来的，与扫码同样计入商家自带客流
    path: `${ROUTES.store}?from=SHARE`,
    merchantNo: merchantNo.value,
  }),
);

/** 导航到门店。没坐标（商家还没在地图上标点）时按钮本身不渲染 */
function navToStore() {
  const f = data.value?.store;
  const c = fromE6(f?.latE6, f?.lngE6);
  if (c) openLocation({ ...c, name: data.value?.merchant?.name ?? "", address: f?.address ?? "" });
}
</script>

<template>
  <sh-scaffold v-if="data" :padded="true">
    <!--
      已停业横幅。**放在最上面、盖不住内容** —— 老客扫码进来是冲着这家店来的，
      要第一眼知道「店关了」而不是翻了半天才发现加不了购。
    -->
    <view v-if="closed" class="closed">
      <text class="closed__text">{{ $t("store.closed") }}</text>
    </view>

    <!-- 店招：登录用户看到的是「常买」优先，这里只占一行 -->
    <view class="store">
      <text class="store__logo">{{ data.merchant.logo || MERCHANT_LOGO_FALLBACK }}</text>
      <view class="store__main">
        <view class="store__row">
          <text class="store__name">{{ data.merchant.name }}</text>
          <text v-if="data.merchant.verified" class="sh-chip sh-chip--primary">
            {{ $t("merchant.verified") }}
          </text>
        </view>
        <view class="addr">
          <text class="sh-muted addr__t">
            {{ data.store.openHours }} · {{ data.store.address }}
          </text>
          <text v-if="data.store.latE6 != null" class="addr__nav" @tap="navToStore">
            {{ $t("community.navigate") }}
          </text>
        </view>
      </view>
      <text class="fav" :class="{ 'is-on': data.favorited }" @tap="toggleFav">
        {{ data.favorited ? "★" : "☆" }}
      </text>
    </view>

    <!--
      店铺公告：店主自发，老客一进来就看到。
      **带更新时间**：一句没有时间的「今天到了新米」，既可能是今早写的、
      也可能是上个月忘了撤的 —— 分不出来就不会照着它跑一趟，而那正是这行字的用处。
    -->
    <view v-if="data.store.announcement" class="notice">
      <text>{{ data.store.announcement }}</text>
      <text v-if="noticeAt" class="notice__at">{{ noticeAt }}</text>
    </view>

    <!-- 第一屏：我买过的。这是本页存在的理由 -->
    <view class="sh-block">
      <view class="sh-block__head sec">
        <text class="sh-h2">{{
          hasFrequent ? $t("store.frequent") : $t("store.hot")
        }}</text>
        <text v-if="hasFrequent" class="sh-link" @tap="reorder">{{
          $t("store.reorder")
        }}</text>
      </view>

      <view
        v-for="f in frequent"
        :key="f.skuNo"
        class="freq"
        :class="{ 'is-off': f.invalid }"
      >
        <sh-cover class="freq__cover" :src="f.cover"></sh-cover>
        <view class="freq__main" @tap="gotoGoods(f.goodsNo)">
          <text class="freq__title">{{ f.title }}</text>
          <text class="sh-muted">{{ f.spec }}</text>
          <view class="freq__tags">
            <text v-if="f.times > 1" class="sh-chip">{{
              $t("store.times", { n: f.times })
            }}</text>
            <text v-if="f.price > f.lastPrice" class="sh-chip sh-chip--warning">
              {{ $t("store.priceUp", { p: money(f.lastPrice) }) }}
            </text>
            <text v-if="f.invalid" class="sh-chip sh-chip--danger">{{
              $t("store.invalid")
            }}</text>
          </view>
        </view>
        <view class="freq__buy">
          <text class="freq__price sh-num">{{ money(f.price) }}</text>
          <text class="add" @tap="addOne(f)">＋</text>
        </view>
      </view>

      <!-- 履约说明：超区在店铺页就说清楚，不等到结算 -->
      <view class="ship">
        <text class="sh-muted">{{ $t("store.fulfillHint") }}</text>
      </view>
    </view>

    <!-- 店内搜索 + 全部商品 -->
    <view class="sh-block">
      <view class="sh-block__head sec">
        <text class="sh-h2">{{ $t("store.allGoods") }}</text>
        <text class="sh-muted sh-num">{{ goods.length }}</text>
      </view>
      <input
        v-model="keyword"
        class="search"
        :placeholder="$t('store.searchPh')"
      />

      <!--
        类目行：店主排的顺序、店主起的名字。
        横滚而不是换行 —— 一家店摆七八类是常事，换行会把商品列表推到屏幕外。
      -->
      <scroll-view v-if="showShelves" class="cats" scroll-x>
        <view class="cats__row">
          <text
            class="pb-tag cats__chip"
            :class="{ 'is-on': !pickedCat }"
            @tap="pickedCat = ''"
          >
            {{ $t("store.allCats") }}
          </text>
          <text
            v-for="c in shelves"
            :key="c.categoryNo"
            class="pb-tag cats__chip"
            :class="{ 'is-on': pickedCat === c.categoryNo }"
            @tap="pickedCat = pickedCat === c.categoryNo ? '' : c.categoryNo"
          >
            {{ c.name }} {{ c.count }}
          </text>
        </view>
      </scroll-view>

      <biz-goods-card
        v-for="g in goods"
        :key="g.goodsNo"
        :goods="g"
        @tap="gotoGoods(g.goodsNo)"
        @add="addGoods(g, $event)"
      ></biz-goods-card>
    </view>
  </sh-scaffold>
</template>

<style scoped>
/* 类目行：横滚。两条都要 —— 见 b-app 商品列表里那段同样的注释：
   uni 的 <text> 自带 pre-line 会盖掉父级 nowrap，flex 子项默认会被压缩而不是溢出滚动 */
.cats {
  white-space: nowrap;
  margin: 12rpx 0 4rpx;
}
.cats__row {
  display: inline-flex;
  gap: 12rpx;
}
.cats__chip {
  white-space: nowrap;
  flex-shrink: 0;
  font-size: 24rpx;
  padding: 10rpx 20rpx;
}
.cats__chip.is-on {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-weight: 600;
}

.addr {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.addr__t {
  flex: 1;
  min-width: 0;
}
.addr__nav {
  flex-shrink: 0;
  padding: 6rpx 18rpx;
  border-radius: 999px;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 22rpx;
}
.closed {
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.closed__text {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.store {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 8rpx 0 24rpx;
}
.store__logo {
  width: 96rpx;
  height: 96rpx;
  border-radius: 32rpx;
  background: var(--sh-surface);
  font-size: 56rpx;
  text-align: center;
  line-height: 96rpx;
}
.store__main {
  flex: 1;
  min-width: 0;
}
.store__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.store__name {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.fav {
  font-size: 40rpx;
  color: var(--sh-sub);
}
.fav.is-on {
  color: var(--sh-warning);
}
.notice__at {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  opacity: 0.75;
}
.notice {
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 26rpx;
  line-height: 1.6;
}
/* 排布由 .sh-block__head 给，这里只把右侧的「再来一单 / 计数」推到头 */
.sec {
  align-items: center;
  justify-content: space-between;
}
/* 底和圆角由外层 .sh-block 给 —— 常买行在块内成行，不再各自一张卡 */
.freq {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 20rpx 26rpx;
}
.freq.is-off {
  opacity: 0.5;
}
.freq__cover {
  width: 88rpx;
  height: 88rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 52rpx;
  text-align: center;
  line-height: 88rpx;
}
.freq__main {
  flex: 1;
  min-width: 0;
}
.freq__title {
  /* 常买清单的行内商品名 —— 与购物车/订单里的商品行同类，用同一档
     （这里的 .freq 是**横向行**，不是首页那个同名的横滑窄卡） */
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  line-height: 1.4;
  color: var(--sh-ink);
}
.freq__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10rpx;
  margin-top: 10rpx;
}
.freq__buy {
  text-align: end;
}
.freq__price {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.add {
  display: inline-block;
  margin-top: 10rpx;
  width: 56rpx;
  height: 56rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 30rpx;
  text-align: center;
  line-height: 56rpx;
}
/* 履约说明在常买块内收尾：它解释的就是上面这些东西怎么送到 */
.ship {
  margin: 8rpx 26rpx 0;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  line-height: 1.6;
}
/* 搜索框在白块内，底要比块浅一档才看得出是个输入框 */
.search {
  height: 80rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  color: var(--sh-ink);
  margin: 0 26rpx 12rpx;
}
</style>
