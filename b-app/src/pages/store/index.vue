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
import { composeAddress, locateWithFeedback, pickOnMap } from "@/utils/geo";
import { saveBase64Image } from "@/utils/image";
import { useMerchantStore } from "@/stores/merchant";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import type { Poster, ShareKit, StoreProfile, StoreQrcode } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

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
/** 只看这一页管的字段：公告在自己的页里改，它变了不该让这里显示「有修改未保存」 */
const pick = (p: StoreProfile) => JSON.stringify([
  p.openHours, p.address, p.addressDetail ?? "", p.latE6 ?? null, p.lngE6 ?? null,
]);
const dirty = computed(() => loaded.value && pick(form.value) !== snapshot.value);

/** 营业时间快捷模板：早市摊位与全天店是两种最常见的作息，点一下填上再改 */
const HOURS = [
  { key: "hoursMorning", value: "05:30–12:00" },
  { key: "hoursAllDay", value: "08:00–22:00" },
];

const qrcode = ref<StoreQrcode | null>(null);
const kit = ref<ShareKit | null>(null);
/** 真海报（P2）：封面/店名/价格/小程序码合成的一张图，不是 kit.posterUrl 那句假话 */
const poster = ref<Poster | null>(null);

async function load() {
  /*
   * allSettled 而不是 all：店铺码还没生成、分享素材抖一下，不该让门面字段
   * 静默退回初始值 —— 店主照着空白点保存，就把默认值覆盖到真实数据上去了。
   */
  const [s, q, k, p] = await Promise.allSettled(
    [api.mStore(), api.mStoreQrcode(), api.mShareKit(), api.mPoster()],
  );
  if (s.status === "fulfilled") {
    form.value = { ...s.value, serviceAreas: s.value.serviceAreas ?? [] };
    snapshot.value = pick(form.value);
    loaded.value = true;
  } else {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
  qrcode.value = q.status === "fulfilled" ? q.value : null;
  kit.value = k.status === "fulfilled" ? k.value : null;
  poster.value = p.status === "fulfilled" ? p.value : null;
}

async function save() {
  // 地址是买家取货页上要印的，太短的（「南门」）等于没写
  if (form.value.address && form.value.address.trim().length < 4) {
    uni.showToast({ title: t("store.addressTooShort"), icon: "none" });
    return;
  }
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
  const [openHours = "", address = "", addressDetail = "", latE6 = null, lngE6 = null] =
    JSON.parse(snapshot.value || "[]") as [string, string, string, number | null, number | null];
  form.value = { ...form.value, openHours, address, addressDetail, latE6, lngE6 };
}

/** 已标过点（坐标随门店保存；买家侧导航/排距离靠它） */
const pinned = computed(() => form.value.latE6 != null && form.value.lngE6 != null);

/**
 * 地图选点取地址：App/小程序走原生选点页（搜索 + 拖图钉），一次拿到门牌地址和坐标。
 *
 * 之前是「定位一次 → 逆地理」：店主没法纠偏，定位偏几十米门店点就偏几十米，
 * 而且坐标根本没存 —— 买家端导航到的是一串文字。
 * 不支持选点的端（H5）退回旧路：定位一次 + 后端逆地理；后端没配 key 返回 10503 就藏按钮。
 */
const geoAvailable = ref(true);
const locating = ref(false);
async function locateAddress() {
  if (locating.value) return;
  locating.value = true;
  try {
    const cur = pinned.value ? { lat: form.value.latE6! / 1e6, lng: form.value.lngE6! / 1e6 } : null;
    const p = await pickOnMap(t, cur);
    if (!p) return;
    form.value.latE6 = Math.round(p.lat * 1e6);
    form.value.lngE6 = Math.round(p.lng * 1e6);
    const composed = composeAddress(p);
    if (composed) {
      form.value.address = composed.slice(0, 100);
      return;
    }
    // 退回路：只有坐标，地址让后端逆地理给
    const r = await api.mGeoReverse(p.lat, p.lng);
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
// 保留给「只定位不选点」的场景引用，避免 tree-shake 后 util 里那条分支没人测
void locateWithFeedback;

onBackPress(() => {
  if (!dirty.value) return false;
  /*
   * **不能 await**：`onBackPress` 要**同步**返回布尔来决定拦不拦这一次返回，
   * 改成 async 的话返回的是 Promise —— 恒真，于是永远拦住，退不出去。
   * 所以这里问完再自己 navigateBack，本次返回先拦下。
   */
  void confirm({
    title: String(t("store.leaveTitle")),
    hint: String(t("store.leaveBody")),
    confirmText: String(t("store.discard")),
  }).then((ok) => {
    if (!ok) return;
    discard();
    uni.navigateBack();
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

/**
 * 把店铺码存到相册。**这才是本地生活场景里真正会用的分享方式**——
 * 「复制链接」要接收方点开，「存图发群/发朋友圈」直接扫，前者在这个场景里几乎没人用。
 *
 * App/小程序走 `saveImageToPhotosAlbum`（先落一份临时文件，这两个平台都不接受直接传 base64）；
 * H5 存不了相册，退回「新开一个图片页」，交给用户自己长按保存 —— 不是最好的体验，
 * 但比一个假装能用的按钮强（这正是这张卡片原来那个「可打印版」按钮的问题：点了没反应）。
 */
function saveQrImage() {
  saveBase64Image(qrcode.value?.imageBase64, "store-qrcode", t);
}
function savePosterImage() {
  saveBase64Image(poster.value?.imageBase64, "store-poster", t);
}

onShow(() => {
  void load();
});
</script>

<template>
  <sh-scaffold title-key="store.title" :denied="!merchant.can('biz:store')">
    <biz-store-tag readonly></biz-store-tag>

    <!-- 门面：只有三个字段。公告在自己的页（pages/store-notice）里，即改即发 -->
    <view class="sh-card">
      <text class="txt-title">{{ $t("store.decorate") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("store.openHours") }}</text>
        <biz-time-range v-model="form.openHours"></biz-time-range>
        <view class="quick">
          <text v-for="h in HOURS" :key="h.key" class="txt-caption mini" @tap="form.openHours = h.value">
            {{ $t(`store.${h.key}`) }}
          </text>
        </view>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("store.address") }}</text>
        <view class="addr">
          <input v-model="form.address" class="field__input sh-fill" :maxlength="100" :placeholder="$t('store.addressPh')" />
          <view v-if="geoAvailable" class="addr__locate" @tap="locateAddress">
            <sh-icon name="pin" :size="18" color="var(--sh-primary-text)"></sh-icon>
            <text class="txt-caption addr__t">{{ locating ? "…" : pinned ? $t("store.repinAddr") : $t("store.pickAddr") }}</text>
          </view>
        </view>
        <!--
          门牌号单独一格。地图选点只能给到小区门口，而买家照着找门缺的正是这一截；
          放在同一个输入框里的话，商家补完再点一次选点就被整条覆盖 —— 补的那截无声消失。
        -->
        <input
          v-model="form.addressDetail"
          class="field__input addr__detail"
          :maxlength="40"
          :placeholder="$t('store.addressDetailPh')"
        />
        <text class="sh-hint">{{ pinned ? $t("store.addressPinned") : $t("store.addressHint") }}</text>
      </view>
    </view>


    <!-- 获客工具：店铺码 + 分享文案合一卡。一期主获客路径的商家侧（ADR-004 决策 3） -->
    <view class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("store.tools") }}</text>

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
        <view class="sh-fill">
          <text class="txt-strong qr__t">{{ $t("store.qrcode") }}</text>
          <text class="sh-hint">
            {{ qrcode?.imageBase64 ? (qrcode.printableHint || $t("store.qrcodeDesc")) : $t("store.qrcodePending") }}
          </text>
          <text v-if="qrcode?.storeCode" class="txt-sub qr__code sh-num">{{ qrcode.storeCode }}</text>
          <view class="btns">
            <text v-if="qrcode?.imageBase64" class="txt-caption mini" @tap="saveQrImage">{{ $t("store.saveImage") }}</text>
            <text v-if="qrcode?.url" class="txt-caption mini" @tap="copyLink">{{ $t("store.copyLink") }}</text>
          </view>
        </view>
      </view>
      <text class="sh-hint">{{ $t("store.qrcodeHint") }}</text>

      <view class="kitwrap">
        <text class="txt-strong qr__t">{{ $t("store.shareKit") }}</text>
        <view class="txt-sub kit">{{ kit?.text }}</view>
        <view class="sh-btn" @tap="copyText">{{ $t("store.copyKit") }}</view>
        <text class="sh-hint">{{ $t("store.shareKitHint") }}</text>

        <!--
          真海报：封面/店名/价格/小程序码合成的一张图，不是上面那句话再配一个假 URL。
          没生成出来（商家异常/极端情况）就不占地方——不摆一张加载不出来的坏图。
        -->
        <view v-if="poster?.imageBase64" class="poster">
          <image class="poster__img" :src="`data:image/png;base64,${poster.imageBase64}`" mode="widthFix" />
          <view class="sh-btn poster__save" @tap="savePosterImage">{{ $t("store.saveImage") }}</view>
        </view>
      </view>
    </view>

    <sh-savebar
      :visible="dirty"
      :text="String($t('store.unsaved'))"
      :discard-text="String($t('store.discard'))"
      :save-text="String($t('common.save'))"
      @discard="discard"
      @save="save"
    ></sh-savebar>
  </sh-scaffold>
</template>

<style scoped>
.field + .field {
  margin-top: 20rpx;
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

.addr__locate {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8rpx;
  height: 88rpx;
  padding: 0 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
}
.addr__t {
  color: var(--sh-primary-text);
}
.mini {
  padding: 12rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
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

.qr__t {
  display: block;
}
.qr__code {
  display: block;
  margin-top: 8rpx;
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
  border-top: var(--sh-hairline);
}
.poster {
  margin-top: 20rpx;
}
.poster__img {
  width: 100%;
  border-radius: 24rpx;
  border: var(--sh-hairline);
}
.poster__save {
  margin-top: 16rpx;
}
.kit {
  margin: 12rpx 0 16rpx;
  padding: 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  color: var(--sh-ink);
}

/* 门牌号：接在地址下面，视觉上属于同一格 */
.addr__detail {
  margin-top: 12rpx;
}

/* 卡头：标题 + 右侧一句副标题。这一页此前没有这个块，两段文字会黏成一行 */
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.head__sub {
  flex-shrink: 0;
}

</style>
