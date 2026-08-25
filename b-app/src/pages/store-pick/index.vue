<script setup lang="ts">
/**
 * 选择门店。**唯一的切店入口**（进 App 时多店主体先到这里；之后从「我的」进来）。
 *
 * 只有一家能进的店时这一页不该出现 —— 调用方（App 启动、登录）按
 * `merchant.needsStorePick` 判；这里不再判，否则直接打开 URL 的人会被静默弹走。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { useI18n } from "vue-i18n";
import type { EntityStores } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();
/** 进 App 的那一次：选完去工作台；从「我的」进来的：选完回上一页 */
const entry = ref(false);
const picked = ref("");

/**
 * 按证照分组。**选一家门店同时定了两件事**：用哪张证照、进哪家店 ——
 * 这也是切证照的唯一入口（产品方案 §2.1：日常打交道的是门店，不是证照）。
 *
 * 拿不到分组（老后端、或这次请求失败）时退回单组：把 `merchant.stores` 当成
 * 唯一一张证照的门店。**宁可少一个分组头，也不要整页空白** ——
 * 他打开这一页是为了进店干活。
 */
const groups = computed<EntityStores[]>(() => {
  if (merchant.entityGroups.length) return merchant.entityGroups;
  return merchant.stores.length
    ? [{ entity: null as never, stores: merchant.stores }]
    : [];
});
/** 单证照时整个不画分组头 —— 只有一组的分组是纯噪音 */
const grouped = computed(() => merchant.multiEntity);
const current = computed(() =>
  groups.value.flatMap((g) => g.stores).find((s) => s.storeNo === picked.value));

onLoad(async (q) => {
  entry.value = q?.entry === "1";
  await Promise.all([merchant.ensureStores(), merchant.ensureEntityGroups()]);
  picked.value = merchant.storeNo || merchant.usableStores[0]?.storeNo || "";
});

/** 证照状态 → 那一组标题右边的小字。营业中不出字：没问题的东西不该占视线 */
function entityNote(g: EntityStores): string {
  const st = g.entity?.status;
  if (st === "PENDING_LICENSE") return t("storePick.entityPending");
  if (st && st !== "ACTIVE") return t("storePick.entityClosed");
  return "";
}

function choose(storeNo: string, status: string) {
  if (status !== "ACTIVE") return;
  picked.value = storeNo;
}

function confirm() {
  if (!picked.value) return;
  merchant.pickStore(picked.value);
  if (entry.value) {
    // reLaunch：这一页不该留在栈里，返回键不应回到「选择门店」
    uni.reLaunch({ url: ROUTES.home });
  } else {
    uni.navigateBack();
  }
}
</script>

<template>
  <sh-scaffold title-key="storePick.title">
    <text class="sh-h1">{{ $t("storePick.heading") }}</text>
    <text class="hint">{{ $t("storePick.hint") }}</text>

    <view v-for="g in groups" :key="g.entity?.entityNo || 'only'" class="list">
      <!-- 分组头只在多证照时出现 -->
      <view v-if="grouped" class="group">
        <text class="group__name">{{ g.entity?.name }}</text>
        <text v-if="entityNote(g)" class="group__note">{{ entityNote(g) }}</text>
      </view>
      <view
        v-for="s in g.stores"
        :key="s.storeNo"
        class="sh-card item"
        :class="{ 'is-on': s.storeNo === picked, 'is-off': s.status !== 'ACTIVE' }"
        @tap="choose(s.storeNo, s.status)"
      >
        <view class="item__main">
          <text class="item__name">
            {{ s.name }}<text v-if="s.isDefault" class="sh-chip item__chip">{{ $t("storePick.default") }}</text>
          </text>
          <text class="item__sub">
            <template v-if="s.status !== 'ACTIVE'">{{ $t("storePick.closed") }}</template>
            <template v-else>
              {{ s.address || "—" }}<template v-if="s.storeNo === merchant.storeNo && merchant.storePicked"> · {{ $t("storePick.last") }}</template>
            </template>
          </text>
        </view>
        <view class="item__radio" :class="{ 'is-on': s.storeNo === picked }">
          <text v-if="s.storeNo === picked" class="item__tick">✓</text>
        </view>
      </view>
    </view>

    <view class="sh-btn enter" :class="{ 'is-disabled': !current }" @tap="confirm">
      {{ $t("storePick.enter") }}
    </view>
    <text class="hint center">{{ $t("storePick.crossHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.hint.center {
  margin-top: 20rpx;
  text-align: center;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 24rpx;
}
.group {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  margin-top: 8rpx;
}
.group__name {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-sub);
}
.group__note {
  font-size: 22rpx;
  color: var(--sh-warn, var(--sh-sub));
}
.item {
  display: flex;
  align-items: center;
  gap: 24rpx;
  border: 4rpx solid transparent;
}
.item.is-on {
  border-color: var(--sh-primary);
}
.item.is-off {
  opacity: 0.55;
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__name {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.item__chip {
  margin-inline-start: 12rpx;
  font-weight: 400;
}
.item__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.item__radio {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  border-radius: 9999px;
  border: 3rpx solid var(--sh-line);
  box-sizing: border-box;
}
.item__radio.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.item__tick {
  font-size: 24rpx;
  color: var(--sh-on-primary);
}
.enter {
  margin-top: 32rpx;
}
.enter.is-disabled {
  opacity: 0.5;
}
</style>
