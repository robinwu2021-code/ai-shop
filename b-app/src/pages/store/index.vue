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
import { pickImages } from "@shared/ports/media";
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
  p.openHours, p.address, p.addressDetail ?? "", p.latE6 ?? null, p.lngE6 ?? null, p.bannerUrl ?? "",
]);
const dirty = computed(() => loaded.value && pick(form.value) !== snapshot.value);

/** 营业时间快捷模板：早市摊位与全天店是两种最常见的作息，点一下填上再改 */
const HOURS = [
  { key: "hoursMorning", value: "05:30–12:00" },
  { key: "hoursAllDay", value: "08:00–22:00" },
];

const qrcode = ref<StoreQrcode | null>(null);

/**
 * 这个码是**哪家店**的（V298 一店一码）。
 *
 * 后端把 storeNo 一起下发了，此前端上没接 —— 店主看到的只是一串码。
 * 多门店时这是**印之前唯一能发现「贴错店」的机会**：贴错了没有任何症状，
 * 码扫得通、页面打得开，只是客流全算到了另一家头上。
 *
 * 名字优先从已加载的门店列表里取；取不到就退回门店号，**不显示成空白** ——
 * 空白会被读成「这个码不属于任何店」。
 */
const qrStoreName = computed(() => {
  const no = qrcode.value?.storeNo;
  if (!no) return "";
  return merchant.stores.find((x) => x.storeNo === no)?.name || no;
});
const kit = ref<ShareKit | null>(null);
/** 真海报（P2）：封面/店名/价格/小程序码合成的一张图，不是 kit.posterUrl 那句假话 */
const poster = ref<Poster | null>(null);

/** 这次没取到。**与「确定为空」是两件事** —— 整页内容都挂在拉来的数据后面 */
const failed = ref(false);

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
    failed.value = false;
  } else {
    // `dirty` 里的 `loaded` 已经挡住了保存条，但门面字段是空的 ——
    // 商家看到的是一张空的店铺资料表，而不是「没取到」
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
    failed.value = true;
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
  const [openHours = "", address = "", addressDetail = "", latE6 = null, lngE6 = null, bannerUrl = ""] =
    JSON.parse(snapshot.value || "[]") as [string, string, string, number | null, number | null, string];
  form.value = { ...form.value, openHours, address, addressDetail, latE6, lngE6, bannerUrl };
}

/** 背景图：选一张 → 传上去 → 填进表单。保存仍走底部那一条（与营业时间、地址一起存） */
const bannerUploading = ref(false);
async function pickBanner() {
  if (bannerUploading.value) return;
  let picked;
  try {
    picked = await pickImages(1, ["album", "camera"]);
  } catch {
    return; // 取消不是错误
  }
  const img = picked[0];
  if (!img) return;
  bannerUploading.value = true;
  try {
    const { url } = await api.mUploadImage(img.tempPath);
    form.value = { ...form.value, bannerUrl: url };
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    bannerUploading.value = false;
  }
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
  <sh-scaffold title-key="store.title" :denied="!merchant.can('biz:store')"
    :failed="failed"
    @retry="load"
  >
    <biz-store-tag readonly></biz-store-tag>

    <!--
      与发货设置、营销同一套骨架：卡头（标题 + 右侧动作）→ 最多一句说明 → 内容。
      动作一律是卡头右侧的药丸，不再是卡中间的整宽红按钮 —— 此前一页上三个一样的大红条，
      分不出哪个是这一页的主动作（主动作只有一个：底部的「保存」）。
    -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.decorate") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("store.openHours") }}</text>
        <biz-time-range v-model="form.openHours"></biz-time-range>
        <view class="sh-wrap quick">
          <text v-for="h in HOURS" :key="h.key" class="sh-chip" @tap="form.openHours = h.value">
            {{ $t(`store.${h.key}`) }}
          </text>
        </view>
      </view>

      <view class="field">
        <view class="sh-row sh-row--between">
          <text class="field__label">{{ $t("store.address") }}</text>
          <view v-if="geoAvailable" class="sh-chip sh-chip--primary sh-chip--icon" @tap="locateAddress">
            <sh-icon name="pin" :size="22" color="var(--sh-primary-text)"></sh-icon>
            {{ locating ? "…" : pinned ? $t("store.repinAddr") : $t("store.pickAddr") }}
          </view>
        </view>
        <input v-model="form.address" class="field__input" :maxlength="100" :placeholder="$t('store.addressPh')" />
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
      </view>

      <!--
        顾客打开店铺时顶部那一条：传了是这张照片，没传是主色浅底（2026-09-29 用户定）。
        宽幅格子，看到的比例接近顾客那边；点图换一张，右上角删掉就回到浅底
      -->
      <view class="field">
        <text class="field__label">{{ $t("store.banner") }}</text>
        <sh-uploader
          :list="form.bannerUrl ? [form.bannerUrl] : []"
          :max="1"
          :width="400"
          :height="200"
          :uploading="bannerUploading"
          removable
          @add="pickBanner"
          @tap-item="pickBanner"
          @remove="form.bannerUrl = ''"
        ></sh-uploader>
      </view>
    </view>

    <!-- 获客工具：店铺码 / 文案 / 海报 各一张卡。一期主获客路径的商家侧（ADR-004 决策 3） -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.qrcode") }}</text>
        <view class="sh-row acts">
          <text v-if="qrcode?.url" class="sh-chip sh-chip--primary" @tap="copyLink">{{ $t("store.copyLink") }}</text>
          <text v-if="qrcode?.imageBase64" class="sh-chip sh-chip--primary" @tap="saveQrImage">{{ $t("store.saveImage") }}</text>
        </view>
      </view>
      <view class="qr sh-row">
        <view class="qr__box sh-center">
          <image
            v-if="qrcode?.imageBase64"
            class="qr__img"
            :src="`data:image/png;base64,${qrcode.imageBase64}`"
            mode="widthFix"
          />
          <!-- **不画一张假码**：占位图会被印到包装袋上，而它扫不出任何东西 -->
          <sh-icon v-else name="scan" :size="56" color="var(--sh-sub)"></sh-icon>
        </view>
        <view class="sh-fill">
          <!-- 码属于哪家店，摆在码值上面：印之前先看见它 -->
          <text v-if="qrStoreName" class="txt-body blk">{{ $t("store.qrcodeOfStore", { name: qrStoreName }) }}</text>
          <text v-if="qrcode?.storeCode" class="txt-caption sh-muted sh-num blk qr__code">{{ qrcode.storeCode }}</text>
          <text v-if="!qrcode?.imageBase64" class="txt-caption sh-muted blk qr__code">{{ $t("store.qrcodePending") }}</text>
        </view>
      </view>
      <!-- 多门店才提示：单店商家看到「每家店的码不一样」只会困惑 -->
      <text v-if="merchant.multiStore" class="sh-hint is-warning">{{ $t("store.qrcodeStoreWarn") }}</text>
    </view>

    <view v-if="kit?.text" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.shareKit") }}</text>
        <text class="sh-chip sh-chip--primary" @tap="copyText">{{ $t("store.copyText") }}</text>
      </view>
      <view class="txt-body kit">{{ kit.text }}</view>
    </view>

    <!--
      真海报：封面/店名/价格/小程序码合成的一张图，不是上面那句话再配一个假 URL。
      没生成出来（商家异常/极端情况）就不占地方——不摆一张加载不出来的坏图。
    -->
    <view v-if="poster?.imageBase64" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.poster") }}</text>
        <text class="sh-chip sh-chip--primary" @tap="savePosterImage">{{ $t("store.saveImage") }}</text>
      </view>
      <image class="poster__img" :src="`data:image/png;base64,${poster.imageBase64}`" mode="widthFix" />
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
.blk {
  display: block;
}
.field + .field {
  margin-top: 24rpx;
}
.quick {
  margin-top: 12rpx;
}
/* 门牌号：接在地址下面，视觉上属于同一格 */
.addr__detail {
  margin-top: 12rpx;
}
.acts {
  gap: 12rpx;
}
.qr {
  gap: 24rpx;
}
.qr__box {
  flex-shrink: 0;
  width: 160rpx;
  height: 160rpx;
  border-radius: 24rpx;
  background: var(--sh-bg);
  overflow: hidden;
}
.qr__img {
  width: 160rpx;
}
.qr__code {
  margin-top: 8rpx;
}
.kit {
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-bg);
}
/* 海报是预览：缩到六成居中，不占满一整屏（要原图点「保存图片」） */
.poster__img {
  display: block;
  width: 60%;
  margin: 0 auto;
  border-radius: 24rpx;
  border: var(--sh-hairline);
}
</style>
