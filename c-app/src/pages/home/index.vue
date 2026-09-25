<script setup lang="ts">
/*
 * 首页 = **我这个社区现在能买到什么**。
 *
 * 这里**没有品类频道** —— 底部菜单常驻一个「分类」tab，一键可达，
 * 首页再放一排同样的三个品类是纯重复。腾出的那一行改放分类页没有的入口。
 * 首页主体是社区商品流：先按覆盖范围滤掉送不到我这儿的商家，再按距离近的在前。
 *
 * **一件商品只出一张卡**（2026-09-19）：团不再单独成段，而是并进它那件商品的卡里，
 * 有团的商品置顶、最早截止在前 —— 规则在 shared/home-feed.ts。此前上半截团卡、
 * 下半截商品卡，同一只香梨一屏里出现两次、两个价格，要用户自己对出是同一件东西。
 * 团购只在顶上留一行入口（N 个团正在拼 · 全部 ›），给「我就是来找团的」那种人。
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
import { firstBuyableSku } from "@shared/utils/goods";
import { flyToCart, tapPoint } from "@/shared/fly";
import { buildHomeFeed, joinableGroups } from "@/shared/home-feed";
import type { Goods, GroupBuy } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();
const location = useLocationStore();

const cart = useCartStore();
const user = useUserStore();

const goods = ref<Goods[]>([]);
/** 本自提点还能参与的团。不单独成段 —— 并进商品卡，见 feed */
const groups = ref<GroupBuy[]>([]);
/** 推荐商品（运营位）。**运营意图，不是销量事实** —— 理由见 contract.promotedGoods */
const promoted = ref<Goods[]>([]);

/**
 * 推荐位**先关掉**（2026-09-18）。
 *
 * <p>一个常量同时关掉请求与那一块 —— 只藏界面的话，首页每次加载还是会去要一次，
 * 而那条请求对用户毫无用处，只是让首屏多等一个往返。
 *
 * <p>放开时改这一个常量就够了，接线都留着。
 */
const SHOW_PROMOTED = false;
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;

/** 首页商品流：团并进商品卡、有团的置顶。`now` 走秒表，截止了的团当场从卡上退掉 */
const feed = computed(() => buildHomeFeed(goods.value, groups.value, now.value));

function cutdownOf(g: Goods): string {
  if (!g.cutoffAt) return "";
  return countdownShort(g.cutoffAt - now.value);
}

/** 首屏到过没有。**不是 `loading`** —— 那个含下拉刷新，刷新时把列表换成空态是另一个 bug */
const loaded = ref(false);
/** 这次没取到。**与「确定为空」是两件事** —— 网络不通时不该显示「还没有…」 */
const failed = ref(false);
/**
 * 连**区**都推不出来（定位被拒且没有任何位置）。
 * 与「这儿还没有货」也是两件事：那一格要的是位置，不是一句「还没有商品」。
 */
const noPlace = ref(false);

async function load() {
  /*
   * **位置永远要有一个，哪怕只精确到区。**
   *
   * 此前没绑社区就不带任何筛选条件去要商品 —— 那不是「兜底」，是过滤被跳过：
   * 拿回来的是全平台的货，而它在界面上与「你这儿能买到的」长得一模一样。
   * 按精度依次降级：聚落 → 区县 → 都没有才空态要位置。
   */
  /*
   * ⚠️ **`ensureCoarseRegion` 会在 await 期间把聚落绑上**（M6 的第 2 级），
   * 所以归属要在它**之后**再读一次。
   *
   * 第一版在 await 之前读了一次就不管了，于是龙华那种「围栏外、绑最近聚落」的人
   * 拿到的是：顶栏写着「最近的取货点 · 约 19 公里」、社区名也对，
   * 而商品区是「还不知道你在哪儿」—— 三块东西互相矛盾，且一条错误都没有。
   * 它在单测与源码守卫里都看不出来（那两者判的是有没有调、传了什么），
   * 是小程序运行时截图抓到的。
   */
  /*
   * **无条件调用。** 它内部会分两种情况处理：已有归属就核一次那个聚落还在不在
   * （核完还在就返回 null），没有归属才去定位。
   *
   * ⚠️ 这里原先写的是 `community.community ? null : await ensureCoarseRegion()` ——
   * 于是**有旧归属时它根本不跑**，而校验「那个聚落还在不在」正写在它里面：
   * 代码在它唯一该起作用的场景里是死的。真机上的症状是顶栏一直显示
   * 一个库里根本没有的社区名，重开多少次都不变。
   */
  /*
   * **「我在哪」先取，而且每次进首页都取一次**（内部按时刻判过期，五分钟内直接给）。
   * 它是顶栏那一行的唯一真源 —— 少了这一句，顶栏读到的是上一次会话留下的东西。
   */
  await location.ensureHere();
  const region = await location.ensureCoarseRegion();
  const communityNo = community.community?.communityNo;
  const regionCode = communityNo ? undefined : region?.code;
  noPlace.value = !communityNo && !regionCode;
  if (noPlace.value) {
    // 连区都推不出来：这是**唯一**该空屏的一格，列一屏买不到的东西比空着更糟
    goods.value = [];
    promoted.value = [];
    groups.value = [];
    failed.value = false;
    loaded.value = true;
    return;
  }
  try {
    const [res, gs, promo] = await Promise.all([
      api.goodsList({ size: 20, communityNo, regionCode }),
      api.groupBuyList(community.pickup?.pickupNo),
      // 关着的模块**不发请求** —— 开关关掉却照样打接口，是白白的一次往返
      SHOW_PROMOTED ? api.promotedGoods({ communityNo, regionCode }) : Promise.resolve([]),
    ]);
    goods.value = res.records;
    promoted.value = promo;
    // 只留**还能参与**的团（没截止、没满员）；不截断 —— 顶上那一行要数全
    groups.value = joinableGroups(gs, Date.now());
    failed.value = false;
  } catch {
    failed.value = true;
  }
  loaded.value = true;
}

async function addToCart(g: Goods, e: unknown) {
  try {
    await cart.add(g.goodsNo, firstBuyableSku(g).skuNo, 1);
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

/** 「去拼团」直接进最快成团的那个团 —— 再经商品详情转一道，是让他多点一次去找同一个按钮 */
function openGroup(groupNo: string) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${groupNo}` });
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

function gotoPlace() {
  /*
   * **去「选择位置」，不是去收货地址页**（TDD-C端首页位置选择，原型 l01→l02）。
   *
   * 顶栏问的是「现在按哪儿看货」，而收货地址页回答的是「下单寄到哪」：
   * 从这儿进去的人要换一带看看，却先要建一条收货地址（地址簿还只有 20 条的位置）。
   * 选点页的浏览模式把两件事分开 —— 搜索 / 当前定位 / 我的收货地址 / 附近小区
   * 四段都在那一页，选完当场切过去，一条地址都不写。
   */
  uni.navigateTo({ url: `${ROUTES.addressPick}?mode=browse` });
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
   * 那条路的前提是能静默定位，而它在 2026-09-03 到 09-20 之间是拿不到的
   * （`wx.getLocation` 被驳回，09-20 才开通）。当时前提没了，
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
        <!--
          **顶栏写的是「当前定位」，不是收货地址。**（2026-09-18 改回）

          中间那一版把这里换成了固定文案「收货地址」—— 那是把**入口的名字**
          印在了本该显示**状态**的位置上：它每一屏都一样，谁看都不知道自己在哪儿。

          为什么是当前定位而不是默认收货地址：这一带支持快递外送，
          「我此刻在哪」与「货寄到哪」是两件事，而顶栏回答的是前一件。
          `location.label` 已经把优先级排好了（用户显式挑过的地址 > 当前定位 >
          粗定位区名），这里只负责显示。

          取不到任何位置时才回落到「收货地址」四个字 —— 这一行**任何时候都要有内容**，
          空着的顶栏会让人以为页面没加载完。点它照旧跳收货地址页。
        -->
        <text class="txt-body place__name">
          {{ location.label || $t("address.title") }}
        </text>
        <!-- 模糊定位只准到区：标出来，别让「龙华区」被读成「就在这儿」。点这一行去地址页可在地图上选准的 -->
        <text v-if="location.approx" class="txt-caption sh-chip place__approx">{{ $t("home.approx") }}</text>
        <view class="sh-fill"></view>
      </view>
      <!--
        **顶栏不放「重新定位」。** 点这一行本来就跳收货地址页，而那一页上
        当前位置那一行带着重新定位 —— 两颗按钮做同一件事，而顶栏这一行
        本来就只有那么宽，多一颗会把地名挤出去。
      -->
      <!--
        「家 / 公司」快捷切换也先收起来（2026-09-18）：它显示的是地址标签，
        同样是在首页上写位置。切换地址仍然走收货地址页 —— 多一次点击，
        但首页不再替他声称在哪儿。
      -->
      <!-- 搜索收成一个 icon 并入这一行：一个社区只覆盖三五家店、几十上百个 SKU，
           用户翻两屏就看完了全部 —— 搜索远没到值一整行主视觉的程度。
           省下的那一行给「再来一单」，那才是这个场景下真正的高频动作。 -->
      <view class="place__search sh-hit sh-center" @tap="gotoSearch">
        <sh-icon name="search" :size="30" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <!--
      团购入口：**一行**。团本身已经并进下面的商品卡（有团的置顶），
      这里只回答「现在有几个团在拼」，给专门来找团的人一个入口。
      此前是一段三张团卡 —— 与下面同一件商品的普通卡重复出现。
    -->
    <view v-if="groups.length" class="sh-card gentry sh-row" @tap="gotoGroups">
      <text class="sh-chip sh-chip--danger">{{ $t("home.groups") }}</text>
      <text class="txt-body sh-fill">{{ $t("home.groupsEntry", { n: groups.length }) }}</text>
      <text class="sh-muted">{{ $t("home.groupsMore") }}</text>
    </view>

    <!-- 推荐商品：运营位。横滑窄卡，不与下面的主商品流抢版面 -->
    <view v-if="SHOW_PROMOTED && promoted.length" class="sh-block">
      <view class="sh-block__head">
        <text class="txt-title">{{ $t("home.promoted") }}</text>
        <text class="sh-muted">{{ $t("home.promotedHint") }}</text>
      </view>
      <view class="freq sh-scrollx">
        <view
          v-for="g in promoted"
          :key="g.goodsNo"
          class="freq__i"
          @tap="openGoods(g)"
        >
          <sh-cover class="freq__cover" :src="g.cover || GOODS_COVER_FALLBACK" :w="200"></sh-cover>
          <text class="txt-strong freq__title">{{ g.title }}</text>
          <view class="freq__foot sh-row sh-row--between">
            <text class="txt-price freq__price sh-num">{{ money(g.price) }}</text>
            <view class="freq__add sh-hit sh-center" @tap.stop="addToCart(g, $event)">
              <text class="freq__sign">＋</text>
            </view>
          </view>
        </view>
      </view>
    </view>

    <!--
      商品清单：首页主体。已在 goodsList 里按门店覆盖范围滤过 + 按距离排过。
      **不写标题、不写位置**（2026-09-18）：此前这儿是「社区在卖 · 桂澜新村」——
      而首页要回答的是「有什么可买」，不是「你在哪儿」。
      商品能不能送到你那儿，看商品详情页上的销售范围。
    -->
    <view class="sh-block">

      <!--
        **要位置的空态与「这儿还没有货」是两件事。** 前者给一个出口（去选地址），
        后者只能等上货。两件事共用一句「还没有商品」，会让定位被拒的人
        以为平台上什么都没有，然后离开。
      -->
      <sh-empty v-if="noPlace" bare :text="$t('home.noPlaceText')" :tip="$t('home.noPlaceTip')">
        <template #action>
          <view class="sh-btn sh-btn--sm" @tap="gotoPlace">{{ $t("home.noPlaceAction") }}</view>
        </template>
      </sh-empty>
      <sh-empty
        bare
        v-else-if="!goods.length" :pending="!loaded" :failed="failed" @retry="load"
        :text="$t('home.communityFeedEmpty')"
      ></sh-empty>
      <biz-goods-card
        v-for="it in feed"
        :key="it.goods.goodsNo"
        :goods="it.goods"
        :group="it.group"
        :countdown-text="cutdownOf(it.goods)"
        @add="addToCart(it.goods, $event)"
        @join="it.group && openGroup(it.group.groupNo)"
        @tap="openGoods(it.goods)"
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
/* 团购入口行：一张 sh-card、一行，与下面的商品块之间留一道常规缝 */
.gentry {
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.place__approx {
  flex-shrink: 0;
}
/* 常买：横滑窄卡。比商品卡窄得多 —— 这里不做决策，只做「就是它，加一个」，
   标题一行 + 价格 + 加号就够，副标题、销量、商家统统是噪音 */
/* 横滑与「藏掉滚动条」都归 `.sh-scrollx`（见 base.css）——
   小程序里那条灰杠就是因为这两半此前分开写、而藏的那一半没覆盖到小程序。
   这里只留这一排自己的版面：通铺到块边，首尾各留出与标题一致的内边距，
   半张卡露在边缘才是「可以滑」的暗示。 */
.freq {
  display: flex;
  gap: 16rpx;
  padding: 0 24rpx 4rpx;
}
.freq__i {
  flex-shrink: 0;
  width: 200rpx;
}
.freq__cover {
  display: block;
  width: 100%;
  height: 120rpx;
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
