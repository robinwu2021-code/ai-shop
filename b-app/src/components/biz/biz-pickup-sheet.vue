<script setup lang="ts">
/**
 * 「社区自提点」这一路的取货点管理（P1，方案 v4 §4.1）。
 *
 * 两件事在一张弹层里：挑系统里已有的点（范围内的常驻点）、自建一个（落 PENDING 待运营核实）。
 * 选完不在这里保存 —— 交回页 A 和开关一起走 PUT /fulfillment（pickupNos 全量替换），
 * 否则会出现「点选了、开关没开」这种一半一半的状态。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { composeAddress, pickOnMap } from "@/utils/geo";
import type { PickupCandidate } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  storeNo: string;
  /** 当前已引用的点 */
  selected: string[];
}>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "done", pickupNos: string[]): void;
}>();

const { t } = useI18n();

const candidates = ref<PickupCandidate[]>([]);
const loading = ref(false);
const picked = ref<string[]>([]);

async function load() {
  loading.value = true;
  try {
    candidates.value = await api.mPickupCandidates(props.storeNo || "default");
  } catch {
    candidates.value = [];
  } finally {
    loading.value = false;
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    picked.value = [...props.selected];
    buildOpen.value = false;
    void load();
  }
});

/** 可勾的：ACTIVE，或本店自建的 PENDING（别家的待审点对你而言还不存在） */
function selectable(c: PickupCandidate) {
  return c.status === "ACTIVE" || (c.status === "PENDING" && c.ownerStoreNo === props.storeNo);
}
function isOn(no: string) {
  return picked.value.includes(no);
}
function toggle(c: PickupCandidate) {
  if (!selectable(c)) return;
  picked.value = isOn(c.pickupNo)
    ? picked.value.filter((x) => x !== c.pickupNo)
    : [...picked.value, c.pickupNo];
}
const mine = computed(() => candidates.value.filter((c) => c.ownerStoreNo === props.storeNo));
const others = computed(() => candidates.value.filter((c) => c.ownerStoreNo !== props.storeNo));

// ---------------------------------------------------------------- 自建
const buildOpen = ref(false);
const form = ref({ name: "", address: "", openHours: "" });
const coords = ref<{ lat: number; lng: number } | null>(null);
const locating = ref(false);
const submitting = ref(false);

/**
 * 在地图上标这个点。之前是「定位一次」—— 商家通常在店里填表，不是站在取货点上，
 * 存下来的是商家当时的位置，而 withinRadius 判定全靠这个坐标。
 * 选点页默认停在上次标的点（没有就当前位置）；地址栏空着就用选点给的地址填上。
 */
async function locate() {
  if (locating.value) return;
  locating.value = true;
  try {
    const p = await pickOnMap(t, coords.value);
    if (!p) return;
    coords.value = { lat: p.lat, lng: p.lng };
    if (!form.value.address.trim()) form.value.address = composeAddress(p).slice(0, 100);
  } finally {
    locating.value = false;
  }
}

async function submitBuild() {
  const name = form.value.name.trim();
  const address = form.value.address.trim();
  if (!name || !address) {
    uni.showToast({ title: t("store.pickup.needNameAddr"), icon: "none" });
    return;
  }
  if (name.length < 2 || address.length < 4) {
    uni.showToast({ title: t("store.pickup.tooShort"), icon: "none" });
    return;
  }
  // 坐标必填：没坐标的点买家用定位永远找不到（withinRadius 对空坐标恒 false）
  if (!coords.value) {
    uni.showToast({ title: t("store.pickup.needCoords"), icon: "none" });
    return;
  }
  submitting.value = true;
  try {
    const created = await api.mSelfBuildPickup({
      storeNo: props.storeNo || "default",
      name,
      address,
      latE6: Math.round(coords.value.lat * 1e6),
      lngE6: Math.round(coords.value.lng * 1e6),
      openHours: form.value.openHours.trim() || undefined,
    });
    candidates.value = [created, ...candidates.value];
    // 自建的点默认勾上：建它就是为了用它
    picked.value = [...picked.value, created.pickupNo];
    form.value = { name: "", address: "", openHours: "" };
    coords.value = null;
    buildOpen.value = false;
    uni.showToast({ title: t("store.pickup.built"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || t("store.pickup.buildFailed"), icon: "none" });
  } finally {
    submitting.value = false;
  }
}

function done() {
  emit("done", picked.value);
  emit("update:visible", false);
}
function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="mask" @tap="close">
    <view class="sheet" @tap.stop>
      <view class="sheet__head">
        <text class="sheet__title">{{ $t("store.pickup.title") }}</text>
        <text class="sheet__count">{{ $t("store.picker.selected", { n: picked.length }) }}</text>
      </view>
      <text class="hint">{{ $t("store.pickup.lead") }}</text>

      <scroll-view scroll-y class="body">
        <text v-if="loading" class="hint">{{ $t("common.loading") }}</text>
        <template v-else>
          <text v-if="mine.length" class="group">{{ $t("store.pickup.mine") }}</text>
          <view v-for="c in mine" :key="c.pickupNo" class="row" :class="{ 'is-off': !selectable(c) }" @tap="toggle(c)">
            <view class="row__main">
              <text class="row__name">
                {{ c.name }}<text v-if="c.status !== 'ACTIVE'" class="sh-chip row__st" :class="c.status === 'PENDING' ? 'sh-chip--warning' : ''">{{ $t(`store.pickup.st${c.status}`) }}</text>
              </text>
              <text class="row__sub">{{ c.address || c.communityName }}</text>
              <text v-if="c.status === 'REJECTED' && c.rejectReason" class="row__reason">{{ c.rejectReason }}</text>
            </view>
            <view v-if="selectable(c)" class="row__check" :class="{ 'is-on': isOn(c.pickupNo) }">
              <text v-if="isOn(c.pickupNo)" class="row__tick">✓</text>
            </view>
          </view>

          <text v-if="others.length" class="group">{{ $t("store.pickup.nearby") }}</text>
          <view v-for="c in others" :key="c.pickupNo" class="row" @tap="toggle(c)">
            <view class="row__main">
              <text class="row__name">{{ c.name }}</text>
              <text class="row__sub">{{ c.communityName }}<template v-if="c.address"> · {{ c.address }}</template></text>
            </view>
            <view class="row__check" :class="{ 'is-on': isOn(c.pickupNo) }">
              <text v-if="isOn(c.pickupNo)" class="row__tick">✓</text>
            </view>
          </view>
          <text v-if="!mine.length && !others.length" class="hint">{{ $t("store.pickup.empty") }}</text>

          <!-- 自建 -->
          <view v-if="!buildOpen" class="row row--build" @tap="buildOpen = true">
            <text class="row__build">{{ $t("store.pickup.buildEntry") }}</text>
          </view>
          <view v-else class="build">
            <text class="hint">{{ $t("store.pickup.buildHint") }}</text>
            <input v-model="form.name" class="field__input" :maxlength="30" :placeholder="$t('store.pickup.namePh')" />
            <input v-model="form.address" class="field__input" :maxlength="100" :placeholder="$t('store.pickup.addressPh')" />
            <text class="field__label">{{ $t("store.pickup.hoursPh") }}</text>
            <biz-time-range v-model="form.openHours" clearable></biz-time-range>
            <view class="locate" :class="{ 'is-ok': !!coords }" @tap="locate">
              <sh-icon name="pin" :size="18" :color="coords ? 'var(--sh-primary-text)' : 'var(--sh-sub)'"></sh-icon>
              <text class="locate__t">{{ locating ? $t("common.loading") : coords ? $t("store.pickup.pinned") : $t("store.pickup.pin") }}</text>
            </view>
            <view class="build__btns">
              <text class="sh-btn sh-btn--soft build__go" @tap="submitBuild">{{ submitting ? "…" : $t("common.submit") }}</text>
              <text class="mini" @tap="buildOpen = false">{{ $t("common.cancel") }}</text>
            </view>
          </view>
        </template>
      </scroll-view>

      <view class="foot">
        <view class="sh-btn" @tap="done">{{ $t("store.picker.done", { n: picked.length }) }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: var(--sh-scrim);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  height: 84vh;
  border-radius: 32rpx 32rpx 0 0;
  background: var(--sh-surface);
}
.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx 8rpx;
}
.sheet__title {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.sheet__count {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.hint {
  display: block;
  padding: 8rpx 32rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.body {
  flex: 1;
  min-height: 0;
  margin-top: 8rpx;
}
.group {
  display: block;
  padding: 16rpx 32rpx 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 32rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.row.is-off {
  opacity: 0.6;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__st {
  margin-inline-start: 12rpx;
  font-weight: 400;
}
.row__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.row__reason {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.row__check {
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
.row__check.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.row__tick {
  font-size: 24rpx;
  color: var(--sh-on-primary);
}
.row--build {
  border-bottom: none;
}
.row__build {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.build {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 8rpx 32rpx 24rpx;
}
.locate {
  display: inline-flex;
  align-items: center;
  gap: 8rpx;
  align-self: flex-start;
  padding: 14rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.locate.is-ok {
  background: var(--sh-primary-tint);
}
.locate__t {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.locate.is-ok .locate__t {
  color: var(--sh-primary-text);
}
.build__btns {
  display: flex;
  gap: 16rpx;
}
.build__go {
  flex: 1;
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.foot {
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  border-top: 2rpx solid var(--sh-line);
}
</style>
