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
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow, onShareAppMessage } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { useLocationStore } from "@/stores/location";
import { useUserStore } from "@/stores/user";
import { buildShareMessage } from "@shared/ports/share";
import { GOODS_COVER_FALLBACK, ROUTES } from "@shared/utils/constants";
import { countdownShort, money } from "@shared/utils/format";
import { firstSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import type { Goods, GroupBuy } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();
const location = useLocationStore();

/**
 * 顶栏第二行。**四种状态各说各的话，一个都不能落到「点击选择」上。**
 *
 * <p>有生效位置却还显示「点击选择你所在的社区」是自相矛盾的：
 * 他明明已经选过了。实测撞到过 —— 切了位置、顶栏主标题变成「公司」，
 * 副标题却还在催他去选。
 *
 * <p>没绑到自提点时显示地址本身，而不是催他 ——
 * 那种情况是「这个位置附近还没有取货点」，催也没用。
 */
const placeSub = computed(() => {
  const arrival = community.pickup?.arrivalDesc;
  if (arrival) return arrival;
  const a = location.active;
  if (a) return a.detail || a.region || "";
  return String(t("home.choosePickupHint"));
});
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

/**
 * 点顶栏那一行。**按「他现在有没有位置」分流，不是永远去选社区页。**
 *
 * <p>顶栏显示的是「我在哪」（家 / 公司），那么点它的心智就是「换个地方」——
 * 落到「选择社区自提点」页是答非所问：他要的是切位置，不是挑一个代收点。
 *
 * <p>还一个位置都没有时才去选社区页 —— 那一页此时承担的是
 * 「这一带有什么」的探索，正好是新用户需要的。
 */
/**
 * 顶栏常驻的快捷切换 chip：家 / 公司。**最多两个，且不含当前那个。**
 *
 * <p>这是「手动多选」被否掉之后的替代方案：多选的真实驱动力是「切换太麻烦」，
 * 所以把切换做到一点即换 —— 而不是让他同时挂着两个地方、看一锅混合的货。
 * 超过两个就别塞了：顶栏那一行还要放定位图标、地名与搜索。
 */
const quickPlaces = computed(() =>
  location.list
    .filter((a) => a.tag && a.addressId !== location.active?.addressId)
    .slice(0, 2));

async function quickSwitch(a: (typeof location.list)[number]) {
  const { rebound } = await location.switchTo(a.addressId);
  uni.showToast({
    title: String(rebound
      ? t("address.nowHere", { name: a.tag || a.detail })
      : t("address.nowHereNoCoord", { name: a.tag || a.detail })),
    icon: "none",
    duration: rebound ? 1500 : 3000,
  });
  load();
}

function gotoPlace() {
  if (location.has || location.list.length) {
    uni.navigateTo({ url: ROUTES.address });
    return;
  }
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
function gotoSearch() {
  uni.navigateTo({ url: ROUTES.search });
}

/**
 * 打开小程序后的身份两步：**先认人，认不出就静默拿 openid。**
 *
 * <p>手机号不在这里要了 —— 见下面那段注释。
 */
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
 * **这里原先会在首屏弹「留个手机号」，现在不弹了。**
 *
 * 它和刚搬走的「强推选自提点」是同一个毛病：把一个需要理由的动作，
 * 放在了用户还没有理由的那一刻。新用户第一眼看到的应该是商品，
 * 不是一张表单 —— 他此刻还不知道这里卖什么，凭什么给你手机号。
 *
 * 手机号真正需要的时刻是**下单**（自提到货要发通知、配送要打电话），
 * 那一刻「为什么要我的号」不用解释。下单页（order-confirm）已经拦着，
 * 见那里的 phoneGate：没号就先要，绑完自动继续提交。
 *
 * 入口没有消失：「我的」页那行「绑定手机号 ›」一直在，想绑随时能绑。
 */

onShow(() => {
  load();
  cart.load();
  void location.load();
  /*
   * **这里原先会把未绑归属的人推去选自提点，现在不推了。**
   *
   * 那条路的前提是能静默定位，而 2026-09 微信驳回了 `wx.getLocation`
   * （开放范围不含「匹配附近服务」，且驳的是规则不是措辞）。前提没了，
   * 这个跳转就成了**对着零信息做出的强制选择**：新用户第一屏被推进一个页面，
   * 而他还不知道这个小程序卖什么。
   *
   * 现在的顺序是：先让他看见东西，顶栏「选择取货点 ›」一直在，想选随时点；
   * 真到下自提单那一步，下单页会拦住并引导。
   * **把选择放在他有理由做选择的那一刻。**
   *
   * 顺带也不再调 probeNearby()：它唯一的用途就是决定要不要跳，
   * 不跳之后它只是一次白花的定位请求 —— 而定位请求会弹授权框。
   */
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
  <sh-scaffold title-key="home.title" tab="home">
    <!-- 页头两行，按**使用频次**排序：
         · 自提点是「装一次、几个月不动」的设置 —— 收成一行小字，能看见、能切换即可
         · 搜索是每次打开都可能用的动作 —— 给它主视觉
         原先反过来：自提点占一张比搜索框还高的大卡片，把最低频的东西放在了最显眼的位置。
         但**不能删**：自提点决定「东西送到哪、什么时候能拿」，下单前要一眼可确认，
         藏进「我的」会让人下完单才发现提错了点。 -->
    <view class="place sh-row">
      <view class="place__main sh-fill sh-row" @tap="gotoPlace">
        <sh-icon name="pin" :size="26" color="var(--sh-primary)"></sh-icon>
        <!--
          **显示的是「当前生效位置」，不是自提点。** 用户脑子里的第一层是
          「我在哪」（家 / 公司），而不是「货落在哪个代收点」——
          后者是前者推出来的结果（location store 的 syncCommunityFromActive）。
          还没有位置时回落到自提点，再没有就提示去选：这一行**任何时候都要有内容**，
          空着的顶栏会让人以为页面没加载完。
        -->
        <!--
          「当前位置」要**标出来**：它与「按家的地址在逛」看到的货不是一回事，
          而两种状态若显示成同一个样子，用户会把此刻的商品当成家里能买到的，
          下单才发现送不到。标签放在名字前面 —— 放后面会被长地名挤出屏幕。
        -->
        <text v-if="location.isTransient" class="txt-caption place__here">{{ $t("home.hereTag") }}</text>
        <text class="txt-body place__name">
          {{ location.label || community.pickup?.name || $t("home.choosePickup") }}
        </text>
        <text class="txt-caption place__sub sh-fill">{{ placeSub }}</text>
      </view>
      <!--
        家 / 公司 一点即换。**替代「手动多选」的那一半** ——
        多选的驱动力是「切换太麻烦」，那就让切换便宜，而不是把两个地方的货混在一屏。
      -->
      <view v-if="quickPlaces.length" class="place__quick sh-row">
        <text
          v-for="a in quickPlaces"
          :key="a.addressId"
          class="txt-caption place__chip"
          @tap.stop="quickSwitch(a)"
        >{{ a.tag }}</text>
      </view>
      <!-- 搜索收成一个 icon 并入这一行：一个社区只覆盖三五家店、几十上百个 SKU，
           用户翻两屏就看完了全部 —— 搜索远没到值一整行主视觉的程度。
           省下的那一行给「再来一单」，那才是这个场景下真正的高频动作。 -->
      <view class="place__search sh-center" @tap="gotoSearch">
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
          <view class="freq__foot sh-row sh-row--between">
            <text class="txt-price freq__price sh-num">{{ money(g.price) }}</text>
            <view class="freq__add sh-center" @tap.stop="addToCart(g, $event)">
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
  </sh-scaffold>
</template>

<style scoped>
/* 自提点：一行搞定 —— 图标 + 名称 + 到货时间 + 右侧箭头（切换入口） */
.place {
  padding: 4rpx 0 12rpx;
}
.place__main {
  gap: 8rpx;
}
/* 搜索缩成 icon 后要保住可点面积：40×40 的圆底，不是一个裸图标 */
.place__quick {
  gap: 12rpx;
  margin-inline-start: 12rpx;
  flex-shrink: 0;
}
.place__chip {
  padding: 6rpx 18rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
}
.place__search {
  flex-shrink: 0;
  width: 64rpx;
  height: 64rpx;
  border-radius: 9999px;
  /* 可点区域要可见，但用**有色**而不是灰：同样一块底，
     主色浅调读作「这是个按钮」，灰读作「这儿有块脏东西」 */
  background: var(--sh-primary-tint);
}
/* 「当前位置」标：与地名同一行，靠颜色区分，不占额外高度 */
.place__here {
  flex-shrink: 0;
  margin-inline-end: 8rpx;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
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
}
.freq__sign {
  color: var(--sh-primary-text);
  font-size: 28rpx;
  line-height: 1;
}
</style>
