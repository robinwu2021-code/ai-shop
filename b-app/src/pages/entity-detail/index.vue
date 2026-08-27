<script setup lang="ts">
/**
 * 证照详情（04 屏）。能看：执照信息、收款账户、资质证件、名下门店。
 *
 * <p><b>门店列表只读、点不进去</b>（产品方案 R8）：要操作某家店必须回门店选择页切过去。
 * 留两条「进店」的路的话，商家迟早会在「改证照资料」和「管这家店」之间
 * 分不清自己走的是哪条 —— 而分不清的表现是在 A 店的页面上改了 B 店的东西。
 *
 * <p>三块资料都带着 `entityNo` 去问后端（B3）：**不用先切到这张证照下的某家店**，
 * 这一页存在的意义就是不用绕那一圈。传别人的证照号后端直接 403。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import type { EntityStores, MyQualifications, PaymentApplyment } from "@shared/types";

const { t } = useI18n();
const entityNo = ref("");
const data = ref<EntityStores | null>(null);
const quals = ref<MyQualifications | null>(null);
const pays = ref<PaymentApplyment[]>([]);
const loading = ref(false);
const denied = ref(false);

const entity = computed(() => data.value?.entity ?? null);
const stores = computed(() => data.value?.stores ?? []);
/** 营业执照那一张。它与其它资质在库里是同一张表，只是 type 不同 */
const license = computed(() =>
  (quals.value?.items ?? []).find((q) => q.qualType === "BUSINESS_LICENSE") ?? null);
/** 除执照外的其它证件 —— 食品经营许可这类 */
const others = computed(() =>
  (quals.value?.items ?? []).filter((q) => q.qualType !== "BUSINESS_LICENSE"));
/**
 * 能不能收钱。**照 `canReceiveMoney` 判，不要自己去比 `applyStatus`** ——
 * 比错的表现是「显示能收钱但收不了」，而这种错要到第一笔订单才暴露。
 */
const payReady = computed(() => pays.value.some((p) => p.canReceiveMoney));

onLoad(async (q) => {
  entityNo.value = q?.entityNo ?? "";
  if (!entityNo.value) return;
  loading.value = true;
  try {
    data.value = await api.mEntity(entityNo.value);
  } catch (e) {
    /*
     * 后端对「不是我的证照」回 403 而不是 404 —— 这一页照它给一句明确的话，
     * 而不是画一个空壳。空壳会让他以为这张证照被删了。
     */
    denied.value = true;
    uni.showToast({ title: (e as Error).message, icon: "none" });
    loading.value = false;
    return;
  }
  /*
   * 证件与进件**各自失败各自算**：收款进件挂了不该把执照那一块也拖成空白，
   * 反过来也一样。它们是两条独立的链路，商家只要有一条能看就有下一步可做。
   */
  const [qs, ps] = await Promise.all([
    api.mQualifications(entityNo.value).catch(() => null),
    api.mPayments(entityNo.value).catch(() => []),
  ]);
  quals.value = qs;
  pays.value = ps;
  loading.value = false;
});

function statusText(s?: string): string {
  if (s === "ACTIVE") return t("entities.stActive");
  if (s === "PENDING_LICENSE") return t("entities.stPending");
  if (s === "REVIEWING" || s === "PENDING") return t("entities.stReviewing");
  if (s === "REJECTED") return t("entities.stRejected");
  return t("entities.stClosed");
}

/** 去传/换这张证照的证件。`entityNo` 带过去 —— 那一页据它决定传给哪张证照 */
function editQuals() {
  uni.navigateTo({ url: `${ROUTES.qualifications}?entityNo=${entityNo.value}` });
}
function editPayment() {
  uni.navigateTo({ url: `${ROUTES.payment}?entityNo=${entityNo.value}` });
}
/** 要管某家店，得先回选店页切过去 —— 这一页不提供第二条进店的路 */
function goPickStore() {
  uni.navigateTo({ url: ROUTES.storePick });
}
</script>

<template>
  <sh-scaffold title-key="entityDetail.title">
    <view v-if="denied" class="sh-card">
      <text class="txt-display">{{ $t("entityDetail.denied") }}</text>
      <text class="hint">{{ $t("entityDetail.deniedHint") }}</text>
    </view>

    <template v-else>
      <view class="sh-card head">
        <text class="head__name">{{ entity?.name || "—" }}</text>
        <text class="head__sub">
          {{ statusText(entity?.status) }}
          <template v-if="entity?.legalForm"> · {{ entity.legalForm }}</template>
          <template v-if="entity?.verified"> · {{ $t("entityDetail.verified") }}</template>
        </text>
      </view>

      <!-- 营业执照 -->
      <view class="sh-card block" @tap="editQuals">
        <view class="block__row">
          <text class="block__title">{{ $t("entityDetail.license") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
        <text class="block__val" :class="{ 'is-empty': !license }">
          {{ license ? license.qualName : $t("entityDetail.licenseEmpty") }}
        </text>
        <text v-if="!license" class="hint">{{ $t("entityDetail.licenseWhy") }}</text>
      </view>

      <!-- 收款账户 -->
      <view class="sh-card block" @tap="editPayment">
        <view class="block__row">
          <text class="block__title">{{ $t("entityDetail.payment") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
        <text class="block__val" :class="{ 'is-empty': !payReady }">
          {{ payReady ? $t("entityDetail.payReady") : $t("entityDetail.payNotReady") }}
        </text>
      </view>

      <!-- 其它资质 -->
      <view class="sh-card block" @tap="editQuals">
        <view class="block__row">
          <text class="block__title">{{ $t("entityDetail.quals") }}</text>
          <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
        </view>
        <text class="block__val" :class="{ 'is-empty': !others.length }">
          {{ others.length ? $t("entityDetail.qualsCount", { n: others.length }) : $t("entityDetail.qualsEmpty") }}
        </text>
      </view>

      <!-- 名下门店：只读 -->
      <view class="block">
        <text class="block__title">{{ $t("entityDetail.stores") }}</text>
        <view class="stores">
          <view v-for="s in stores" :key="s.storeNo" class="sh-card store">
            <text class="store__name">
              {{ s.name }}
              <text v-if="s.isDefault" class="sh-chip store__chip">{{ $t("storePick.default") }}</text>
            </text>
            <text class="store__sub">
              {{ s.status === "ACTIVE" ? (s.address || "—") : $t("storePick.closed") }}
            </text>
          </view>
          <text v-if="!stores.length" class="hint">{{ $t("entityDetail.noStore") }}</text>
        </view>
        <!-- R8：这里不给「进店」，只给一条回选店页的路 -->
        <text class="hint">{{ $t("entityDetail.storesReadonly") }}</text>
        <sh-go :text="String($t('entityDetail.goPick'))" @tap="goPickStore"></sh-go>
      </view>
    </template>
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
.head {
  margin-top: 8rpx;
}
.head__name {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.head__sub {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.block {
  margin-top: 16rpx;
}
.block__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.block__title {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.block__val {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.block__val.is-empty {
  color: var(--sh-sub);
}
.stores {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  margin-top: 12rpx;
}
.store__name {
  display: block;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.store__chip {
  margin-inline-start: 12rpx;
}
.store__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.sh-link {
  display: block;
  margin-top: 12rpx;
}
</style>
