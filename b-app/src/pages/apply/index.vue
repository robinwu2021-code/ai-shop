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
import { SERVICE_SCOPE } from "@shared/utils/constants";
import type { Community, MasterData, MerchantSubject, ServiceScope } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/*
 * 主体与行业都**从 /common/master-data 取**，不再是页面里的常量 ——
 * 微信放开某个行业的小微白名单时不用发版。
 * 主数据没回来之前用这份兜底：表单不能因为一个 GET 失败就打不开。
 */
const SUBJECTS: MerchantSubject[] = ["MICRO", "INDIVIDUAL", "ENTERPRISE"];
const master = ref<MasterData | null>(null);
const industries = computed(() => master.value?.industries ?? []);
const subjectList = computed<MerchantSubject[]>(
  () => master.value?.subjects.map((s) => s.subjectType) ?? SUBJECTS,
);

/**
 * 当前行业允许的主体类型。
 *
 * **小微受行业白名单管控**（`industryGated`），其余主体不受。
 * 不做这个联动的后果不是提示不友好，是**进件被拒** ——
 * 而那时商家已经开完店、上完架，回头再改主体要重走一遍开户。
 */
const subjectAllowed = (s: MerchantSubject) => {
  const meta = master.value?.subjects.find((x) => x.subjectType === s);
  if (!meta?.industryGated) return true;
  const ind = industries.value.find((i) => i.industry === form.value.industry);
  // 还没选行业时不禁用：先禁再解释，人会以为这个选项坏了
  return !ind || ind.microAllowed;
};

/*
 * 档位**从主数据取**，与上面的行业、主体同一个理由 —— 这一行原先是写死的三档。
 *
 * 写死的后果不是「多一个选项」：一期自营模式关掉了 PLATFORM，而这里照样把
 * 「全平台发货」摆出来，商家点下去得到「当前不支持这个经营范围」——
 * 一个必被拒的选项，而他无从知道自己该选什么。2026-08-11 端到端实测撞到过。
 *
 * 主数据没取到时退到「仅本社区」一档：它是启用白名单里永远不会空的那一档
 * （后台不允许全关），也是一期的主力形态。退到三档才是危险的 —— 那等于
 * 在加载失败时把已知拒绝的选项重新摆回去。
 */
const scopes = computed<readonly ServiceScope[]>(
  () => master.value?.serviceScopes ?? [SERVICE_SCOPE.COMMUNITY],
);

const form = ref({
  name: "",
  subject: "MICRO" as MerchantSubject,
  contactName: "",
  contactPhone: "",
  category: "",
  desc: "",
  /** 行业：决定能不能以小微进件，也是 points_forced 的来源。此前根本没有这个字段 */
  industry: "",
  asPickupPoint: true,
  /*
   * 服务范围**必须在申请时就填**。
   *
   * 此前这张表没有这两项，提交上去恒空 —— 本该由运营在审核时补，
   * 而运营侧那条链还没接通，于是没有任何地方能填上它：
   * 商家通过审核、商品上了架，**对谁都不可见**，而这个故障不报错。
   */
  serviceScope: SERVICE_SCOPE.COMMUNITY as ServiceScope,
  communityNos: [] as string[],
});
const submitting = ref(false);
const communities = ref<Community[]>([]);
/**
 * 小区列表**没加载出来**（区别于「加载成功但一个都没有」）。
 *
 * 两者在界面上都是一片空白，后果却相反：前者刷新可能就好，后者等也没用。
 * 不分开的话，申请人对着空白只会反复点提交 —— 而「仅本社区」必须选一个小区，
 * 提交永远过不去，他也永远不知道为什么。
 */
const communitiesFailed = ref(false);

function pickScope(v: ServiceScope) {
  form.value.serviceScope = v;
}

function toggleCommunity(communityNo: string) {
  const list = form.value.communityNos;
  const i = list.indexOf(communityNo);
  if (i >= 0) list.splice(i, 1);
  else list.push(communityNo);
}

const status = computed(() => merchant.profile?.status ?? "NONE");
/** 个人主体不需要营业执照，也就不需要商户号 */
// 小微免执照 —— 这正是它存在的意义。权威在 sys_merchant_subject.need_license
const needLicense = computed(() => form.value.subject !== "MICRO");
const settleType = computed(() =>
  needLicense.value ? "settleMERCHANT_ID" : "settlePERSONAL_OPENID",
);
/** 「仅本社区」却一个小区都没选 = 上架后对谁都不可见，所以它也是提交的前置 */
const scopeReady = computed(
  () => form.value.serviceScope !== SERVICE_SCOPE.COMMUNITY || form.value.communityNos.length > 0,
);
const canSubmit = computed(
  () =>
    !!form.value.name &&
    !!form.value.contactName &&
    /^\d{11}$/.test(form.value.contactPhone) &&
    scopeReady.value,
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
  // 可选小区与主数据先取：驳回回填时要按它们显示已选中的项与可选主体
  communities.value = await api.mCommunities().catch(() => {
    communitiesFailed.value = true;
    return [];
  });
  master.value = await api.mMasterData().catch(() => null);

  const draft = await api.mApplyDraft().catch(() => null);
  if (!draft) return;
  form.value = {
    name: draft.name,
    subject: draft.subject,
    contactName: draft.contactName,
    contactPhone: draft.contactPhone,
    category: draft.category,
    desc: draft.desc,
    industry: draft.industry ?? "",
    // 契约里这几项是选填（分账主体属于独立开户流程，ADR-002），
    // 但 B 端表单确实收，草稿回显时给默认值
    asPickupPoint: draft.asPickupPoint ?? false,
    serviceScope: draft.serviceScope ?? SERVICE_SCOPE.COMMUNITY,
    communityNos: [...(draft.communityNos ?? [])],
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
  if (!scopeReady.value) {
    // 单独给这条提示：它与「必填项没填」不是一回事，而后果比必填项更严重
    uni.showToast({ title: t("store.scopeNeedCommunity"), icon: "none" });
    return;
  }
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
      <!--
        行业排在主体之前：**它决定主体能不能选小微**（微信白名单按行业给）。
        顺序反了的话，人先挑了小微再选一个不允许小微的行业，
        要么被无声改掉选择，要么提交后才被拒。
      -->
      <view class="field">
        <text class="field__label">{{ $t("apply.industry") }}</text>
        <view class="chips">
          <text
            v-for="i in industries"
            :key="i.industry"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.industry === i.industry }"
            @tap="form.industry = i.industry"
          >
            {{ i.name }}
          </text>
        </view>
        <text class="hint">{{ $t("apply.industryHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.subject") }}</text>
        <view class="chips">
          <text
            v-for="s in subjectList"
            :key="s"
            class="sh-chip"
            :class="{
              'sh-chip--primary': form.subject === s,
              'is-blocked': !subjectAllowed(s),
            }"
            @tap="subjectAllowed(s) && (form.subject = s)"
          >
            {{ $t(`apply.subject${s}`) }}
          </text>
        </view>
        <!-- 禁用要给出理由：光变灰会让人以为是 bug，然后去反复点它 -->
        <text v-if="!subjectAllowed('MICRO')" class="warn">
          {{ $t("apply.microBlocked") }}
        </text>
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

    <!--
      服务范围：决定这家店的货在 C 端能被谁看到。
      不是展示问题 —— 选大了会卖到送不到的地方（下单后提不了货），
      选小了整片小区都搜不到这家店。所以给后果说明，不只给三个单选。
    -->
    <view class="sh-card mt-card">
      <text class="sh-h2">{{ $t("store.scope") }}</text>
      <text class="hint">{{ $t("apply.scopeHint") }}</text>

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
            :class="{ 'is-on': form.communityNos.includes(c.communityNo) }"
            @tap="toggleCommunity(c.communityNo)"
          >
            {{ c.name }}
          </text>
        </view>
        <!-- 加载失败与「真的一个小区都没有」要分开说 -->
        <text v-if="communitiesFailed" class="warn">
          {{ $t("store.communitiesFailed") }}
        </text>
        <text v-else-if="!communities.length" class="warn">
          {{ $t("store.communitiesEmpty") }}
        </text>
        <text v-else-if="!form.communityNos.length" class="warn">
          {{ $t("store.scopeNeedCommunity") }}
        </text>
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

.sh-chip.is-blocked {
  opacity: 0.4;
}

/* 服务范围选择器：与店铺设置页同一套观感 —— 同一件事在两处长得不一样会让人以为是两件事 */
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
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.scope__desc {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.scope__tick {
  flex-shrink: 0;
  font-size: 30rpx;
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
  font-size: 24rpx;
  color: var(--sh-danger);
}
.submit {
  margin-top: 40rpx;
}
</style>
