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
  close: [];
  done: [pickupNos: string[]];
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
  emit("close");
}
function close() {
  emit("close");
}
</script>

<template>
  <!--
    形态归 `sh-sheet`（遮罩、面板、圆角、把手、关闭、安全区、定高与内滚都在它那儿）。
    这一份此前自己画了一遍 —— 连同 `biz-region-picker` 是同一个形状写了两遍，
    所以「定高 + 贴底页脚 + 通铺到边」补进了库件，不是留在这儿。
  -->
  <sh-sheet
    :visible="visible"
    flush
    :title="String($t('store.pickup.title'))"
    :hint="String($t('store.pickup.lead'))"
    @close="close"
  >
    <text v-if="loading" class="txt-caption hint">{{ $t("common.loading") }}</text>
    <template v-else>
      <text v-if="mine.length" class="txt-caption group">{{ $t("store.pickup.mine") }}</text>
      <view v-for="c in mine" :key="c.pickupNo" class="sh-row row" :class="{ 'is-off': !selectable(c) }" @tap="toggle(c)">
        <view class="sh-fill">
          <text class="txt-strong row__name">
            {{ c.name }}<text v-if="c.status !== 'ACTIVE'" class="sh-chip row__st" :class="c.status === 'PENDING' ? 'sh-chip--warning' : ''">{{ $t(`store.pickup.st${c.status}`) }}</text>
          </text>
          <text class="txt-caption row__sub">{{ c.address || c.communityName }}</text>
          <text v-if="c.status === 'REJECTED' && c.rejectReason" class="txt-caption row__reason">{{ c.rejectReason }}</text>
        </view>
        <sh-check v-if="selectable(c)" round :model-value="isOn(c.pickupNo)"></sh-check>
      </view>

      <text v-if="others.length" class="txt-caption group">{{ $t("store.pickup.nearby") }}</text>
      <view v-for="c in others" :key="c.pickupNo" class="sh-row row" @tap="toggle(c)">
        <view class="sh-fill">
          <text class="txt-strong row__name">{{ c.name }}</text>
          <text class="txt-caption row__sub">{{ c.communityName }}<template v-if="c.address"> · {{ c.address }}</template></text>
        </view>
        <sh-check round :model-value="isOn(c.pickupNo)"></sh-check>
      </view>
      <text v-if="!mine.length && !others.length" class="txt-caption hint">{{ $t("store.pickup.empty") }}</text>

      <!-- 自建 -->
      <view v-if="!buildOpen" class="sh-row row row--build" @tap="buildOpen = true">
        <text class="txt-sub txt-primary">{{ $t("store.pickup.buildEntry") }}</text>
      </view>
      <view v-else class="build">
        <text class="txt-caption hint">{{ $t("store.pickup.buildHint") }}</text>
        <input v-model="form.name" class="field__input" :maxlength="30" :placeholder="$t('store.pickup.namePh')" />
        <input v-model="form.address" class="field__input" :maxlength="100" :placeholder="$t('store.pickup.addressPh')" />
        <text class="field__label">{{ $t("store.pickup.hoursPh") }}</text>
        <biz-time-range v-model="form.openHours" clearable></biz-time-range>
        <!-- 定位入口就是一颗 chip：未定位灰底（`.sh-chip` 本态），已定位主色 tint
             （`--primary`）。此前自己画了一份「faint → primary-tint」，正是判据说的
             「把 chip 的处理抄在了别的东西上」。不用 sh-option 是因为那个件带描边，
             而这里从来没有边。 -->
        <view
          class="sh-chip sh-chip--icon locate"
          :class="{ 'sh-chip--primary': !!coords }"
          @tap="locate"
        >
          <sh-icon name="pin" :size="18" :color="coords ? 'var(--sh-primary-text)' : 'var(--sh-sub)'"></sh-icon>
          <text>{{ locating ? $t("common.loading") : coords ? $t("store.pickup.pinned") : $t("store.pickup.pin") }}</text>
        </view>
        <view class="sh-row build__btns">
          <text class="sh-btn sh-btn--soft build__go" @tap="submitBuild">{{ submitting ? "…" : $t("common.submit") }}</text>
          <text class="sh-btn sh-btn--muted sh-btn--sm" @tap="buildOpen = false">{{ $t("common.cancel") }}</text>
        </view>
      </view>
    </template>

    <template #foot>
      <view class="sh-btn" @tap="done">{{ $t("store.picker.done", { n: picked.length }) }}</view>
    </template>
  </sh-sheet>
</template>

<style scoped>
.hint {
  display: block;
  padding: 8rpx 32rpx;
  color: var(--sh-sub);
}
.group {
  display: block;
  padding: 16rpx 32rpx 4rpx;
  color: var(--sh-sub);
}
/* 通铺到边的一行。左右 32rpx 是行自己的留白（弹层已由 flush 让开） */
.row {
  gap: 20rpx;
  padding: 22rpx 32rpx;
  border-bottom: var(--sh-hairline);
}
.row.is-off {
  opacity: 0.6;
}
.row__name {
  display: block;
  color: var(--sh-ink);
}
.row__st {
  margin-inline-start: 12rpx;
}
.row__sub {
  display: block;
  margin-top: 4rpx;
}
.row__reason {
  display: block;
  margin-top: 4rpx;
  color: var(--sh-danger);
}
.row--build {
  border-bottom: none;
}
.build {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 8rpx 32rpx 24rpx;
}
/* 只留版面：圆角、内边距、底色与选中态都归 .sh-chip */
.locate {
  align-self: flex-start;
}
.build__btns {
  gap: 16rpx;
}
.build__go {
  flex: 1;
}
</style>
