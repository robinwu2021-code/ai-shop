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
    <text class="txt-display">{{ $t("storePick.heading") }}</text>
    <text class="sh-hint">{{ $t("storePick.hint") }}</text>

    <view v-for="g in groups" :key="g.entity?.entityNo || 'only'" class="list">
      <!-- 分组头只在多证照时出现 -->
      <view v-if="grouped" class="group">
        <text class="txt-strong group__name">{{ g.entity?.name }}</text>
        <text v-if="entityNote(g)" class="txt-caption group__note">{{ entityNote(g) }}</text>
      </view>
      <view
        v-for="s in g.stores"
        :key="s.storeNo"
        class="sh-row sh-card item"
        :class="{ 'is-on': s.storeNo === picked, 'is-off': s.status !== 'ACTIVE' }"
        @tap="choose(s.storeNo, s.status)"
      >
        <view class="sh-fill">
          <text class="txt-strong item__name">
            {{ s.name }}<text v-if="s.isDefault" class="sh-chip item__chip">{{ $t("storePick.default") }}</text>
          </text>
          <text class="txt-caption item__sub">
            <template v-if="s.status !== 'ACTIVE'">{{ $t("storePick.closed") }}</template>
            <template v-else>
              {{ s.address || "—" }}<template v-if="s.storeNo === merchant.storeNo && merchant.storePicked"> · {{ $t("storePick.last") }}</template>
            </template>
          </text>
        </view>
        <sh-check round :model-value="s.storeNo === picked"></sh-check>
      </view>
    </view>

    <view class="sh-btn enter" :class="{ 'is-disabled': !current }" @tap="confirm">
      {{ $t("storePick.enter") }}
    </view>
    <text class="center sh-hint">{{ $t("storePick.crossHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint.center {
  text-align: center;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.group {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  margin-top: 8rpx;
}
.group__name {
  color: var(--sh-sub);
}
/* ⚠️ 此前写的是 `var(--sh-warn, var(--sh-sub))` —— **`--sh-warn` 这个变量不存在**
   （正名是 `--sh-warning`），于是这行字永远走兜底、渲染成普通灰。
   它本该是一句提醒（这一组里有店打烊了），灰下去就跟旁边的说明文字一样重。
   皮肤变量守卫故意放行带兜底的写法（「拼错了也还有兜底」），
   而这恰恰是它看不见的那一类：**兜底把拼错的后果盖住了**。 */
.group__note {
  color: var(--sh-warning);
}
.item {
  gap: 24rpx;
  border: 4rpx solid transparent;
}
.item.is-on {
  border-color: var(--sh-primary);
}
.item.is-off {
  opacity: 0.55;
}

.item__name {
  display: block;
}
.item__chip {
  margin-inline-start: 12rpx;
}
.item__sub {
  display: block;
  margin-top: 4rpx;
}

.enter.is-disabled {
  opacity: 0.5;
}
</style>
