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
import type { Community, MasterData, MerchantSubject, ServiceScope, QualificationItem } from "@shared/types";
import { isPhone, notBlank } from "@shared/utils/validate";

const { t } = useI18n();
const merchant = useMerchantStore();

/*
 * 主体与行业都**从 /common/master-data 取**，不再是页面里的常量 ——
 * 微信放开某个行业的小微白名单时不用发版。
 * 主数据没回来之前用这份兜底：表单不能因为一个 GET 失败就打不开。
 */
const SUBJECTS: MerchantSubject[] = ["NATURAL_PERSON", "INDIVIDUAL", "ENTERPRISE"];
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
  subject: "NATURAL_PERSON" as MerchantSubject,
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
/**
 * 要不要营业执照 —— **从主数据取，不在端上写死取值**。
 *
 * 这一行原先是 `subject !== "MICRO"`。权威在 `sys_legal_form.need_license`，
 * 而取值在 V87 就真的改了（MICRO → NATURAL_PERSON）—— 当初若写死取值，
 * 那天会**静默失配**：
 * 不报错，只是所有人都被要求传执照，或者所有人都不被要求。
 *
 * 取不到主数据时按 true（要执照）：宁可多要一次，也不要放进一个本该有照却没照的商家。
 */
const needLicense = computed(() => {
  const meta = master.value?.subjects.find((x) => x.subjectType === form.value.subject);
  return meta ? meta.needLicense : true;
});
const settleType = computed(() =>
  needLicense.value ? "settleMERCHANT_ID" : "settlePERSONAL_BANK_CARD",
);
/** 「仅本社区」却一个小区都没选 = 上架后对谁都不可见，所以它也是提交的前置 */
const scopeReady = computed(
  () => form.value.serviceScope !== SERVICE_SCOPE.COMMUNITY || form.value.communityNos.length > 0,
);
const canSubmit = computed(
  () =>
    notBlank(form.value.name) &&
    notBlank(form.value.contactName) &&
    // `!!x` 会把 `"   "` 当成填了；`/^\d{11}$/` 会把 `00000000000` 当成手机号
    isPhone(form.value.contactPhone) &&
    scopeReady.value,
);

/** 已上传的资质图（旧字段，仍然传 —— 后端两个都收，存量申请单靠它回看） */
const licenses = ref<string[]>([]);
const uploading = ref(false);

/**
 * **结构化资质**：只有带类型/证号/有效期的这一份，审核通过时才转得进
 * `mch_qualification` —— 而上架的两个闸门（资质过期、类目授权）读的就是那张表。
 *
 * 光传图片 URL 填不出那些列，所以此前商家传的执照停在申请单里，两个闸门从不触发。
 */
// 直接用契约类型，不在端上另造一个 —— 另造的那个迟早与契约漂移，而漂移不报错
const qualItems = ref<QualificationItem[]>([]);
/** 长期有效 —— 勾上时 expireAt 传 null。**不要用 0 或一个很大的数字冒充**：
 *  过期扫描会把前者当成已过期、后者当成永不过期，两种都错且都不报错 */
const foreverFlags = ref<boolean[]>([]);

function addQual(type: QualificationItem["type"]) {
  qualItems.value.push({ type, code: "", imageUrl: "", expireAt: null });
  foreverFlags.value.push(true);
}
function removeQual(i: number) {
  qualItems.value.splice(i, 1);
  foreverFlags.value.splice(i, 1);
}
/** 提交前把界面状态折成后端要的形状；勾了长期有效就抹掉日期 */
function toPayload(): QualificationItem[] {
  return qualItems.value
    .filter((q) => q.type && q.imageUrl)
    .map((q, i) => ({ ...q, expireAt: foreverFlags.value[i] ? null : q.expireAt }));
}
/** 缺营业执照 —— 需要执照的档位不能提交 */
const licenseMissing = computed(
  () => needLicense.value && !qualItems.value.some((q) => q.type === "BUSINESS_LICENSE" && q.imageUrl),
);

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

  /*
   * **结构化资质也要回填**（V79）。
   *
   * 此前只回填了 `licenses` —— 那只有图片 URL。证件类型、编号、有效期三项全丢，
   * 商家重提时得逐格再填一遍，而这正是上面那段注释想避免的：
   * 「让人从头重填一遍是把补交变成重来」。后端一直在发 `qualificationItems`。
   *
   * `foreverFlags` 由 expireAt 推出：null = 长期有效（见 QualificationItem 的注释，
   * 不要用 0 或很大的数字冒充）。
   */
  qualItems.value = (draft.qualificationItems ?? []).map((q) => ({ ...q }));
  foreverFlags.value = qualItems.value.map((q) => q.expireAt == null);
});

/** 上传资质。缺它正是个体户/企业被驳回的主因，所以入口要显眼 */
/** @param idx 传入下标 = 上传到该条结构化资质；不传 = 旧的图片数组（两者并存） */
async function addLicense(idx?: number) {
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
    if (idx === undefined) {
      licenses.value.push(url);
    } else {
      qualItems.value[idx]!.imageUrl = url;
      // 旧字段同步塞一份：审核台与存量逻辑还在读它
      licenses.value.push(url);
    }
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
  if (licenseMissing.value) {
    // 单独提示：与「必填项没填」不是一回事 —— 后端也会拒，但在这里说清楚缺的是哪张证
    uni.showToast({ title: t("apply.licenseRequired"), icon: "none" });
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
      qualificationItems: toPayload(),
      settleAccountType: needLicense.value ? "MERCHANT_ID" : "PERSONAL_BANK_CARD",
    });
    merchant.profile = profile;

    // **没过审就不要跳走** —— 工作台的空态只会判断「有没有生效的店」（isActive），
    // APPLYING 和从没申请过在那边长得一模一样，跳过去看到「还没有开店」
    // 会让人以为提交失败了，回头再交一次。留在原页，用上面的状态卡说清楚。
    if (profile.status === "REJECTED") {
      uni.showToast({ title: profile.rejectReason || t("apply.rejectFallback"), icon: "none" });
      scrollToTop();
      return;
    }
    if (profile.status === "APPLYING") {
      uni.showToast({ title: t("apply.submitted"), icon: "none" });
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
      <text class="txt-display">{{ $t("apply.title") }}</text>
      <text class="sh-muted sh-mt-xs blk">{{ $t("apply.hint") }}</text>
    </view>

    <!-- 审核中/驳回：不重复渲染整张表，先把状态说清楚 -->
    <view v-if="status === 'APPLYING'" class="sh-card status">
      <text class="txt-title">{{ $t("apply.statusAPPLYING") }}</text>
      <text class="sh-muted sh-mt-xs blk">{{ $t("apply.statusAPPLYINGHint") }}</text>
    </view>

    <!-- 驳回：**必须说清楚为什么** —— 只显示「已驳回」等于让人猜，
         下面的表单已回填上次内容，改缺的那一项再交即可 -->
    <view v-if="status === 'REJECTED'" class="sh-card rejected">
      <text class="txt-title">{{ $t("apply.statusREJECTED") }}</text>
      <text class="txt-body reason">{{ merchant.profile?.rejectReason || $t("apply.rejectFallback") }}</text>
      <text class="sh-muted sh-mt-xs blk">{{ $t("apply.rejectedHint") }}</text>
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
        <text class="sh-hint">{{ $t("apply.industryHint") }}</text>
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
        <text v-if="!subjectAllowed('NATURAL_PERSON')" class="txt-caption warn">
          {{ $t("apply.microBlocked") }}
        </text>
        <text class="sh-hint">{{ $t("apply.subjectHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.name") }}</text>
        <input maxlength="64" v-model="form.name" class="field__input" placeholder="张记粮油" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.contact") }}</text>
        <input maxlength="64" v-model="form.contactName" class="field__input" placeholder="张老板" />
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
        <input maxlength="64" v-model="form.category" class="field__input" :placeholder="$t('apply.categoryPh')" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("apply.desc") }}</text>
        <input maxlength="255" v-model="form.desc" class="field__input" placeholder="街角三十年老店" />
      </view>
    </view>

    <!--
      服务范围：决定这家店的货在 C 端能被谁看到。
      不是展示问题 —— 选大了会卖到送不到的地方（下单后提不了货），
      选小了整片小区都搜不到这家店。所以给后果说明，不只给三个单选。
    -->
    <view class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("store.scope") }}</text>
      <text class="sh-hint">{{ $t("apply.scopeHint") }}</text>

      <sh-option
        v-for="sc in scopes"
        :key="sc"
        class="scope"
        :selected="form.serviceScope === sc"
        @tap="pickScope(sc)"
      >
        <view class="sh-fill">
          <text class="txt-strong scope__name">{{ $t(`serviceScope.${sc}`) }}</text>
          <text class="txt-caption scope__desc">{{ $t(`store.scopeDesc.${sc}`) }}</text>
        </view>
        <sh-icon v-if="form.serviceScope === sc" class="scope__tick" name="check"
          :size="30" color="var(--sh-primary-text)"></sh-icon>
      </sh-option>

      <!-- 只有「仅本社区」才需要选小区，其余两档选了也用不上 -->
      <view v-if="form.serviceScope === SERVICE_SCOPE.COMMUNITY" class="cms">
        <text class="field__label">{{ $t("store.scopeCommunities") }}</text>
        <view class="cms__list">
          <text
            v-for="c in communities"
            :key="c.communityNo"
            class="sh-chip"
            :class="{ 'sh-chip--solid': form.communityNos.includes(c.communityNo) }"
            @tap="toggleCommunity(c.communityNo)"
          >
            {{ c.name }}
          </text>
        </view>
        <!-- 加载失败与「真的一个小区都没有」要分开说 -->
        <text v-if="communitiesFailed" class="txt-caption warn">
          {{ $t("store.communitiesFailed") }}
        </text>
        <text v-else-if="!communities.length" class="txt-caption warn">
          {{ $t("store.communitiesEmpty") }}
        </text>
        <text v-else-if="!form.communityNos.length" class="txt-caption warn">
          {{ $t("store.scopeNeedCommunity") }}
        </text>
      </view>
    </view>

    <!-- 自提点：小店既是供给方也是取货点（ADR-005 type=STORE） -->
    <view class="sh-card sh-mt-sm">
      <view class="switch-row" @tap="form.asPickupPoint = !form.asPickupPoint">
        <view class="switch-row__text">
          <text class="txt-title">{{ $t("apply.asPickup") }}</text>
          <text class="sh-hint">{{ $t("apply.asPickupHint") }}</text>
        </view>
        <sh-switch :model-value="form.asPickupPoint"></sh-switch>
      </view>
    </view>

    <view class="sh-card sh-mt-sm">
      <text class="field__label">{{ $t("apply.settle") }}</text>
      <text class="txt-title">{{ $t(`apply.${settleType}`) }}</text>
      <text class="sh-hint">{{ $t("apply.settleHint") }}</text>
      <!-- 免执照档位：整块隐藏，换一句说明。对自然人要执照本来就是错的 -->
      <view v-if="!needLicense" class="license">
        <text class="sh-hint">{{ $t("apply.noLicenseNeeded") }}</text>
      </view>

      <view v-else class="license">
        <text class="field__label">{{ $t("apply.licenses") }}</text>
        <text class="sh-hint">{{ $t("apply.licensesHint") }}</text>

        <view v-for="(q, i) in qualItems" :key="i" class="qual">
          <view class="qual__head">
            <text class="txt-bold">{{ $t(`apply.qual${q.type}`) }}</text>
            <text class="txt-caption qual__del" @tap="removeQual(i)">{{ $t("apply.qualRemove") }}</text>
          </view>
          <input
            maxlength="64"
            v-model="q.code"
            class="sh-input"
            :placeholder="$t('apply.qualCode')"
          />
          <view class="qual__row">
            <view class="qual__forever" @tap="foreverFlags[i] = !foreverFlags[i]">
              <sh-check :model-value="foreverFlags[i]"></sh-check>
              <text>{{ $t("apply.qualForever") }}</text>
            </view>
            <input
              maxlength="10"
              v-if="!foreverFlags[i]"
              class="sh-input qual__date"
              type="number"
              :value="q.expireAt ?? ''"
              :placeholder="$t('apply.qualExpire')"
              @input="q.expireAt = Number(($event as any).detail.value) || null"
            />
          </view>
          <sh-uploader
            :list="q.imageUrl ? [q.imageUrl] : []"
            :w="140"
            :uploading="uploading"
            @add="addLicense(i)"
            @tap-item="addLicense(i)"
          ></sh-uploader>
        </view>

        <view class="qual__add">
          <text @tap="addQual('BUSINESS_LICENSE')">＋ {{ $t("apply.qualBUSINESS_LICENSE") }}</text>
          <text @tap="addQual('FOOD_PERMIT')">＋ {{ $t("apply.qualFOOD_PERMIT") }}</text>
        </view>
        <text v-if="licenseMissing" class="txt-caption warn">{{ $t("apply.licenseRequired") }}</text>
      </view>
    </view>

    <view class="sh-btn submit" :class="{ 'sh-btn--muted': !canSubmit }" @tap="submit">
      {{ status === "REJECTED" ? $t("apply.resubmit") : $t("apply.submit") }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.rejected {
  margin-bottom: 16rpx;
  background: var(--sh-danger-tint);
}
.reason {
  display: block;
  margin-top: 12rpx;
  color: var(--sh-danger);
}
/* 结构化资质：一条一块，与旧的「一排缩略图」区分开 —— 那个表达不出「哪张证」 */
.qual {
  margin-top: 20rpx;
  padding: 20rpx;
  border: var(--sh-hairline);
  border-radius: 16rpx;
}
.qual__head { display: flex; justify-content: space-between; align-items: center; }

.qual__del {
  color: var(--sh-danger);
}
.qual__row { display: flex; align-items: center; gap: 16rpx; margin-top: 12rpx; }
.qual__forever { display: flex; align-items: center; gap: 8rpx; }
.qual__date { flex: 1; }
.qual__add { display: flex; gap: 24rpx; margin-top: 20rpx; color: var(--sh-primary-text); }

.head {
  padding: 32rpx 8rpx 28rpx;
}
.blk {
  display: block;
}
.status {
  margin-bottom: 16rpx;
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
  padding: 14rpx 28rpx;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.switch-row__text {
  flex: 1;
}
.license {
  margin-top: 20rpx;
}

.sh-chip.is-blocked {
  opacity: 0.4;
}

/* 服务范围选择器：与店铺设置页同一套观感 —— 同一件事在两处长得不一样会让人以为是两件事 */
/* 形态（描边 / 圆角 / 选中底色）由 `sh-option` 给 —— `member-settings` 的范围
   选择用的就是它，这里是同一件事漏收的一个。页面只留「名称与说明左、勾右」的排布。 */
.scope {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 16rpx;
}

.scope__name {
  display: block;
}
.scope__desc {
  display: block;
  margin-top: 8rpx;
}
.scope__tick {
  flex-shrink: 0;
}
.cms {
  margin-top: 20rpx;
}
.cms__list {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-top: 16rpx;
}

.warn {
  display: block;
  margin-top: 16rpx;
  color: var(--sh-danger);
}
.submit {
  margin-top: 28rpx;
}
</style>
