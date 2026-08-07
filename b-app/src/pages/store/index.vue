<script setup lang="ts">
// 店铺装修（B-11.2.5）+ 店铺码（B-11.2.6）+ 分享素材（B-11.2.7）。
//
// **一期主获客路径的商家侧**（ADR-004 决策 3）：店主把店铺码印在包装袋、把文案发进
// 自己的客户群，老客带着复购习惯进来，获客成本 ≈ 0。
//
// 设计约束：**极简，店主是在手机上弄的**。不做拖拽布局、不做多模块编排 ——
// 一个公告 + 营业时间 + 地址就够了，多一个字段就多一个店主填不完的理由。
import { ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { SERVICE_SCOPE } from "@shared/utils/constants";
import type { Community, ServiceScope, ShareKit, StoreProfile, StoreQrcode } from "@shared/types";

const { t } = useI18n();

const form = ref<StoreProfile>({
  announcement: "",
  openHours: "",
  address: "",
  featured: [],
  serviceScope: SERVICE_SCOPE.COMMUNITY,
  serviceCommunityNos: [],
});
/** 可选社区。真实环境按商家已签约的自提点给，一期先给全量 */
const communities = ref<Community[]>([]);

const scopes = [SERVICE_SCOPE.COMMUNITY, SERVICE_SCOPE.CITY, SERVICE_SCOPE.PLATFORM] as const;

function pickScope(v: ServiceScope) {
  form.value.serviceScope = v;
}

function toggleCommunity(communityNo: string) {
  const list = form.value.serviceCommunityNos;
  const i = list.indexOf(communityNo);
  if (i >= 0) list.splice(i, 1);
  else list.push(communityNo);
}
const qrcode = ref<StoreQrcode | null>(null);
const kit = ref<ShareKit | null>(null);

async function load() {
  const [s, q, k, cs] = await Promise.all([
    api.mStore(),
    api.mStoreQrcode(),
    api.mShareKit(),
    api.mCommunities().catch(() => []),
  ]);
  form.value = s;
  communities.value = cs;
  qrcode.value = q;
  kit.value = k;
}

async function save() {
  /*
   * 选了「仅本社区」却一个小区都没勾 —— **必须拦住**。
   * 存下去的话这家店在 C 端对谁都不可见：店主看着自己的商品好好地上着架，
   * 一个订单也不来，还完全不知道为什么。这是那种自己永远查不出来的故障。
   */
  if (form.value.serviceScope === SERVICE_SCOPE.COMMUNITY && !form.value.serviceCommunityNos.length) {
    uni.showToast({ title: t("store.scopeNeedCommunity"), icon: "none" });
    return;
  }
  form.value = await api.mSaveStore(form.value);
  uni.showToast({ title: t("common.saved"), icon: "none" });
}

function copyText() {
  if (!kit.value) return;
  uni.setClipboardData({
    data: kit.value.text,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

function copyLink() {
  if (!qrcode.value) return;
  uni.setClipboardData({
    data: qrcode.value.url,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="store.title">
    <text class="sh-h1">{{ $t("store.title") }}</text>

    <!--
      经营范围。放在装修**之前** —— 公告写不写只影响好看，范围选错直接决定有没有生意：
      选大了卖到送不到的地方（下单后提不了货 → 退款），选小了整片小区搜不到这家店。
      所以每一项都写清楚后果，不做成三个干巴巴的单选。
    -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("store.scope") }}</text>
      <text class="hint">{{ $t("store.scopeHint") }}</text>

      <view
        v-for="sc in scopes"
        :key="sc"
        class="scope"
        :class="{ 'is-on': form.serviceScope === sc }"
        @tap="pickScope(sc)"
      >
        <view class="scope__main">
          <text class="scope__name">{{ $t(`serviceScope.${sc}`) }}</text>
          <text class="scope__desc">{{ $t(`store.scopeDesc.${sc}`) }}</text>
        </view>
        <text class="scope__tick">{{ form.serviceScope === sc ? "✓" : "" }}</text>
      </view>

      <!-- 只有「仅本社区」才需要选小区，其余两档选了也用不上 -->
      <view v-if="form.serviceScope === SERVICE_SCOPE.COMMUNITY" class="cms">
        <text class="field__label">{{ $t("store.scopeCommunities") }}</text>
        <view class="cms__list">
          <text
            v-for="c in communities"
            :key="c.communityNo"
            class="sh-chip cms__i"
            :class="{ 'is-on': form.serviceCommunityNos.includes(c.communityNo) }"
            @tap="toggleCommunity(c.communityNo)"
          >
            {{ c.name }}
          </text>
        </view>
        <text v-if="!form.serviceCommunityNos.length" class="warn">
          {{ $t("store.scopeNeedCommunity") }}
        </text>
      </view>

      <view class="sh-btn sh-btn--soft save" @tap="save">{{ $t("common.save") }}</view>
    </view>

    <!-- 装修：只有三个字段 -->
    <view class="sh-card mt">
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
      </view>

      <view class="field">
        <text class="field__label">{{ $t("store.address") }}</text>
        <input v-model="form.address" class="field__input" placeholder="阳光里小区南门" />
      </view>

      <view class="sh-btn sh-btn--soft save" @tap="save">{{ $t("common.save") }}</view>
    </view>

    <!-- 店铺码：线下场景的主入口，印在包装袋上 -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("store.qrcode") }}</text>
      <view class="qr">
        <text class="qr__ph">▦</text>
        <text class="sh-muted qr__note">{{ $t("store.qrcodePlaceholder") }}</text>
      </view>
      <text class="link sh-num">{{ qrcode?.url }}</text>
      <view class="btns">
        <text class="mini" @tap="copyLink">{{ $t("store.copyLink") }}</text>
        <text class="mini">{{ $t("store.printVersion") }}</text>
      </view>
      <text class="hint">{{ $t("store.qrcodeHint") }}</text>
    </view>

    <!-- 分享素材：一键复制，发进自己的客户群 -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("store.shareKit") }}</text>
      <view class="kit">{{ kit?.text }}</view>
      <view class="sh-btn copy" @tap="copyText">{{ $t("store.copyText") }}</view>
      <text class="hint">{{ $t("store.shareKitHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.scope {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx;
  margin-top: 16rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.scope.is-on {
  background: var(--sh-primary-tint);
}
.scope__main {
  flex: 1;
  min-width: 0;
}
.scope__name {
  display: block;
  font-size: 27rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.scope__desc {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.scope__tick {
  flex-shrink: 0;
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-primary);
}
.cms {
  margin-top: 28rpx;
}
.cms__list {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 14rpx;
}
.cms__i.is-on {
  background: var(--sh-primary);
  color: #fff;
}
.warn {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: var(--sh-danger);
}
.mt {
  margin-top: 24rpx;
}
.hint {
  display: block;
  margin-top: 16rpx;
  font-size: 22rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
.save {
  margin-top: 32rpx;
}
.qr {
  margin: 24rpx 0;
  padding: 40rpx;
  border-radius: 32rpx;
  background: var(--sh-faint);
  text-align: center;
}
.qr__ph {
  display: block;
  font-size: 140rpx;
  line-height: 1;
  color: var(--sh-sub);
}
.qr__note {
  display: block;
  margin-top: 20rpx;
  font-size: 22rpx;
}
.link {
  display: block;
  font-size: 22rpx;
  color: var(--sh-sub);
  word-break: break-all;
}
.btns {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.kit {
  margin: 20rpx 0;
  padding: 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  line-height: 1.7;
  color: var(--sh-ink);
}
.copy {
  margin-top: 8rpx;
}
</style>
