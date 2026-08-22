<script setup lang="ts">
/**
 * 页 B「店铺与获客」（方案 v3）：日常内容 —— 装修三项 + 获客工具。
 *
 * 经营范围与送货方式拆去了页 A（pages/store-scope）：那是开店的两个决策，配一次少动；
 * 这一页是会反复改的东西（今天到了什么货、几点开门）。两者混在一页时保存语义也打架：
 * 送货即点即存、范围要确认、装修随手改。
 *
 * 设计约束不变：**极简，店主是在手机上弄的**。一个公告 + 营业时间 + 地址就够了。
 */
import { computed, ref } from "vue";
import { onBackPress, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { getLocation } from "@shared/ports/location";
import { useMerchantStore } from "@/stores/merchant";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import type { ShareKit, StoreProfile, StoreQrcode } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

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
const snapshot = ref("");
const pick = (p: StoreProfile) => JSON.stringify([p.announcement, p.openHours, p.address]);
const dirty = computed(() => loaded.value && pick(form.value) !== snapshot.value);

/** 营业时间快捷模板：早市摊位与全天店是两种最常见的作息，点一下填上再改 */
const HOURS = [
  { key: "hoursMorning", value: "05:30–12:00" },
  { key: "hoursAllDay", value: "08:00–22:00" },
];

const qrcode = ref<StoreQrcode | null>(null);
const kit = ref<ShareKit | null>(null);

async function load() {
  /*
   * allSettled 而不是 all：店铺码还没生成、分享素材抖一下，不该让门面字段
   * 静默退回初始值 —— 店主照着空白点保存，就把默认值覆盖到真实数据上去了。
   */
  const [s, q, k] = await Promise.allSettled([api.mStore(), api.mStoreQrcode(), api.mShareKit()]);
  if (s.status === "fulfilled") {
    form.value = { ...s.value, serviceAreas: s.value.serviceAreas ?? [] };
    snapshot.value = pick(form.value);
    loaded.value = true;
  } else {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
  qrcode.value = q.status === "fulfilled" ? q.value : null;
  kit.value = k.status === "fulfilled" ? k.value : null;
}

async function save() {
  // 这一页不管范围：serviceAreas 不传 = 不改；旧三档留空 = 不改（存量 PLATFORM 回传会被拒）
  const payload = {
    ...form.value,
    serviceScope: "",
    serviceAreas: undefined,
  } as unknown as StoreProfile;
  try {
    const saved = await api.mSaveStore(payload);
    form.value = { ...saved, serviceAreas: saved.serviceAreas ?? [] };
    snapshot.value = pick(form.value);
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function discard() {
  const [announcement = "", openHours = "", address = ""] = JSON.parse(snapshot.value || "[]") as string[];
  form.value = { ...form.value, announcement, openHours, address };
}

/**
 * 定位取地址（P2）：逆地理编码走后端代理。后端没配地图密钥时返回 10501，
 * 端上据此**藏掉按钮** —— 一个点了只会报错的按钮比没有更糟。
 */
const geoAvailable = ref(true);
const locating = ref(false);
async function locateAddress() {
  if (locating.value) return;
  locating.value = true;
  try {
    const loc = await getLocation();
    if (!loc) {
      uni.showToast({ title: t("store.pickup.locateFailed"), icon: "none" });
      return;
    }
    const r = await api.mGeoReverse(loc.lat, loc.lng);
    if (r.recommend) form.value.address = r.recommend;
  } catch (e) {
    if ((e as { code?: number }).code === 10503) {
      geoAvailable.value = false;
      return;
    }
    uni.showToast({ title: t("store.locateAddrFailed"), icon: "none" });
  } finally {
    locating.value = false;
  }
}

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

function copyText() {
  if (!kit.value) return;
  uni.setClipboardData({
    data: kit.value.text,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

function copyLink() {
  const url = qrcode.value?.url;
  if (!url) return;
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="store.title" :denied="!merchant.can('biz:store')">
    <biz-store-tag></biz-store-tag>

    <!-- 装修：只有三个字段 -->
    <view class="sh-card">
      <text class="sh-h2">{{ $t("store.decorate") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("store.announcement") }}</text>
        <textarea
          v-model="form.announcement"
          class="field__area"
          :placeholder="$t('store.announcementPh')"
          maxlength="60"
        />
        <text class="hint">{{ $t("store.announcementHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("store.openHours") }}</text>
        <input v-model="form.openHours" class="field__input" placeholder="06:30–21:00" />
        <view class="quick">
          <text v-for="h in HOURS" :key="h.key" class="mini" @tap="form.openHours = h.value">
            {{ $t(`store.${h.key}`) }}
          </text>
        </view>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("store.address") }}</text>
        <view class="addr">
          <input v-model="form.address" class="field__input addr__input" placeholder="阳光里小区南门" />
          <view v-if="geoAvailable" class="addr__locate" @tap="locateAddress">
            <sh-icon name="pin" :size="18" color="var(--sh-primary-text)"></sh-icon>
            <text class="addr__t">{{ locating ? "…" : $t("store.locateAddr") }}</text>
          </view>
        </view>
        <text class="hint">{{ $t("store.addressHint") }}</text>
      </view>
    </view>

    <!-- 获客工具：店铺码 + 分享文案合一卡。一期主获客路径的商家侧（ADR-004 决策 3） -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("store.tools") }}</text>

      <view class="qr">
        <view class="qr__box">
          <image
            v-if="qrcode?.imageBase64"
            class="qr__img"
            :src="`data:image/png;base64,${qrcode.imageBase64}`"
            mode="widthFix"
          />
          <!-- **不画一张假码**：占位图会被印到包装袋上，而它扫不出任何东西 -->
          <text v-else class="qr__ph">▦</text>
        </view>
        <view class="qr__main">
          <text class="qr__t">{{ $t("store.qrcode") }}</text>
          <text class="hint">{{ qrcode?.imageBase64 ? $t("store.qrcodeDesc") : $t("store.qrcodePending") }}</text>
          <text v-if="qrcode?.storeCode" class="qr__code sh-num">{{ qrcode.storeCode }}</text>
          <view class="btns">
            <text v-if="qrcode?.url" class="mini" @tap="copyLink">{{ $t("store.copyLink") }}</text>
            <text class="mini">{{ $t("store.printVersion") }}</text>
          </view>
        </view>
      </view>
      <text class="hint">{{ $t("store.qrcodeHint") }}</text>

      <view class="kitwrap">
        <text class="qr__t">{{ $t("store.shareKit") }}</text>
        <view class="kit">{{ kit?.text }}</view>
        <view class="sh-btn" @tap="copyText">{{ $t("store.copyKit") }}</view>
        <text class="hint">{{ $t("store.shareKitHint") }}</text>
      </view>
    </view>

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
.field + .field {
  margin-top: 20rpx;
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.quick {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}
.addr {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.addr__input {
  flex: 1;
  min-width: 0;
}
.addr__locate {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 6rpx;
  height: 88rpx;
  padding: 0 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
}
.addr__t {
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.mini {
  padding: 12rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.qr {
  display: flex;
  gap: 24rpx;
  margin-top: 20rpx;
}
.qr__box {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 192rpx;
  height: 192rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  overflow: hidden;
}
.qr__img {
  width: 192rpx;
}
.qr__ph {
  font-size: 48rpx;
  line-height: 1;
  color: var(--sh-sub);
}
.qr__main {
  flex: 1;
  min-width: 0;
}
.qr__t {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.qr__code {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  letter-spacing: 4rpx;
  color: var(--sh-ink);
}
.btns {
  display: flex;
  gap: 12rpx;
  margin-top: 12rpx;
}
.kitwrap {
  margin-top: 24rpx;
  padding-top: 24rpx;
  border-top: 2rpx solid var(--sh-line);
}
.kit {
  margin: 12rpx 0 16rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  line-height: 1.7;
  color: var(--sh-ink);
}
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
