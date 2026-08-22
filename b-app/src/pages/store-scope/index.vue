<script setup lang="ts">
/**
 * 页 A「经营范围与送货」（方案 v3）：开店的两个决策，各一张卡。
 *
 * - 经营范围：主体级，全店共用；统一列表，不分组、不标行政级别；一个入口「添加范围」。
 *   改动攒到页面级吸底保存条。
 * - 送货方式：门店级；开关即点即存（它是独立端点，攒到大保存里会出现
 *   「范围存了送货没存」这种一半一半的状态）；开着的路下面一行配置摘要。
 *
 * 装修与获客拆去了页 B（pages/store）：那是日常会反复改的内容，和这两个决策不同频。
 */
import { computed, ref } from "vue";
import { onBackPress, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import type {
  CommunityApply,
  DeliveryRule,
  ServiceArea,
  StoreFulfillment,
  StoreProfile,
} from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

// ---------------------------------------------------------------- 经营范围（主体级）
const form = ref<StoreProfile>({
  announcement: "",
  openHours: "",
  address: "",
  featured: [],
  serviceScope: SERVICE_SCOPE.COMMUNITY,
  serviceCommunityNos: [],
  fulfillmentReach: FULFILLMENT_REACH.PICKUP,
  serviceAreas: [],
});
const loaded = ref(false);
/** 打开页面时的快照，用来判「有没有改」 */
const snapshot = ref("");

const areas = computed<ServiceArea[]>(() => form.value.serviceAreas ?? []);
const activeAreas = computed(() => areas.value.filter((a) => a.status !== "PENDING"));
const dirty = computed(() => loaded.value && JSON.stringify(areas.value) !== snapshot.value);

/**
 * 已选项的显示拆成「名字 + 路径」：name 存的是整条路径（"浙江省 / 杭州市 / 西湖区 / 阳光花园"），
 * 列表里主标题取最后一段，路径作次要文字 —— 不分组、不标级别，一眼看得出是哪里。
 */
function splitName(a: ServiceArea) {
  const parts = (a.name || a.refCode).split(" / ");
  return { main: parts[parts.length - 1] ?? a.refCode, path: parts.slice(0, -1).join(" · ") };
}
function isWhole(a: ServiceArea) {
  return a.level !== "COMMUNITY";
}

function removeArea(a: ServiceArea) {
  form.value.serviceAreas = areas.value.filter((x) => !(x.level === a.level && x.refCode === a.refCode));
}

const pickerOpen = ref(false);
function setAreas(v: ServiceArea[]) {
  form.value.serviceAreas = v;
}

// 提报进度：不显示的话商家会以为没提交成功，隔天再提一次同样的
const applies = ref<CommunityApply[]>([]);
const pendingApplies = computed(() => applies.value.filter((a) => a.status === "PENDING"));
const rejectedApplies = computed(() => applies.value.filter((a) => a.status === "REJECTED"));
function onApplied(a: CommunityApply) {
  applies.value = [a, ...applies.value];
}

// ---------------------------------------------------------------- 送货方式（门店级）
const fulfillment = ref<StoreFulfillment | null>(null);
const savingChannel = ref("");
const channelRows = computed(() => fulfillment.value?.channels ?? []);
const on = (ch: string) => channelRows.value.some((c) => c.channel === ch && c.enabled);
const pickupOn = computed(() => on("STORE_PICKUP") || on("NEIGHBOR_PICKUP"));
const deliveryOn = computed(() => on("MERCHANT_DELIVERY"));
const expressOn = computed(() => on("EXPRESS"));

/**
 * 覆盖项为空的含义**由送货方式决定**：只有自提是「谁也看不到」（拦），
 * 开了自送/快递是「不限」（正常）。判的是生效中的项 —— 待审的不参与展开。
 */
const emptyIsBlocking = computed(
  () => pickupOn.value && !deliveryOn.value && !expressOn.value && !activeAreas.value.length,
);

async function loadFulfillment() {
  try {
    fulfillment.value = await api.mStoreFulfillment(merchant.storeNo || "default");
  } catch {
    fulfillment.value = null;
  }
}

async function persistChannels(next: StoreFulfillment["channels"], channel: string) {
  savingChannel.value = channel;
  try {
    fulfillment.value = await api.mSaveStoreFulfillment(merchant.storeNo || "default", {
      channels: next.map((c) => ({
        channel: c.channel,
        enabled: c.enabled,
        templateNo: c.templateNo ?? undefined,
      })),
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || t("store.fulfillFailed"), icon: "none" });
  } finally {
    savingChannel.value = "";
  }
}

async function toggleChannel(channel: string) {
  const cur = fulfillment.value;
  if (!cur || savingChannel.value) return;
  const row = cur.channels.find((c) => c.channel === channel);
  if (!row || row.denied) return;
  const next = cur.channels.map((c) => (c.channel === channel ? { ...c, enabled: !c.enabled } : c));
  if (!next.some((c) => c.enabled)) {
    uni.showToast({ title: t("store.fulfillNone"), icon: "none" });
    return;
  }
  if (row.enabled) {
    // 关路前确认：只勾了这一路的在售商品，关掉后买家下不了单。商品不自动改 —— 动在售商品要商家自己点头
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: t("store.offConfirmTitle", { s: t(`channel.${channel}`) }),
        content: t("store.offConfirmBody"),
        cancelText: t("store.offKeep"),
        confirmText: t("store.offAnyway"),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }
  await persistChannels(next, channel);
}

// 自送费率：行内展开编辑，读写既有 deliveryRule 接口（单位分；输入按元）
const rule = ref<DeliveryRule | null>(null);
const ruleOpen = ref(false);
const ruleForm = ref({ minOrder: "", fee: "", free: "" });
const yuan = (minor: number) => (minor / 100).toString();
const toMinor = (s: string) => Math.round(Number(s || 0) * 100);

async function loadRule() {
  rule.value = await api.mDeliveryRule().catch(() => null);
}
function openRule() {
  const r = rule.value;
  ruleForm.value = {
    minOrder: r ? yuan(r.minOrderMinor) : "0",
    fee: r ? yuan(r.feeMinor) : "0",
    free: r ? yuan(r.freeThresholdMinor) : "0",
  };
  ruleOpen.value = true;
}
async function saveRule() {
  const next: DeliveryRule = {
    radius: rule.value?.radius ?? 3000,
    minOrderMinor: toMinor(ruleForm.value.minOrder),
    feeMinor: toMinor(ruleForm.value.fee),
    freeThresholdMinor: toMinor(ruleForm.value.free),
  };
  if ([next.minOrderMinor, next.feeMinor, next.freeThresholdMinor].some((n) => !Number.isFinite(n) || n < 0)) {
    uni.showToast({ title: t("store.rateInvalid"), icon: "none" });
    return;
  }
  // 免配门槛低于起送价是无意义配置，服务端也拒；端上先说人话
  if (next.freeThresholdMinor && next.freeThresholdMinor < next.minOrderMinor) {
    uni.showToast({ title: t("store.rateHint"), icon: "none" });
    return;
  }
  try {
    rule.value = await api.mSaveDeliveryRule(next);
    ruleOpen.value = false;
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
const deliverySummary = computed(() => {
  const r = rule.value;
  if (!r) return "";
  const a = money(r.minOrderMinor);
  const b = money(r.feeMinor);
  // 没设免配门槛就别硬套「满 X 免配」的句式 —— 会拼成「满 不免 免配」
  return r.freeThresholdMinor
    ? t("store.sumDelivery", { a, b, c: money(r.freeThresholdMinor) })
    : t("store.sumDeliveryNoFree", { a, b });
});

// ---------------------------------------------------------------- 加载 / 保存
async function load() {
  const [s, ap] = await Promise.allSettled([api.mStore(), api.mMyCommunityApplies()]);
  if (s.status === "fulfilled") {
    form.value = normalize(s.value);
    snapshot.value = JSON.stringify(form.value.serviceAreas ?? []);
    loaded.value = true;
  } else {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
  applies.value = ap.status === "fulfilled" ? ap.value : [];
}

function normalize(p: StoreProfile): StoreProfile {
  return { ...p, serviceAreas: p.serviceAreas ?? [] };
}

async function save() {
  if (emptyIsBlocking.value) {
    uni.showToast({ title: t("store.areaNeeded"), icon: "none" });
    return;
  }
  /*
   * 只回传这一页管的字段。旧三档 serviceScope **不能原样回传**：存量主体里还有
   * PLATFORM 这种已不在开放白名单里的值，回传就被 assertServiceScopeAllowed 拒
   * （「当前不支持这个经营范围」）——而人根本没碰过它。空 = 服务端不改。
   * fulfillmentReach 是服务端「范围为空是否合法」的判据，按开关推导一致的值。
   */
  const reach = expressOn.value ? "SHIPPING" : deliveryOn.value ? "ONSITE" : "PICKUP";
  const payload = {
    ...form.value,
    serviceScope: "",
    serviceCommunityNos: [],
    serviceCityCode: undefined,
    fulfillmentReach: reach,
  } as unknown as StoreProfile;
  try {
    form.value = normalize(await api.mSaveStore(payload));
    snapshot.value = JSON.stringify(form.value.serviceAreas ?? []);
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function discard() {
  form.value.serviceAreas = JSON.parse(snapshot.value || "[]");
}

/** 返回时拦未保存：丢改动要他自己点头 */
onBackPress(() => {
  if (!dirty.value) return false;
  uni.showModal({
    title: t("store.leaveTitle"),
    content: t("store.leaveBody"),
    confirmText: t("store.discard"),
    success: (r) => {
      if (r.confirm) {
        discard();
        uni.navigateBack();
      }
    },
  });
  return true;
});

function goAddress() {
  uni.navigateTo({ url: ROUTES.store });
}

onShow(() => {
  void merchant.ensureStores().then(() => {
    void loadFulfillment();
  });
  void load();
  void loadRule();
});
</script>

<template>
  <sh-scaffold title-key="store.scopeTitle" :denied="!merchant.can('biz:store')">
    <biz-store-tag></biz-store-tag>

    <!-- ① 经营范围（主体级） -->
    <view class="sh-card">
      <view class="head">
        <text class="sh-h2">{{ $t("store.scope") }}</text>
        <text class="head__sub">{{ $t("store.scopeAll") }}</text>
      </view>
      <text class="hint">{{ $t("store.scopeLead") }}</text>

      <view v-if="areas.length" class="list">
        <view v-for="a in areas" :key="`${a.level}:${a.refCode}`" class="item">
          <view class="item__main">
            <text class="item__name" :class="{ 'is-pending': a.status === 'PENDING' }">
              {{ splitName(a).main }}<text v-if="isWhole(a)" class="item__whole"> {{ $t("store.whole") }}</text>
            </text>
            <text v-if="splitName(a).path" class="item__path">{{ splitName(a).path }}</text>
          </view>
          <text v-if="a.status === 'PENDING'" class="sh-chip sh-chip--warning">{{ $t("store.areaPending") }}</text>
          <text class="item__x" @tap="removeArea(a)">×</text>
        </view>
      </view>

      <!-- 空列表的含义两分：只自提是故障，开了自送/快递是「不限」。绝不能显示同一句话 -->
      <text v-if="emptyIsBlocking" class="warn">{{ $t("store.areaNeeded") }}</text>
      <text v-else-if="!areas.length" class="hint">{{ $t("store.areaUnlimited") }}</text>
      <text v-if="areas.length > activeAreas.length" class="hint">{{ $t("store.areaPendingHint") }}</text>

      <view class="sh-btn sh-btn--soft add" @tap="pickerOpen = true">
        {{ $t("store.addArea") }}
      </view>

      <view v-if="pendingApplies.length || rejectedApplies.length" class="progress">
        <text v-if="pendingApplies.length" class="hint">
          {{ $t("store.applyProgress", { n: pendingApplies.length }) }} · {{ pendingApplies.map((a) => a.name).join("、") }}
        </text>
        <text v-for="a in rejectedApplies" :key="a.applyNo" class="warn">
          {{ a.name }} · {{ $t("store.applyRejected") }}{{ a.reason ? `：${a.reason}` : "" }}
        </text>
      </view>
    </view>

    <!-- ② 送货方式（门店级，即点即存） -->
    <view v-if="fulfillment" class="sh-card mt">
      <view class="head">
        <text class="sh-h2">{{ $t("store.fulfillCard") }}</text>
        <text class="head__sub">{{ $t("store.fulfillSub") }}</text>
      </view>

      <template v-for="c in channelRows" :key="c.channel">
        <view class="ch" :class="{ 'is-off': c.denied }" @tap="toggleChannel(c.channel)">
          <view class="ch__main">
            <text class="ch__name">{{ $t(`channel.${c.channel}`) }}</text>
            <text class="ch__desc">{{ c.denied ? $t("store.channelDenied") : $t(`store.channelDesc.${c.channel}`) }}</text>
          </view>
          <view class="switch" :class="{ 'is-on': c.enabled, 'is-busy': savingChannel === c.channel }">
            <view class="switch__knob"></view>
          </view>
        </view>

        <!-- 开着的路：一行配置摘要 -->
        <view v-if="c.enabled && c.channel === 'STORE_PICKUP'" class="sum" :class="{ 'sum--warn': !form.address }">
          <text class="sum__t">{{ form.address ? $t("store.sumPickupAddr", { s: form.address }) : $t("store.sumNoAddress") }}</text>
          <text class="sum__go" @tap.stop="goAddress">{{ $t("store.goAddress") }}</text>
        </view>
        <view v-if="c.enabled && c.channel === 'MERCHANT_DELIVERY'" class="sum">
          <template v-if="!ruleOpen">
            <text class="sum__t">{{ deliverySummary || $t("store.sumDeliveryUnset") }}</text>
            <text class="sum__go" @tap.stop="openRule">{{ $t("store.edit") }}</text>
          </template>
          <view v-else class="rate" @tap.stop>
            <view class="rate__grid">
              <view class="rate__f">
                <text class="field__label">{{ $t("store.minOrder") }}</text>
                <input v-model="ruleForm.minOrder" class="field__input" type="digit" />
              </view>
              <view class="rate__f">
                <text class="field__label">{{ $t("store.fee") }}</text>
                <input v-model="ruleForm.fee" class="field__input" type="digit" />
              </view>
              <view class="rate__f">
                <text class="field__label">{{ $t("store.freeThreshold") }}</text>
                <input v-model="ruleForm.free" class="field__input" type="digit" />
              </view>
            </view>
            <text class="hint">{{ $t("store.rateHint") }}</text>
            <view class="rate__btns">
              <text class="sh-btn sh-btn--soft rate__save" @tap="saveRule">{{ $t("store.saveRate") }}</text>
              <text class="mini" @tap="ruleOpen = false">{{ $t("store.collapse") }}</text>
            </view>
          </view>
        </view>
        <view v-if="c.enabled && c.channel === 'EXPRESS'" class="sum">
          <text class="sum__t">{{ $t("store.sumExpress") }}</text>
        </view>
      </template>
    </view>

    <biz-region-picker
      v-model:visible="pickerOpen"
      :areas="areas"
      @update:areas="setAreas"
      @applied="onApplied"
    ></biz-region-picker>

    <!-- 吸底保存条：范围有未保存改动时才浮现 -->
    <view v-if="dirty" class="savebar">
      <text class="savebar__t">{{ $t("store.unsaved") }}</text>
      <text class="mini" @tap="discard">{{ $t("store.discard") }}</text>
      <view class="sh-btn savebar__save" @tap="save">{{ $t("common.save") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 16rpx;
}
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
}
.head__sub {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.warn {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.list {
  margin-top: 12rpx;
}
.item {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 2rpx solid var(--sh-line);
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.item__name.is-pending {
  color: var(--sh-sub);
}
.item__whole {
  font-size: 24rpx;
  font-weight: 400;
  color: var(--sh-sub);
}
.item__path {
  display: block;
  margin-top: 2rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.item__x {
  flex-shrink: 0;
  padding: 0 12rpx;
  font-size: 30rpx;
  color: var(--sh-sub);
}
.add {
  margin-top: 20rpx;
}
.progress {
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: 2rpx solid var(--sh-line);
}
/* 送货方式：紧凑开关行 */
.ch {
  display: flex;
  align-items: center;
  gap: 24rpx;
  padding: 22rpx 0;
  border-bottom: 2rpx solid var(--sh-line);
}
.ch.is-off {
  opacity: 0.55;
}
.ch__main {
  flex: 1;
  min-width: 0;
}
.ch__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.ch__desc {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.switch {
  flex-shrink: 0;
  position: relative;
  width: 88rpx;
  height: 48rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  border: 2rpx solid var(--sh-line);
  box-sizing: border-box;
  transition: background 0.15s;
}
.switch__knob {
  position: absolute;
  top: 4rpx;
  left: 4rpx;
  width: 36rpx;
  height: 36rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  box-shadow: 0 2rpx 4rpx var(--sh-scrim);
  transition: left 0.15s;
}
.switch.is-on {
  background: var(--sh-primary);
  border-color: var(--sh-primary);
}
.switch.is-on .switch__knob {
  left: 46rpx;
}
.switch.is-busy {
  opacity: 0.6;
}
.sum {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  margin: 12rpx 0 4rpx;
  padding: 14rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.sum--warn {
  background: var(--sh-warning-tint);
}
.sum__t {
  flex: 1;
  min-width: 0;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.sum--warn .sum__t {
  color: var(--sh-ink);
}
.sum__go {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.rate {
  flex: 1;
}
.rate__grid {
  display: flex;
  gap: 12rpx;
}
.rate__f {
  flex: 1;
  min-width: 0;
}
.rate__btns {
  display: flex;
  gap: 16rpx;
  margin-top: 12rpx;
}
.rate__save {
  flex: 1;
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
/* 吸底保存条：transform 框内 fixed 会跟着窄栏收窄（见 sh-scaffold） */
.savebar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  padding-bottom: calc(20rpx + env(safe-area-inset-bottom));
  background: var(--sh-surface);
  border-top: 2rpx solid var(--sh-line);
}
.savebar__t {
  flex: 1;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.savebar__save {
  padding: 20rpx 48rpx;
}
</style>
