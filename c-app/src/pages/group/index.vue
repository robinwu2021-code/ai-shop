<script setup lang="ts">
/*
 * 参团（原型 s22）。与 B 端团详情（s10）同一版式：倒计时、人头、信息列表，
 * 只是底部按钮换成买家的动作 —— 分享、参团 ¥8。
 *
 * **参团 = 带团号下单，按团价付款，付了款才算一人**（TDD-营销域-详细设计 §1.4）。
 * 此前这里直接调「参团」接口插一行成员，不产生订单与付款：成团价从未被收过，
 * 到期也无钱可退，而提示还写着「先参团的邻居差价已退回」—— 那句话兑现不了，删掉了。
 * 没凑齐的团到期自动整单退款，这一条写在页面上，是买家敢付钱的前提。
 *
 * 2026-09-19（原型 p04–p07，TDD-C端拼团买家流程）：
 *   - 付完团单从支付页落到这里（`?paid=1`）：说一句「付款成功，已开团 / 已参团」，主按钮只剩「邀请邻居来拼」——
 *     团成不成取决于他转不转发，这一屏只放这一件事；
 *   - 「商品：香梨」一行字换成商品行（图 + 名 + 团价 + 单买价），整行进商品详情；
 *   - 已成团：一条状态轴 +「查看订单」（myOrderNo）；没凑齐：退了多少、退到哪 +「单独买 / 再开一个团」两条出路。
 */
import { computed, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShareAppMessage, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useUserStore } from "@/stores/user";
import { useCartStore } from "@/stores/cart";
import { useCommunityStore } from "@/stores/community";
import { buildShareMessage, canNativeShare } from "@shared/ports/share";
import { ROUTES } from "@shared/utils/constants";
import { defaultFulfillment } from "@shared/utils/goods";
import { money } from "@shared/utils/format";
import type { GroupBuy } from "@shared/types";

const { t } = useI18n();
const user = useUserStore();
const cart = useCartStore();
const community = useCommunityStore();

const group = ref<GroupBuy | null>(null);
/** 从支付页付完款落到这里（`?paid=1`）。只影响头上那一句「付款成功」 */
const justPaid = ref(false);
const failed = ref(false);
const busy = ref(false);
const currentNo = ref("");
/** 小程序有原生转发；H5 上不画这个按钮（点了什么都不发生），分享走浏览器自己的菜单 */
const nativeShare = canNativeShare();
const now = ref(Date.now());
const tick = setInterval(() => { now.value = Date.now(); }, 1000);
onUnmounted(() => clearInterval(tick));

async function load() {
  if (!currentNo.value) return;
  try {
    group.value = await api.groupBuyDetail(currentNo.value);
    uni.setNavigationBarTitle({ title: title.value });
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

const title = computed(() => {
  const g = group.value;
  if (!g) return "";
  return g.initiatorNickname
    ? String(t("group.titleOf", { name: g.initiatorNickname }))
    : String(t("group.titleMerchant"));
});

/** 能参 = 进行中、没过截止、我还不是成员 */
const open = computed(() => !!group.value && group.value.status === "OPEN" && group.value.expireAt > now.value);
const canJoin = computed(() => open.value && !group.value?.joined);

const countdown = computed(() => {
  if (!group.value) return "";
  const s = Math.max(0, Math.floor((group.value.expireAt - now.value) / 1000));
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
});

/** 人头：已付款的成员 + 空位；我还没参时，第一个空位写「你」 */
const seats = computed(() => {
  const g = group.value;
  if (!g) return [];
  const heads = g.members.map((m) => ({ k: (m.nickname || "·").slice(0, 1), empty: false, me: false }));
  const gap = Math.max(0, g.minCount - heads.length);
  const empties = Array.from({ length: gap }, (_, i) => ({ k: "", empty: true, me: canJoin.value && i === 0 }));
  return [...heads, ...empties];
});

/**
 * 参团：取这件货默认的那个规格加进购物车，带团号去结算。
 * 结算页统一从购物车取数（与详情页「立即购买」同一条路），不另开一条直购链路。
 */
async function join() {
  const g = group.value;
  if (!g || !canJoin.value || busy.value) return;
  busy.value = true;
  try {
    const goods = await api.goodsDetail(g.goodsNo);
    const sku = goods.skus.find((s) => s.stock > 0) ?? goods.skus[0];
    if (!sku) throw new Error(String(t("group.soldOut")));
    await cart.add(g.goodsNo, sku.skuNo, 1);
    uni.navigateTo({
      url: `${ROUTES.orderConfirm}?fulfillment=${defaultFulfillment(goods)}&skus=${sku.skuNo}&groupNo=${g.groupNo}`,
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function openGoods() {
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${group.value?.goodsNo}` });
}

onLoad((q) => {
  currentNo.value = (q?.groupNo as string) || "";
  justPaid.value = q?.paid === "1";
});

const formed = computed(() => group.value?.status === "FORMED");
const failedGroup = computed(() => group.value?.status === "FAILED");
/** 我是开团的人：付款成功那一句说「已开团」而不是「已参团」 */
const iOpened = computed(() => !!group.value?.isOwner);

function openOrder() {
  const no = group.value?.myOrderNo;
  if (no) uni.navigateTo({ url: `${ROUTES.order}?orderNo=${no}` });
}
onShow(() => {
  void load();
});

// 分享文案带进度 —— 「还差 1 人」比「快来拼团」有效得多
onShareAppMessage(() => {
  const g = group.value;
  return buildShareMessage({
    title: g ? String(t("group.shareNeed", { n: g.need, title: g.title })) : "",
    path: `${ROUTES.group}?groupNo=${g?.groupNo ?? ""}`,
    merchantNo: community.pickup?.hostMerchantNo,
    inviterNo: user.user?.cUserNo,
  });
});
</script>

<template>
  <sh-scaffold :pending="!group && !failed" :failed="failed" @retry="load">
    <template v-if="group">
      <view class="hero">
        <template v-if="open">
          <text class="txt-display sh-num">{{ countdown }}</text>
          <text class="txt-sub sh-muted hero__sub">{{ $t("group.leftNeed", { n: group.need }) }}</text>
        </template>
        <template v-else>
          <text class="txt-title">{{ $t(`group.status.${group.status}`) }}</text>
          <text class="txt-sub sh-muted hero__sub sh-num">{{ $t("group.joinedOf", { n: group.joinedCount, m: group.minCount }) }}</text>
        </template>
      </view>

      <view class="sh-row seats">
        <view v-for="(s, i) in seats" :key="i" class="seat" :class="{ 'seat--empty': s.empty, 'seat--me': s.me }">
          <text v-if="!s.empty" class="txt-body">{{ s.k }}</text>
          <text v-else-if="s.me" class="txt-caption txt-primary">{{ $t("group.you") }}</text>
        </view>
      </view>

      <!-- 付完款落到这里（p04）：付款成功那一句 -->
      <view v-if="justPaid && group.joined" class="sh-notice sh-notice--success sh-row paid">
        <text class="sh-fill">{{ $t(iOpened ? "group.paidOpened" : "group.paidJoined") }}</text>
        <text class="sh-num">{{ money(group.groupPrice) }}</text>
      </view>

      <!-- 商品行（p05）：图 + 名 + 团价 + 单买价，整行进商品详情。团页不复制整份详情 -->
      <view class="sh-card sh-row goodsrow" @tap="openGoods">
        <sh-cover class="goodsrow__cover" :src="group.cover" :w="200"></sh-cover>
        <view class="sh-fill goodsrow__main">
          <text class="txt-strong goodsrow__title">{{ group.title }}</text>
          <view class="sh-row sh-row--baseline goodsrow__price">
            <text class="txt-price sh-num">{{ money(group.groupPrice) }}</text>
            <text class="txt-caption txt-quiet sh-num">{{ $t("group.soloPrice", { p: money(group.basePrice) }) }}</text>
          </view>
        </view>
        <text class="txt-caption txt-quiet">{{ $t("group.detailLink") }}</text>
      </view>

      <!-- 已成团（p06）：成团之后他关心的是什么时候到 -->
      <view v-if="formed" class="sh-card steps">
        <text class="txt-body is-success">✓ {{ $t("group.stepPaid") }}</text>
        <text class="txt-body is-success">✓ {{ $t("group.stepFormed") }}</text>
        <text class="txt-body txt-primary">● {{ $t("group.stepPreparing") }}</text>
        <text class="txt-body txt-quiet">○ {{ $t("group.stepDelivered") }}</text>
      </view>

      <!-- 没凑齐（p07）：退了多少、退到哪 —— 只对付过款的人说 -->
      <view v-if="failedGroup && group.joined" class="sh-notice sh-notice--success sh-row paid">
        <text class="sh-fill">{{ $t("group.refunded") }}</text>
        <text class="sh-num">{{ money(group.groupPrice) }}</text>
      </view>

      <view v-if="group.pickupName" class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.pickup") }}</text>
          <text class="txt-body">{{ group.pickupName }}</text>
        </view>
      </view>

      <view v-if="open" class="sh-notice">
        <text class="txt-caption">{{ $t("group.refundNote") }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <!-- 拼团中、我已在团里（p04）：只剩一件事 —— 邀请 -->
          <template v-if="open && group.joined">
            <button v-if="nativeShare" class="sh-btn sh-fill share" open-type="share">{{ $t("group.invite") }}</button>
            <view v-else class="sh-btn sh-fill is-disabled">{{ $t("group.joinedBtn") }}</view>
          </template>
          <!-- 拼团中、我还没参（p05） -->
          <template v-else-if="open">
            <button v-if="nativeShare" class="sh-btn sh-btn--muted sh-fill share" open-type="share">
              {{ $t("group.share") }}
            </button>
            <view class="sh-btn bar__main" :class="{ 'is-disabled': !canJoin || busy }" @tap="join">
              {{ $t("group.joinAt", { p: money(group.groupPrice) }) }}
            </view>
          </template>
          <!-- 已成团（p06） -->
          <view v-else-if="formed && group.myOrderNo" class="sh-btn sh-btn--muted sh-fill" @tap="openOrder">
            {{ $t("group.viewOrder") }}
          </view>
          <!-- 没凑齐（p07）：两条出路。再开一个团 = 去商品页点「开团」 -->
          <template v-else-if="failedGroup">
            <view class="sh-btn sh-btn--muted sh-fill" @tap="openGoods">{{ $t("group.buyAlone") }}</view>
            <view class="sh-btn bar__main" @tap="openGoods">{{ $t("group.reopen") }}</view>
          </template>
          <view v-else class="sh-btn sh-fill is-disabled">{{ $t("group.closed") }}</view>
        </view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16rpx 0 8rpx;
}
.hero__sub {
  margin-top: 8rpx;
}
.seats {
  justify-content: center;
  gap: 16rpx;
  flex-wrap: wrap;
}
.seat {
  width: 88rpx;
  height: 88rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.seat--empty {
  background: transparent;
  border: 2rpx dashed var(--sh-line);
}
.seat--me {
  border-color: var(--sh-primary);
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
.share {
  margin: 0;
}
.paid {
  gap: 16rpx;
}
.goodsrow {
  gap: 20rpx;
}
.goodsrow__cover {
  flex-shrink: 0;
  width: 120rpx;
  height: 120rpx;
}
.goodsrow__main {
  min-width: 0;
}
.goodsrow__title {
  display: block;
}
.goodsrow__price {
  gap: 12rpx;
  margin-top: 8rpx;
}
.steps {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
</style>
