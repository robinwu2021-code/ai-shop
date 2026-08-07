<script setup lang="ts">
/*
 * 首页 = **我这个社区现在能买到什么**。
 *
 * 这里**没有品类频道** —— 底部菜单常驻一个「分类」tab，一键可达，
 * 首页再放一排同样的三个品类是纯重复。腾出的那一行改放分类页没有的入口。
 * 团购一期活动很少，撑不起首页，也不该由它定义首页心智 —— 它是活动，不是货架，
 * 入口留在「我的」。首页主体是社区商品流：先按覆盖范围滤掉送不到我这儿的商家，
 * 再按距离近的在前。附近的店排在商品流之前 —— 邻里购物里「谁在卖」常常先于「卖什么」。
 */
import { onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { buildShareMessage } from "@shared/ports/share";
import { ROUTES } from "@shared/utils/constants";
import { countdownShort, money } from "@shared/utils/format";
import { firstSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { Goods, GroupBuy } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();
const cart = useCartStore();
const user = useUserStore();

const goods = ref<Goods[]>([]);
/** 本自提点进行中的团。团购是活动不是货架，所以它在商品流**之前**但只占一小段 */
const groups = ref<GroupBuy[]>([]);
/** 推荐商品（运营位）。**运营意图，不是销量事实** —— 理由见 contract.promotedGoods */
const promoted = ref<Goods[]>([]);
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;

function cutdownOf(g: Goods): string {
  if (!g.cutoffAt) return "";
  return countdownShort(g.cutoffAt - now.value);
}

async function load() {
  // 没绑社区时不带 communityNo —— 拿全量兜底，总比空首页强（随后会弹自提点选择）
  const communityNo = community.community?.communityNo;
  const [res, gs, promo] = await Promise.all([
    api.goodsList({ size: 20, communityNo }),
    api.groupBuyList(community.pickup?.pickupNo).catch(() => []),
    // 关着的模块**不发请求** —— 开关关掉却照样打接口，是白白的一次往返
    api.promotedGoods({ communityNo }).catch(() => []),
  ]);
  goods.value = res.records;
  promoted.value = promo;
  // 首页只放**还能参与**的团（没到截止），过期的留在团购页
  groups.value = gs.filter((g) => g.expireAt > Date.now()).slice(0, 3);
}

async function addToCart(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstSku(g).skuNo, 1);
    const p = tapPoint(e as Parameters<typeof tapPoint>[0]);
    flyToCart(p.x, p.y, g.cover);
  } catch (err) {
    uni.showToast({ title: (err as Error).message, icon: "none" });
  }
}

function openGoods(g: Goods) {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${g.goodsNo}` });
}

function gotoGroups() {
  uni.navigateTo({ url: ROUTES.groups });
}

function openGroup(g: GroupBuy) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${g.groupNo}` });
}

function gotoCommunity() {
  uni.navigateTo({ url: ROUTES.community });
}

function gotoSearch() {
  uni.navigateTo({ url: ROUTES.search });
}

onShow(() => {
  load();
  cart.load();
  if (!community.bound) gotoCommunity();
  timer = setInterval(() => (now.value = Date.now()), 1000);
});

onUnmounted(() => clearInterval(timer));

// 裂变：分享必带归因参数
onShareAppMessage(() =>
  buildShareMessage({
    title: String(t("home.shareTitle")),
    path: ROUTES.home,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  }),
);
</script>

<template>
  <sh-scaffold title-key="tab.home" tab="home">
    <!-- 页头两行，按**使用频次**排序：
         · 自提点是「装一次、几个月不动」的设置 —— 收成一行小字，能看见、能切换即可
         · 搜索是每次打开都可能用的动作 —— 给它主视觉
         原先反过来：自提点占一张比搜索框还高的大卡片，把最低频的东西放在了最显眼的位置。
         但**不能删**：自提点决定「东西送到哪、什么时候能拿」，下单前要一眼可确认，
         藏进「我的」会让人下完单才发现提错了点。 -->
    <view class="place">
      <view class="place__main" @tap="gotoCommunity">
        <sh-icon name="pin" :size="26" color="var(--sh-primary)"></sh-icon>
        <text class="place__name">
          {{ community.pickup?.name || $t("home.choosePickup") }}
        </text>
        <text class="place__sub">
          {{
            community.pickup ? community.pickup.arrivalDesc : $t("home.choosePickupHint")
          }}
        </text>
      </view>
      <!-- 搜索收成一个 icon 并入这一行：一个社区只覆盖三五家店、几十上百个 SKU，
           用户翻两屏就看完了全部 —— 搜索远没到值一整行主视觉的程度。
           省下的那一行给「再来一单」，那才是这个场景下真正的高频动作。 -->
      <view class="place__search" @tap="gotoSearch">
        <sh-icon name="search" :size="30" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <!-- 团购：活动，有时效，蹭首页曝光。只放**还能参与**的，最多 3 条 ——
         首页给它一小段就够，完整列表（含商家团/邻里求团/发起）在团购页。 -->
    <template v-if="groups.length">
      <view class="section">
        <text class="sh-h2">{{ $t("home.groups") }}</text>
        <text class="sh-muted" @tap="gotoGroups">{{ $t("home.groupsMore") }}</text>
      </view>
      <biz-group-card
        v-for="g in groups"
        :key="g.groupNo"
        :group="g"
        :now="now"
        @tap="openGroup(g)"
      ></biz-group-card>
    </template>

    <!-- 推荐商品：运营位。横滑窄卡，不与下面的主商品流抢版面 -->
    <template v-if="promoted.length">
      <view class="section">
        <text class="sh-h2">{{ $t("home.promoted") }}</text>
        <text class="sh-muted">{{ $t("home.promotedHint") }}</text>
      </view>
      <view class="freq">
        <view v-for="g in promoted" :key="g.goodsNo" class="freq__i" @tap="openGoods(g)">
          <text class="freq__cover">{{ g.cover }}</text>
          <text class="freq__title">{{ g.title }}</text>
          <view class="freq__foot">
            <text class="freq__price sh-num">{{ money(g.price) }}</text>
            <view class="freq__add" @tap.stop="addToCart(g, $event)">
              <text class="freq__sign">＋</text>
            </view>
          </view>
        </view>
      </view>
    </template>

    <!-- 社区在卖：首页主体。已在 goodsList 里按覆盖范围滤过 + 按距离排过 -->
    <view class="section">
      <text class="sh-h2">{{ $t("home.communityFeed") }}</text>
      <text class="sh-muted">
        {{ community.community?.name || $t("home.communityFeedHint") }}
      </text>
    </view>

    <sh-empty bare v-if="!goods.length" :text='$t("home.communityFeedEmpty")'></sh-empty>

    <biz-goods-card
      v-for="g in goods"
      :key="g.goodsNo"
      :goods="g"
      :countdown-text="cutdownOf(g)"
      @add="addToCart(g, $event)"
      @tap="openGoods(g)"
    ></biz-goods-card>
  </sh-scaffold>
</template>

<style scoped>
/* 自提点：一行搞定 —— 图标 + 名称 + 到货时间 + 右侧箭头（切换入口） */
.place {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 4rpx 0 12rpx;
}
.place__main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10rpx;
}
/* 搜索缩成 icon 后要保住可点面积：40×40 的圆底，不是一个裸图标 */
.place__search {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.place__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
  flex-shrink: 0;
}
.place__sub {
  flex: 1;
  min-width: 0;
  font-size: 23rpx;
  color: var(--sh-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 常买：横滑窄卡。比商品卡窄得多 —— 这里不做决策，只做「就是它，加一个」，
   标题一行 + 价格 + 加号就够，副标题、销量、商家统统是噪音 */
.freq {
  display: flex;
  gap: 16rpx;
  overflow-x: auto;
  padding-bottom: 4rpx;
}
.freq__i {
  flex-shrink: 0;
  width: 200rpx;
  background: var(--sh-surface);
  border-radius: 24rpx;
  padding: 18rpx;
}
.freq__cover {
  display: block;
  width: 100%;
  height: 120rpx;
  border-radius: 18rpx;
  background: var(--sh-faint);
  font-size: 56rpx;
  line-height: 120rpx;
  text-align: center;
}
.freq__title {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-ink);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.freq__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
  margin-top: 10rpx;
}
.freq__price {
  font-size: 28rpx;
  font-weight: 700;
  color: var(--sh-ink);
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.freq__add {
  flex-shrink: 0;
  width: 48rpx;
  height: 48rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.freq__sign {
  color: var(--sh-primary);
  font-size: 28rpx;
  font-weight: 600;
  line-height: 1;
}
/* 分段标题的上下留白：48/24 在手机上几乎占掉一行的高度，收到 32/16 */
.section {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  margin: 32rpx 0 16rpx;
}
</style>
