<script setup lang="ts">
// 入驻申请（B-11.1）。
//
// 设计要点：**门槛前低后高**（ADR-002 §4）—— 个人主体免资质、收款走微信零钱，
// 先让人开得起张；做大之后再升个体户/企业。所以「主体类型」是这张表的第一个字段，
// 它决定后面资质与结算账户两块要不要填。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { scrollToTop } from "@ai-shop/ui/scroll";
import { USE_MOCK } from "@/api";
import { ensureDemoOrders } from "@/api/demo-orders";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { pickImages } from "@shared/ports/media";
import type { MerchantSubject } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const SUBJECTS: MerchantSubject[] = ["PERSONAL", "INDIVIDUAL_BIZ", "COMPANY"];

const form = ref({
  name: "",
  subject: "PERSONAL" as MerchantSubject,
  contactName: "",
  contactPhone: "",
  category: "",
  desc: "",
  asPickupPoint: true,
});
const submitting = ref(false);

const status = computed(() => merchant.profile?.status ?? "NONE");
/** 个人主体不需要营业执照，也就不需要商户号 */
const needLicense = computed(() => form.value.subject !== "PERSONAL");
const settleType = computed(() =>
  needLicense.value ? "settleMERCHANT_ID" : "settlePERSONAL_OPENID",
);
const canSubmit = computed(
  () => !!form.value.name && !!form.value.contactName && /^\d{11}$/.test(form.value.contactPhone),
);

/** 已上传的资质图（个体户/企业必需，个人免） */
const licenses = ref<string[]>([]);
const uploading = ref(false);

onShow(async () => {
  // **不拿 profile.phone 预填**：那是脱敏后的登录号（138****8000），
  // 填进去看着像已填好，实际过不了 11 位校验，人只会盯着一个"填了的"框发愣。
  // 联系号码本来也不一定等于登录号 —— 店主登录，留的是店里座机是常事。
  // 驳回后回填上次填过的内容 —— 驳回往往只是缺一张执照，
  // 让人从头重填一遍是把「补交」变成「重来」
  const draft = await api.mApplyDraft().catch(() => null);
  if (!draft) return;
  form.value = {
    name: draft.name,
    subject: draft.subject,
    contactName: draft.contactName,
    contactPhone: draft.contactPhone,
    category: draft.category,
    desc: draft.desc,
    // 契约里这几项是选填（分账主体属于独立开户流程，ADR-002），
    // 但 B 端表单确实收，草稿回显时给默认值
    asPickupPoint: draft.asPickupPoint ?? false,
  };
  licenses.value = [...(draft.licenses ?? [])];
});

/** 上传资质。缺它正是个体户/企业被驳回的主因，所以入口要显眼 */
async function addLicense() {
  if (uploading.value) return;
  let picked;
  try {
    picked = await pickImages(1, ["camera", "album"]);
  } catch {
    return; // 取消不是错误
  }
  const img = picked[0];
  if (!img) return;
  uploading.value = true;
  try {
    const { url } = await api.mUploadImage(img.tempPath);
    licenses.value.push(url);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  if (!canSubmit.value) {
    uni.showToast({ title: t("apply.required"), icon: "none" });
    return;
  }
  if (submitting.value) return;
  submitting.value = true;
  try {
    const profile = await api.mApply({
      ...form.value,
      licenses: licenses.value,
      settleAccountType: needLicense.value ? "MERCHANT_ID" : "PERSONAL_OPENID",
    });
    merchant.profile = profile;

    // **被驳回就不要说「已提交」也不要跳走** —— 提交了但没过，
    // 跳到工作台看到「还没有开店」只会让人以为系统坏了。
    // 留在原页，驳回原因就在上方，改完再交。
    if (profile.status === "REJECTED") {
      uni.showToast({ title: profile.rejectReason || t("apply.rejectFallback"), icon: "none" });
      scrollToTop();
      return;
    }

    // 演示数据要在**这里**补一次：App 启动时补的那次跑在入驻之前，
    // 那时还不知道是哪家店，于是新商家进来订单/核销/分拣三个页面永远是空的，
    // 看着像功能坏了（老账号因为本地已有旧数据，反而看不出这个坑）
    if (USE_MOCK) ensureDemoOrders();

    uni.showToast({ title: t("apply.submitted"), icon: "none" });
    setTimeout(() => uni.switchTab({ url: ROUTES.home }), 600);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <sh-scaffold title-key="apply.title">
    <view class="head">
      <text class="sh-h1">{{ $t("apply.title") }}</text>
      <text class="sh-muted mt">{{ $t("apply.hint") }}</text>
    </view>

    <!-- 审核中/驳回：不重复渲染整张表，先把状态说清楚 -->
    <view v-if="status === 'APPLYING'" class="sh-card status">
      <text class="sh-h2">{{ $t("apply.statusAPPLYING") }}</text>
      <text class="sh-muted mt">{{ $t("apply.statusAPPLYINGHint") }}</text>
    </view>

    <!-- 驳回：**必须说清楚为什么** —— 只显示「已驳回」等于让人猜，
         下面的表单已回填上次内容，改缺的那一项再交即可 -->
    <view v-if="status === 'REJECTED'" class="sh-card rejected">
      <text class="sh-h2">{{ $t("apply.statusREJECTED") }}</text>
      <text class="reason">{{ merchant.profile?.rejectReason || $t("apply.rejectFallback") }}</text>
      <text class="sh-muted mt">{{ $t("apply.rejectedHint") }}</text>
    </view>

    <view class="sh-card">
      <view class="field">
        <text class="field__label">{{ $t("apply.subject") }}</text>
        <view class="chips">
          <text
            v-for="s in SUBJECTS"
            :key="s"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.subject === s }"
            @tap="form.subject = s"
          >
            {{ $t(`apply.subject${s}`) }}
          </text>
        </view>
        <text class="hint">{{ $t("apply.subjectHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.name") }}</text>
        <input v-model="form.name" class="field__input" placeholder="张记粮油" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.contact") }}</text>
        <input v-model="form.contactName" class="field__input" placeholder="张老板" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.phone") }}</text>
        <input
          v-model="form.contactPhone"
          class="field__input sh-num"
          type="number"
          maxlength="11"
          placeholder="13800138000"
        />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.category") }}</text>
        <input v-model="form.category" class="field__input" :placeholder="$t('apply.categoryPh')" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.desc") }}</text>
        <input v-model="form.desc" class="field__input" placeholder="街角三十年老店" />
      </view>
    </view>

    <!-- 自提点：小店既是供给方也是取货点（ADR-005 type=STORE） -->
    <view class="sh-card mt-card">
      <view class="switch-row" @tap="form.asPickupPoint = !form.asPickupPoint">
        <view class="switch-row__text">
          <text class="sh-h2">{{ $t("apply.asPickup") }}</text>
          <text class="hint">{{ $t("apply.asPickupHint") }}</text>
        </view>
        <view class="toggle" :class="{ 'is-on': form.asPickupPoint }">
          <view class="toggle__dot" />
        </view>
      </view>
    </view>

    <view class="sh-card mt-card">
      <text class="field__label">{{ $t("apply.settle") }}</text>
      <text class="sh-h2">{{ $t(`apply.${settleType}`) }}</text>
      <text class="hint">{{ $t("apply.settleHint") }}</text>
      <view v-if="needLicense" class="license">
        <text class="field__label">{{ $t("apply.licenses") }}</text>
        <text class="hint">{{ $t("apply.licensesHint") }}</text>
        <view class="shots">
          <image
            v-for="(url, i) in licenses"
            :key="i"
            :src="url"
            class="shot"
            mode="aspectFill"
          />
          <view class="shot shot--add" @tap="addLicense">
            <text>{{ uploading ? $t("apply.uploading") : "＋" }}</text>
          </view>
        </view>
      </view>
    </view>

    <view class="sh-btn submit" :class="{ 'sh-btn--muted': !canSubmit }" @tap="submit">
      {{ status === "REJECTED" ? $t("apply.resubmit") : $t("apply.submit") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.rejected {
  margin-bottom: 24rpx;
  background: var(--sh-danger-tint);
}
.reason {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: var(--sh-danger);
  line-height: 1.6;
}
.shots {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 20rpx;
}
.shot {
  width: 140rpx;
  height: 140rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.shot--add {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 40rpx;
  color: var(--sh-sub);
}

.head {
  padding: 32rpx 8rpx 28rpx;
}
.mt {
  display: block;
  margin-top: 12rpx;
}
.mt-card {
  margin-top: 24rpx;
}
.status {
  margin-bottom: 24rpx;
  background: var(--sh-warning-tint);
}
.field + .field {
  margin-top: 20rpx;
}
.chips {
  display: flex;
  gap: 16rpx;
  flex-wrap: wrap;
}
.chips .sh-chip {
  font-size: 26rpx;
  padding: 14rpx 28rpx;
}
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
}
.switch-row {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.switch-row__text {
  flex: 1;
}
.toggle {
  width: 88rpx;
  height: 48rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  padding: 4rpx;
  transition: background 0.2s ease;
}
.toggle.is-on {
  background: var(--sh-primary);
}
.toggle__dot {
  width: 40rpx;
  height: 40rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  transition: transform 0.2s ease;
}
.toggle.is-on .toggle__dot {
  transform: translateX(40rpx);
}
.license {
  margin-top: 28rpx;
}
.submit {
  margin-top: 40rpx;
}
</style>
