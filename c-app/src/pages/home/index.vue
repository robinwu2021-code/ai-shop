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
import { onUnmounted, ref, watch} from "vue";
import { useI18n } from "vue-i18n";
import { onShow, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import PhoneGate from "@/components/phone-gate.vue";
import { useCartStore } from "@/stores/cart";
import { useUserStore } from "@/stores/user";
import { buildShareMessage } from "@shared/ports/share";
import { GOODS_COVER_FALLBACK, ROUTES } from "@shared/utils/constants";
import { countdownShort, money } from "@shared/utils/format";
import { firstSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { Goods, GroupBuy } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const community = useCommunityStore();
/** 「留个手机号」弹层。打开小程序、认出身份之后立刻问一次 */
const phoneGate = ref(false);
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
    flyToCart(p.x, p.y, g.cover || GOODS_COVER_FALLBACK);
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

/**
 * 未绑归属时，<b>先探附近有没有，再决定要不要推他去选</b>。
 *
 * <p>此前是无条件推：所在区域还没开通的用户，每次回首页都被推去一个
 * 只会说「这一带还没有自提点」的页面 —— 那一页对他没有任何用处，
 * 却是他绕不过去的第一屏。
 *
 * <p>附近没有就**留在首页让他先逛**：商品、门店、团购在后端本来就是游客可访问的。
 * 归属这件事推迟到他真要下自提单时再要（下单页会引导），
 * 而顶栏那个「选自提点」入口一直在，想手动选随时能点。
 */
async function maybePickCommunity() {
  if (community.bound) return;
  if (await community.probeNearby()) {
    gotoCommunity();
  } else if (!hintedNoNearby) {
    hintedNoNearby = true; // 一次会话说一次就够，每次回首页都弹是骚扰
    /*
     * **说「还没开通」的同时要给出手动选的路**，否则这句话只是个坏消息：
     * 用户不知道自己其实可以挑一个别处的自提点（给父母下单、出差前囤货都是这么用的）。
     * 用 showModal 而不是 toast，正是因为 toast 点不了 —— 它说完就走，什么也不给。
     */
    void confirm({
      title: String(t("home.noNearbyTitle")),
      hint: String(t("home.noNearbyPickup")),
      confirmText: String(t("home.pickManually")),
      cancelText: String(t("home.browseFirst")),
    }).then((ok) => {
      if (ok) gotoCommunity();
    });
  }
}
let hintedNoNearby = false;

function gotoSearch() {
  uni.navigateTo({ url: ROUTES.search });
}

/**
 * 打开小程序后的身份三步：**先认人，认不出就静默拿 openid，拿到了没手机号就要一个。**
 *
 * <p>顺序不能颠倒：先静默登录，是因为绑手机号要挂在一个账号上；
 * 没有账号就弹绑定，绑完不知道该记在谁名下。
 *
 * <p><b>一次会话只弹一次。</b> 他点了「以后再说」就是明确表态了，
 * 每次回首页再弹一遍会把它从「提示」变成「骚扰」——
 * 而入口一直在（「我的」页那行「绑定手机号 ›」），想绑随时能绑。
 */
let askedPhone = false;

async function ensureIdentity() {
  // 1) 已登录？没有就静默拿 openid（微信侧不需要用户确认，无感）
  if (!user.isLogin) {
    await user.silentLogin();
  }
  if (!user.isLogin) return; // 静默失败：不拦他，逛照样逛

  /*
   * 2) **每次都核对一遍 profile，不能「缓存里有就跳过」。**
   *
   * 缓存下来的资料是给首屏立刻有东西看的，不是事实来源。
   * 只在缺失时才拉的话，账号在服务端已经没了（被删、被封、已注销）时，
   * 端上会一直显示那个**并不存在的身份**，而且**一个需要鉴权的请求都不发** ——
   * 连 401 都触发不了，自愈机制永远不会启动。
   *
   * 真机实测撞到：库里把账号删了，小程序重开仍然「是」那个人，
   * 而按设计此刻应当重新注册一个（2026-08-22）。
   */
  await user.loadProfile().catch(() => {});
}

/*
 * **「该不该要手机号」用 watch，不在 onShow 里判一次就算。**
 *
 * onShow 那一刻状态常常还没就绪：App 的静默登录、这一页的 profile 拉取
 * 都是飞行中的请求，谁先到不确定。判一次的后果是「大多数时候不弹」——
 * 而它不报错，看起来就像这个功能没做（2026-08-22 真机自动化实测：
 * token 有了、账号也建了、profile 里 phone 是 null，弹层就是不出来）。
 *
 * watch 则是「状态一到就判」，与顺序无关。`askedPhone` 保证一次会话只弹一次：
 * 他点过「以后再说」就是表态了，再弹是骚扰。
 */
watch(
  () => [user.isLogin, user.user?.phone] as const,
  ([login, phone]) => {
    if (login && !phone && !askedPhone) {
      askedPhone = true;
      phoneGate.value = true;
    }
  },
  { immediate: true },
);

onShow(() => {
  load();
  cart.load();
  maybePickCommunity();
  void ensureIdentity();
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
      <view class="place__main sh-fill" @tap="gotoCommunity">
        <sh-icon name="pin" :size="26" color="var(--sh-primary)"></sh-icon>
        <text class="txt-body place__name">
          {{ community.pickup?.name || $t("home.choosePickup") }}
        </text>
        <text class="txt-caption place__sub sh-fill">
          {{
            community.pickup
              ? community.pickup.arrivalDesc
              : $t("home.choosePickupHint")
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
         首页给它一小段就够，完整列表（含商家团/邻里求团/发起）在团购页。
         白底页上它需要自己站住：给一层极淡填充，这是首页少数几个「真色块」之一 —— 
         它是**限时的、要立刻决定的**，与下面可以慢慢逛的商品流不是一类。 -->
    <view v-if="groups.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("home.groups") }}</text>
        <text class="sh-muted" @tap="gotoGroups">{{
          $t("home.groupsMore")
        }}</text>
      </view>
      <biz-group-card
        v-for="g in groups"
        :key="g.groupNo"
        :group="g"
        :now="now"
        @tap="openGroup(g)"
      ></biz-group-card>
    </view>

    <!-- 推荐商品：运营位。横滑窄卡，不与下面的主商品流抢版面 -->
    <view v-if="promoted.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("home.promoted") }}</text>
        <text class="sh-muted">{{ $t("home.promotedHint") }}</text>
      </view>
      <view class="freq">
        <view
          v-for="g in promoted"
          :key="g.goodsNo"
          class="freq__i"
          @tap="openGoods(g)"
        >
          <sh-cover class="freq__cover" :src="g.cover || GOODS_COVER_FALLBACK"></sh-cover>
          <text class="txt-strong freq__title">{{ g.title }}</text>
          <view class="freq__foot">
            <text class="txt-price freq__price sh-num">{{ money(g.price) }}</text>
            <view class="freq__add" @tap.stop="addToCart(g, $event)">
              <text class="freq__sign">＋</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!-- 社区在卖：首页主体。已在 goodsList 里按覆盖范围滤过 + 按距离排过 -->
    <view class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("home.communityFeed") }}</text>
        <text class="sh-muted">
          {{ community.community?.name || $t("home.communityFeedHint") }}
        </text>
      </view>

      <sh-empty
        bare
        v-if="!goods.length"
        :text="$t('home.communityFeedEmpty')"
      ></sh-empty>
      <biz-goods-card
        v-for="g in goods"
        :key="g.goodsNo"
        :goods="g"
        :countdown-text="cutdownOf(g)"
        @add="addToCart(g, $event)"
        @tap="openGoods(g)"
      ></biz-goods-card>
    </view>
    <!--
      **必须留在 sh-scaffold 里面。** 这套 `--sh-*` 变量声明在 `:root, .sh-root` 上，
      而**小程序里没有 `:root`** —— 根节点叫 `page`，那条选择器一个节点都不匹配，
      全靠 scaffold 根节点上的 `.sh-root`。挂到 scaffold 外面就一个变量都继承不到：
      遮罩和卡片背景 `var(--sh-scrim)` / `var(--sh-surface)` 双双落空变透明，
      弹层文字直接浮在商品列表上，**看起来像页面串了行，而不像弹窗坏了**。
      H5 上不会露：浏览器里 `:root` 是匹配的。见 shared/tests/scaffold-scope.test.ts
    -->
    <phone-gate class="sh-phone-gate" :show="phoneGate" @done="phoneGate = false" @close="phoneGate = false" />
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
  display: flex;
  align-items: center;
  gap: 8rpx;
}
/* 搜索缩成 icon 后要保住可点面积：40×40 的圆底，不是一个裸图标 */
.place__search {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 9999px;
  /* 可点区域要可见，但用**有色**而不是灰：同样一块底，
     主色浅调读作「这是个按钮」，灰读作「这儿有块脏东西」 */
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.place__name {
  /* 英文店名比中文长得多（Sunnyside Block 3 Point vs 阳光里 3 幢自提点）：
     原本 flex-shrink: 0 会让它独占整行、把右边的到货时间挤到只剩省略号。
     两边都可收缩，长的那个先让步。 */
  min-width: 0;
  flex-shrink: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.place__sub {
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
/* 白底页上不需要再铺一层白。横滑排回归纯粹的间距分隔 */
/* 横滑排通铺到块边，首尾各留出与标题一致的内边距 —— 半张卡露在边缘才是「可以滑」的暗示 */
.freq {
  gap: 16rpx;
  padding: 0 26rpx;
}
.freq__i {
  flex-shrink: 0;
  width: 200rpx;
}
.freq__cover {
  display: block;
  width: 100%;
  height: 120rpx;
  border-radius: 16rpx;
  /* 同商品卡：不给底色，emoji 自带形状，字号放大填满占位区 */
  font-size: 88rpx;
  line-height: 120rpx;
  text-align: center;
}
.freq__title {
  /* 单行截断在英文下等于没有信息：「Streaming Me…」「4-Ply Facial Ti…」。
     给两行并锁定高度 —— 高度固定，横滑排里每张卡的价格行才对得齐。 */
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  margin-top: 12rpx;
  height: 80rpx;
  overflow: hidden;
}
.freq__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
  margin-top: 8rpx;
}
.freq__price {
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
  /* 与商品卡的加购钮同色 —— 同一个动作在两处长得不一样，是没道理的 */
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.freq__sign {
  color: var(--sh-primary-text);
  font-size: 28rpx;
  line-height: 1;
}
</style>
