<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 商家团（B-11.6.1 / 6.2）。
//
// 一期的团**绝大多数应该由商家和运营铺出来**，不是等用户自发（ADR-004 §3.3）——
// 所以这个页面是团购这条线的起点。社区里还没人的时候，用户发不起团。
//
// 规则（需求 §五之四）：
//   · 成团单位是**自提点**（拼的是一车送到一个点的成本）
//   · **单档成团**，不做阶梯价
//   · **不成团不作废**，按原价照常发货 —— 生鲜场景下「不成团退款」= 用户白等一天没菜
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money } from "@shared/utils/money";
import { countdown } from "@shared/utils/datetime";
import type { GroupBuy } from "@shared/types";

/** 只取开团要用的三个字段，不把整个 Goods 拖进页面状态 */
interface Groupable {
  goodsNo: string;
  title: string;
  cover: string;
}

const { t } = useI18n();

const groups = ref<GroupBuy[]>([]);
/** 可开团的商品 = 配过 {起团人数, 团购价} 的 —— 没配就没有团价可用 */
const groupable = ref<Groupable[]>([]);
const busy = ref(false);

async function load() {
  const [gs, res] = await Promise.all([api.mGroupList(), api.mGoodsList({ size: 100 })]);
  groups.value = gs;
  groupable.value = res.records
    .filter((g) => g.groupBuy && g.onSale)
    .map((g) => ({ goodsNo: g.goodsNo, title: g.title, cover: g.cover }));
}

async function create(goodsNo: string) {
  if (busy.value) return;
  busy.value = true;
  try {
    await api.mCreateGroup(goodsNo);
    uni.showToast({ title: t("groups.created"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="groups.title" :denied="!merchant.can('biz:campaign')">
    <text class="txt-display">{{ $t("groups.title") }}</text>
    <text class="sh-muted intro">{{ $t("groups.intro") }}</text>

    <text class="txt-title sec">{{ $t("groups.running") }}</text>
    <sh-empty v-if="!groups.length" :text='$t("groups.noRunning")'></sh-empty>

    <view v-for="g in groups" :key="g.groupNo" class="sh-card sh-mb-sm">
      <view class="item__head sh-row">
        <sh-cover class="item__cover" :src="g.cover"></sh-cover>
        <view class="sh-fill">
          <text class="txt-strong item__title">{{ g.title }}</text>
          <text class="sh-muted">{{ g.pickupName }}</text>
        </view>
        <view class="item__price">
          <text class="txt-body sh-num now">{{ money(g.groupPrice) }}</text>
          <text class="txt-caption sh-num base">{{ money(g.basePrice) }}</text>
        </view>
      </view>

      <view class="progress sh-row">
        <text class="sh-chip" :class="g.reached ? 'sh-chip--primary' : 'sh-chip--warning'">
          {{ g.reached ? $t("groups.reached") : $t("groups.need", { n: g.need }) }}
        </text>
        <text class="sh-muted sh-num">{{ $t("groups.joined", { n: g.joinedCount }) }}</text>
        <text class="sh-muted sh-num">{{ countdown(g.expireAt - Date.now()) }}</text>
      </view>
    </view>

    <text class="txt-title sec">{{ $t("groups.canOpen") }}</text>
    <sh-empty v-if="!groupable.length" :text='$t("groups.noGroupable")'></sh-empty>

    <view v-for="g in groupable" :key="g.goodsNo" class="sh-row sh-card row sh-mb-sm">
      <sh-cover class="row__cover" :src="g.cover"></sh-cover>
      <text class="txt-body sh-fill">{{ g.title }}</text>
      <text class="sh-btn sh-btn--sm btn" @tap="create(g.goodsNo)">{{ $t("groups.open") }}</text>
    </view>

    <text class="tip sh-hint">{{ $t("groups.rules") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.intro {
  display: block;
  margin: 0 8rpx;
}
.sec {
  display: block;
  margin: 0 8rpx;
}

.item__head {
  gap: 20rpx;
}
.item__cover {
  width: 88rpx;
  height: 88rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 52rpx;
  text-align: center;
  line-height: 88rpx;
}

.item__title {
  display: block;
}
.item__price {
  text-align: end;
}
.now {
  display: block;
  color: var(--sh-primary-text);
}
.base {
  text-decoration: line-through;
}
.progress {
  gap: 20rpx;
  margin-top: 20rpx;
}
.row {
  gap: 20rpx;
}
.row__cover {
  width: 72rpx;
  height: 72rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 44rpx;
  text-align: center;
  line-height: 72rpx;
}

.btn {

  padding: 18rpx 32rpx;

}
.tip {
  margin: 0 8rpx;
}
</style>
