<script setup lang="ts">
// 团购入口页。
//
// 两条线**刻意分 tab**，因为它们根本不是一回事：
//   商家团 —— 供给找需求：商品已上架、价格已定，用户参与就行（生鲜日用这类高频标品）
//   邻里求团 —— 需求找供给：发起时商品还不存在，靠邻居 +1 攒够量再让商家报价（床垫、校服）
// 混在一个列表里，用户会分不清「这个能直接买」和「这个还只是个想法」。
//
// 初期团很少，所以这个入口**不放首页** —— 空着的楼层比没有更差。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useCommunityStore } from "@/stores/community";
import { ROUTES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import type { Goods, GroupBuy, GroupRequest } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();

const tab = ref<"merchant" | "request">("merchant");
const groups = ref<GroupBuy[]>([]);
const requests = ref<GroupRequest[]>([]);
const now = ref(Date.now());
const loaded = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;

const pickupNo = computed(() => community.pickup?.pickupNo);

async function load() {
  const [g, r] = await Promise.all([
    api.groupBuyList(pickupNo.value),
    api.requestList(pickupNo.value),
  ]);
  groups.value = g;
  requests.value = r;
  loaded.value = true;
}

function openGroup(g: GroupBuy) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${g.groupNo}` });
}

function openRequest(r: GroupRequest) {
  uni.navigateTo({ url: `${ROUTES.request}?requestNo=${r.requestNo}` });
}

/**
 * 发起一个团（C-GB-05 + C-GB-06）。
 * 「送到我家」是邻里自提：床垫、校服这类东西没有门店可提，只能送到发起人家里。
 * 勾了之后建的是一个**团粒度的临时自提点**，随团创建、随团消失（ADR-005）。
 */
const creating = ref(false);
const groupable = ref<Goods[]>([]);
const form = ref({ goodsNo: "", toMyHome: false, address: "", timeSlot: "" });

async function openCreate() {
  const res = await api.goodsList({ size: 100 });
  groupable.value = res.records.filter((g) => g.groupBuy);
  form.value = { goodsNo: groupable.value[0]?.goodsNo ?? "", toMyHome: false, address: "", timeSlot: "" };
  creating.value = true;
}

async function submitCreate() {
  const f = form.value;
  if (!f.goodsNo) return;
  // 送到我家必须填地址与时段：邻居家不能一直堆着货，没有时段就没法约（B15）
  if (f.toMyHome && (!f.address.trim() || !f.timeSlot.trim())) {
    uni.showToast({ title: String(t("groups.needAddress")), icon: "none" });
    return;
  }
  try {
    const g = await api.createGroupBuy(
      f.goodsNo,
      pickupNo.value ?? "",
      f.toMyHome
        ? { toMyHome: true, address: f.address.trim(), timeSlot: f.timeSlot.trim() }
        : undefined,
    );
    creating.value = false;
    await load();
    openGroup(g);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function createRequest() {
  uni.navigateTo({ url: ROUTES.requestCreate });
}

onShow(() => {
  load();
  timer = setInterval(() => (now.value = Date.now()), 1000);
});
</script>

<template>
  <sh-scaffold title-key="groups.title">
    <sh-tabs
      :items="[
        { key: 'merchant', label: String($t('groups.merchantTab', { n: groups.length })) },
        { key: 'request', label: String($t('groups.requestTab', { n: requests.length })) },
      ]"
      :active="tab"
      @change="(k: string) => (tab = k as typeof tab)"
    ></sh-tabs>

    <!-- 商家团：商品现成的，参与就行 -->
    <template v-if="tab === 'merchant'">
      <text class="hint">{{ $t("groups.merchantHint") }}</text>

      <view class="sh-btn sh-btn--soft mkgroup" @tap="openCreate">
        {{ $t("groups.createGroup") }}
      </view>

      <!-- 发起团表单：商品 + 是否送到我家 -->
      <view v-if="creating" class="sh-card form">
        <text class="sh-h2">{{ $t("groups.createGroup") }}</text>
        <view class="chips">
          <text
            v-for="g in groupable"
            :key="g.goodsNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.goodsNo === g.goodsNo }"
            @tap="form.goodsNo = g.goodsNo"
          >
            {{ g.title }}
          </text>
        </view>

        <view class="toggle" @tap="form.toMyHome = !form.toMyHome">
          <sh-check :model-value="form.toMyHome"></sh-check>
          <view class="toggle__main">
            <text class="toggle__label">{{ $t("groupHost.toMyHome") }}</text>
            <text class="sh-muted">{{ $t("groupHost.toMyHomeHint") }}</text>
          </view>
        </view>

        <template v-if="form.toMyHome">
          <input maxlength="255" v-model="form.address" class="field__input" :placeholder="$t('groupHost.addressPh')" />
          <input maxlength="64" v-model="form.timeSlot" class="field__input" :placeholder="$t('groupHost.timeSlotPh')" />
          <text class="privacy">{{ $t("groupHost.addressPrivacy") }}</text>
        </template>

        <view class="btns">
          <text class="btn btn--ghost" @tap="creating = false">{{ $t("common.cancel") }}</text>
          <text class="btn" @tap="submitCreate">{{ $t("groups.submitCreate") }}</text>
        </view>
      </view>
      <biz-group-card
        v-for="g in groups"
        :key="g.groupNo"
        :group="g"
        :now="now"
        @tap="openGroup(g)"
      ></biz-group-card>
      <sh-empty bare v-if="loaded && !groups.length" :text='$t("groups.merchantEmpty")'></sh-empty>
    </template>

    <!-- 邻里求团：先有需求，后有供给 -->
    <template v-else>
      <text class="hint">{{ $t("groups.requestHint") }}</text>

      <view v-for="r in requests" :key="r.requestNo" class="sh-card rq" @tap="openRequest(r)">
        <view class="rq__head">
          <text class="rq__avatar">{{ r.initiatorAvatar }}</text>
          <view class="rq__who">
            <text class="rq__title">{{ r.title }}</text>
            <text class="rq__by">
              {{ $t("groups.startedBy", { name: r.initiatorNickname }) }} · {{ r.pickupName }}
            </text>
          </view>
        </view>

        <text class="rq__desc">{{ r.desc }}</text>

        <view class="rq__meta">
          <text class="sh-chip sh-chip--primary sh-num">
            {{ $t("groups.interested", { n: r.interestedCount }) }}
          </text>
          <text
            v-if="r.quotes.length"
            class="sh-chip sh-chip--warning sh-num"
          >
            {{ $t("groups.quotes", { n: r.quotes.length }) }}
          </text>
          <text v-else class="sh-chip">{{ $t("groups.noQuote") }}</text>
          <text v-if="r.quotes.length" class="sh-chip sh-num">
            {{ $t("groups.lowest", { p: money(r.quotes[0]!.priceMinor) }) }}
          </text>
        </view>
      </view>

      <sh-empty bare v-if="loaded && !requests.length" :text='$t("groups.requestEmpty")'></sh-empty>

      <sh-actionbar :pad="160">
        <view class="sh-btn" @tap="createRequest">{{ $t("groups.createGroup") }}</view>
      </sh-actionbar>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.mkgroup {
  margin-bottom: 24rpx;
}
.form {
  margin-bottom: 24rpx;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin: 20rpx 0;
}
.toggle {
  display: flex;
  gap: 16rpx;
  align-items: flex-start;
  padding: 20rpx 0;
}
.toggle__main {
  flex: 1;
}
.toggle__label {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
/* 只留纵向间距。此前这里高 84rpx，与 base.css 的 88rpx 差 2px ——
   88rpx ≈ 44pt 是点按目标的下限，缩到 84 省不出什么却贴着下限走 */
.field__input {
  margin-top: 16rpx;
}
.privacy {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
.btns {
  display: flex;
  gap: 16rpx;
  margin-top: 28rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
}
.btn--ghost {
  background: var(--sh-faint);
  color: var(--sh-sub);
}

.hint {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin: 24rpx 4rpx;
}
.rq {
  margin-bottom: 20rpx;
}
.rq__head {
  display: flex;
  gap: 20rpx;
  align-items: center;
}
.rq__avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  text-align: center;
  line-height: 72rpx;
  font-size: 36rpx;
  flex-shrink: 0;
}
.rq__who {
  flex: 1;
  min-width: 0;
}
.rq__title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.rq__by {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
.rq__desc {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
  margin-top: 20rpx;
}
.rq__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
</style>
