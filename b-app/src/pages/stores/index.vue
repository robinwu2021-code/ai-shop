<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 门店管理（M6）。
//
// 与「店铺设置」的分工：那一页管**一家店的门面**（公告/营业时间/地址/主推），
// 这一页管**有几家店、哪家是哪家**。分开是因为前者天天改、后者一年动不了几次，
// 且后者每个动作都有硬约束（额度、默认店唯一、收款号必须是自己的）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { PaymentApplyment, Store } from "@shared/types";

const { t } = useI18n();

const stores = ref<Store[]>([]);
const payments = ref<PaymentApplyment[]>([]);
const busy = ref(false);

/** 新建表单：默认收起 —— 大多数商家只有一家店，天天看到一个空表单是噪音 */
const adding = ref(false);
const form = ref({ name: "", address: "" });

/** 可挑的收款号：只列**已开通**的。没开通的挂上去，下一单就收不了款 */
const payOptions = computed(() =>
  payments.value.filter((p) => p.canReceiveMoney && p.payMerchantNo),
);

onShow(load);

async function load() {
  stores.value = await api.mStoreList().catch(() => []);
  payments.value = await api.mPayments().catch(() => []);
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function create() {
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("stores.needName"), icon: "none" });
    return;
  }
  run(async () => {
    await api.mCreateStore({ name: form.value.name.trim(), address: form.value.address.trim() });
    form.value = { name: "", address: "" };
    adding.value = false;
  });
}

/** 停用是「不再接新单」，已有的单照常履约 —— 文案要说清，否则没人敢点 */
function toggleStatus(s: Store) {
  run(() => api.mSetStoreStatus(s.storeNo, s.status !== "ACTIVE"));
}

function makeDefault(s: Store) {
  run(() => api.mSetDefaultStore(s.storeNo));
}

/** 传空 = 回到主体默认收款号，是合法操作 */
function pickPayment(s: Store, payMerchantNo?: string) {
  run(() => api.mSetStorePayment(s.storeNo, payMerchantNo));
}
</script>

<template>
  <sh-scaffold title-key="stores.title" :denied="!merchant.can('biz:store:admin')">
    <view class="head">
      <text class="sh-h1">{{ $t("stores.title") }}</text>
      <text class="sh-muted mt">{{ $t("stores.hint") }}</text>
    </view>

    <view v-for="s in stores" :key="s.storeNo" class="sh-card st">
      <view class="st__top">
        <text class="sh-h2">{{ s.name }}</text>
        <view class="tags">
          <text v-if="s.isDefault" class="tag tag--primary">{{ $t("stores.default") }}</text>
          <text v-if="s.status !== 'ACTIVE'" class="tag">{{ $t("stores.disabled") }}</text>
          <!-- 收不了钱要显眼：店开着但钱进不来，是最容易被忽略的一种坏 -->
          <text v-if="!s.payReady" class="tag tag--warn">{{ $t("stores.payNotReady") }}</text>
        </view>
      </view>

      <text v-if="s.address" class="addr">{{ s.address }}</text>
      <text class="meta">{{ $t("stores.staffCount", { n: s.staffCount }) }}</text>

      <!-- 收款号：空 = 用主体默认号，这是常态不是缺配置 -->
      <view class="pay">
        <text class="pay__label">{{ $t("stores.payment") }}</text>
        <view class="pay__opts">
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': !s.payMerchantNo }"
            @tap="pickPayment(s, undefined)"
          >
            {{ $t("stores.payDefault") }}
          </text>
          <text
            v-for="p in payOptions"
            :key="p.payMerchantNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': s.payMerchantNo === p.payMerchantNo }"
            @tap="pickPayment(s, p.payMerchantNo)"
          >
            {{ p.channelName }}
          </text>
        </view>
      </view>

      <view class="acts">
        <text v-if="!s.isDefault && s.status === 'ACTIVE'" class="act" @tap="makeDefault(s)">
          {{ $t("stores.setDefault") }}
        </text>
        <!-- 默认店没有停用入口：后端也会拒，但按钮就不该出现在那儿 -->
        <text v-if="!s.isDefault" class="act" @tap="toggleStatus(s)">
          {{ s.status === "ACTIVE" ? $t("stores.disable") : $t("stores.enable") }}
        </text>
      </view>
    </view>

    <view v-if="!adding" class="sh-btn sh-btn--soft add" @tap="adding = true">
      {{ $t("stores.add") }}
    </view>

    <view v-else class="sh-card mt-card">
      <text class="sh-h2">{{ $t("stores.add") }}</text>
      <!-- 额度说明放在表单里而不是报错后才说：让人白填一遍再被拒是没道理的 -->
      <text class="hint">{{ $t("stores.quotaHint") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("stores.name") }}</text>
        <input v-model="form.name" class="field__input" :placeholder="$t('stores.namePh')" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("stores.address") }}</text>
        <input v-model="form.address" class="field__input" :placeholder="$t('stores.addressPh')" />
      </view>

      <view class="sh-btn submit" @tap="create">{{ $t("common.save") }}</view>
      <view class="sh-btn sh-btn--soft cancel" @tap="adding = false">{{ $t("common.cancel") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head {
  padding: 32rpx 32rpx 8rpx;
}
.mt {
  margin-top: 12rpx;
}
.mt-card {
  margin-top: 24rpx;
}
.st {
  margin-top: 24rpx;
}
.st__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.tags {
  display: flex;
  gap: 10rpx;
}
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
}
.tag--warn {
  color: var(--sh-danger);
}
.addr,
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.pay {
  margin-top: 20rpx;
}
.pay__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.pay__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 20rpx;
}
.act {
  font-size: 26rpx;
  color: var(--sh-primary);
}
.field {
  margin-top: 28rpx;
}
.field__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.field__input {
  margin-top: 12rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.add {
  margin-top: 24rpx;
}
.submit {
  margin-top: 32rpx;
}
.cancel {
  margin-top: 16rpx;
}
</style>
