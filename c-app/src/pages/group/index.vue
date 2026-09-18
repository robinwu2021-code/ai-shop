<script setup lang="ts">
/*
 * 参团（原型 s22）。与 B 端团详情（s10）同一版式：倒计时、人头、信息列表，
 * 只是底部按钮换成买家的动作 —— 分享、参团 ¥8。
 *
 * **参团 = 带团号下单，按团价付款，付了款才算一人**（TDD-营销域-详细设计 §1.4）。
 * 此前这里直接调「参团」接口插一行成员，不产生订单与付款：成团价从未被收过，
 * 到期也无钱可退，而提示还写着「先参团的邻居差价已退回」—— 那句话兑现不了，删掉了。
 * 没凑齐的团到期自动整单退款，这一条写在页面上，是买家敢付钱的前提。
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
});
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

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between" @tap="openGoods">
          <text class="txt-body sh-muted">{{ $t("group.goods") }}</text>
          <text class="txt-body">{{ group.title }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.price") }}</text>
          <text class="txt-body sh-num">{{ money(group.groupPrice) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("group.pickup") }}</text>
          <text class="txt-body">{{ group.pickupName || $t("group.anyPickup") }}</text>
        </view>
      </view>

      <view class="sh-notice">
        <text class="txt-caption">{{ $t("group.refundNote") }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <button v-if="nativeShare" class="sh-btn sh-btn--muted sh-fill share" open-type="share">
            {{ $t("group.share") }}
          </button>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': !canJoin || busy }" @tap="join">
            {{ group.joined ? $t("group.joinedBtn") : open ? $t("group.joinAt", { p: money(group.groupPrice) }) : $t("group.closed") }}
          </view>
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
  line-height: inherit;
}
</style>
