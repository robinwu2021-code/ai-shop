<script setup lang="ts">
/*
 * 新建券（原型 s13 填写 → s14 确认）。结构照搬新建活动：两步、一组组列表行；
 * **类型是一个字段**，改类型只换「规则」那一组 —— 选折扣时多出「封顶」，选商品券时换成「兑换商品 + 次数」。
 *
 * 四类券对应到库里（TDD-券与活动模型 §4.5）—— 类型不是一列，由权益方式 + 门槛推出来：
 *   满减券 = CASH + 门槛必填，下单抵扣；现金券 = CASH 无门槛，下单抵扣；
 *   折扣券 = PERCENT，下单抵扣，必须封顶；
 *   商品券 = GIFT × 兑换次数（1 = 单次，> 1 = 次卡），到店出示核销（核销页扣次数）。
 * 与活动的关系：满减券 / 折扣券与满减 / 打折活动是同一种优惠 —— 活动对范围内所有人自动生效，
 * 券要发到人手上才算数；下单先算活动，再在活动后的金额上用券。
 *
 * 确认页（s14）与活动确认页同一个位置放「最多支出」= 数量 × 单张最大优惠 × 次数。
 * 商家填的是张数，要为之负责的是钱。
 *
 * ⚠️ 这一页的校验**与后端一字不差**（mock 里也一样）。页面放宽的话，
 * 演示环境填得过、连真后端就被拒，而那时没人记得是哪一条拦的。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money, toMinor } from "@shared/utils/money";
import { couponKind, couponQuantity, couponRule, couponThreshold, couponValidity } from "@/shared/coupon-text";
import type { MerchantCoupon, MerchantCouponDraft } from "@shared/types";

const { t } = useI18n();
const tt = (k: string, a?: Record<string, unknown>) => String(t(k, a ?? {}));
const merchant = useMerchantStore();

type Kind = "FULL_CUT" | "CASH" | "PERCENT" | "GOODS";
const KINDS: Kind[] = ["FULL_CUT", "CASH", "PERCENT", "GOODS"];

const couponNo = ref("");
const step = ref<1 | 2>(1);
const saving = ref(false);
const showKind = ref(false);

function today(offsetDays = 0): string {
  const d = new Date(Date.now() + offsetDays * 86_400_000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

const form = ref({
  title: "",
  kind: "FULL_CUT" as Kind,
  /** 满减 / 现金：元；折扣：几折（8.5）；商品券：不用 */
  value: "",
  cap: "",
  minAmount: "",
  /** 商品券兑换几次。1 = 单次，> 1 = 次卡 */
  times: "1",
  /** 商品券兑换什么（「豆浆 1 杯」）。存进 benefitRef，核销页原样显示给店员 */
  gift: "",
  validityMode: "RELATIVE" as "RELATIVE" | "ABSOLUTE",
  validDays: "7",
  endDay: today(30),
  totalCount: "100",
  perUserLimit: "1",
});

const currentNo = ref("");
const failed = ref(false);

async function loadExisting(no: string) {
  currentNo.value = no;
  // 兜成 null 会让「编辑这张券」静默变成「新建一张空券」—— 失败就让页面说失败
  let c: MerchantCoupon;
  try {
    c = await api.mCoupon(no);
    failed.value = false;
  } catch {
    failed.value = true;
    return;
  }
  couponNo.value = c.couponNo;
  // 免运费券 B 端不建；真碰上一张就按现金券打开，别让页面空着
  const k = couponKind(c);
  const kind: Kind = k === "FREE_SHIP" ? "CASH" : k;
  form.value = {
    title: c.title,
    kind,
    value: kind === "PERCENT" ? String(c.benefitValue / 1000)
      : kind === "GOODS" ? "" : String((c.benefitValue / 100).toFixed(2)),
    cap: c.benefitCapMinor ? String((c.benefitCapMinor / 100).toFixed(2)) : "",
    minAmount: c.minAmountMinor ? String((c.minAmountMinor / 100).toFixed(2)) : "",
    times: String(c.timesTotal > 1 ? c.timesTotal : 1),
    gift: c.benefitRef ?? "",
    validityMode: c.validityMode === "ABSOLUTE" ? "ABSOLUTE" : "RELATIVE",
    validDays: String(c.validDays ?? 7),
    endDay: c.endAt ? today(Math.round((c.endAt - Date.now()) / 86_400_000)) : today(30),
    totalCount: c.totalCount == null ? "" : String(c.totalCount),
    perUserLimit: String(c.perUserLimit),
  };
}

/** 表单 → 入参。确认页的每一行也由它算，保证「看到的」就是「提交的」 */
const draft = computed<MerchantCouponDraft>(() => {
  const f = form.value;
  const kind = f.kind;
  /*
   * 折扣按「几折」输入、按万分比提交：8.5 折 → 8500。让商家直接填 8500 的话，
   * 他迟早会填 85 —— 那在这个口径里是「顾客付 0.85%」，等于白送。
   */
  const benefitValue = kind === "PERCENT" ? Math.round(Number(f.value || 0) * 1000)
    : kind === "GOODS" ? 0 : toMinor(f.value);
  const endAt = f.validityMode === "ABSOLUTE" ? new Date(`${f.endDay}T23:59:59`).getTime() : null;
  return {
    couponNo: couponNo.value || undefined,
    title: f.title.trim(),
    // 满减券与现金券库里都是 CASH，差别只在门槛：现金券的门槛固定为空
    benefitMode: kind === "GOODS" ? "GIFT" : kind === "FULL_CUT" ? "CASH" : kind,
    benefitValue,
    benefitCapMinor: kind === "PERCENT" ? toMinor(f.cap) : null,
    benefitRef: kind === "GOODS" ? f.gift.trim() : null,
    minAmountMinor: kind === "GOODS" || kind === "CASH" ? null : toMinor(f.minAmount) || null,
    scopeType: "ALL",
    scopeRefs: [],
    validityMode: f.validityMode,
    validDays: f.validityMode === "RELATIVE" ? Number(f.validDays || 7) : null,
    startAt: f.validityMode === "ABSOLUTE" ? Date.now() : null,
    endAt,
    issueMode: "TARGETED",
    // 商品券要到店拿货、按次扣，只能到店核销；满减 / 现金 / 折扣在下单时自动抵扣
    redeemMode: kind === "GOODS" ? "STORE_CODE" : "ORDER",
    timesTotal: kind === "GOODS" ? Number(f.times || 1) : 1,
    totalCount: f.totalCount ? Number(f.totalCount) : null,
    perUserLimit: Number(f.perUserLimit || 1),
    budgetMinor: null,
  };
});

/** 确认页当作一张券来描述（与列表、详情同一套说法） */
const preview = computed<MerchantCoupon>(() => ({
  ...(draft.value as MerchantCoupon),
  couponNo: couponNo.value,
  minQty: null,
  scopeType: "ALL",
  scopeRefs: [],
  receivedCount: 0,
  maxExposureMinor: null,
  status: "ACTIVE",
  usedTimes: 0,
  spentMinor: 0,
}));

/** 最多支出 = 数量 × 单张最大优惠 × 次数；商品券是兑换，不算钱 */
const maxSpend = computed(() => {
  const d = draft.value;
  const per = d.benefitMode === "CASH" ? d.benefitValue : d.benefitMode === "PERCENT" ? d.benefitCapMinor ?? 0 : 0;
  return d.totalCount == null ? null : per * d.totalCount * (d.timesTotal ?? 1);
});

/** 第一步能不能往下走。每条与后端同一个口径，不过就说哪一条 */
function checkFill(): string | null {
  const f = form.value;
  if (!f.title.trim()) return "couponEdit.needTitle";
  if ((f.kind === "CASH" || f.kind === "FULL_CUT") && !(toMinor(f.value) > 0)) return "couponEdit.needValue";
  // 满减券没有门槛就是现金券 —— 两个类型在库里同一列，门槛是唯一区别，所以这里必填
  if (f.kind === "FULL_CUT" && !(toMinor(f.minAmount) > 0)) return "couponEdit.needMin";
  if (f.kind === "PERCENT") {
    const rate = Math.round(Number(f.value || 0) * 1000);
    if (rate < 1000 || rate >= 10000) return "couponEdit.badRate";
    if (!(toMinor(f.cap) > 0)) return "couponEdit.needCap";
  }
  if (f.kind === "GOODS" && !(Number(f.times) >= 1)) return "couponEdit.needTimes";
  if (f.kind === "GOODS" && !f.gift.trim()) return "couponEdit.needGift";
  if (f.validityMode === "ABSOLUTE" && f.endDay < today()) return "couponEdit.badEnd";
  if (!(Number(f.perUserLimit) >= 1)) return "couponEdit.needPerUser";
  return null;
}

function next() {
  const bad = checkFill();
  if (bad) {
    uni.showToast({ title: tt(bad), icon: "none" });
    return;
  }
  step.value = 2;
}

async function save() {
  if (saving.value) return;
  saving.value = true;
  try {
    const c = await api.mSaveCoupon(draft.value);
    uni.redirectTo({ url: `${ROUTES.coupon}?couponNo=${c.couponNo}` });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

function cancel() {
  if (step.value === 2) step.value = 1;
  else uni.navigateBack();
}

onLoad((q) => {
  if (q?.couponNo) void loadExisting(q.couponNo as string);
});
</script>

<template>
  <sh-scaffold title-key="couponEdit.title" :denied="!merchant.can('biz:campaign')"
    :failed="failed"
    @retry="() => loadExisting(currentNo)"
  >
    <!-- ============================== 填写（s13） -->
    <template v-if="step === 1">
      <view class="sh-row sh-row--between prog">
        <text class="txt-body txt-bold">{{ $t("couponEdit.stepFill") }}</text>
        <text class="txt-caption sh-muted sh-num">{{ $t("couponEdit.progress", { i: 1 }) }}</text>
      </view>

      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.name") }}</text>
          <input v-model="form.title" maxlength="64" class="txt-body cell__input" :placeholder="$t('couponEdit.namePh')" />
        </view>
        <view class="sh-cell sh-row sh-row--between" @tap="showKind = true">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.kind") }}</text>
          <view class="sh-row">
            <text class="txt-body">{{ $t(`couponText.kind.${form.kind}`) }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("couponEdit.groupRule") }}</text>
      <view class="sh-cells">
        <view v-if="form.kind === 'CASH' || form.kind === 'FULL_CUT'" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.face") }}</text>
          <input v-model="form.value" maxlength="10" type="digit" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.facePh')" />
        </view>
        <template v-if="form.kind === 'PERCENT'">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.rate") }}</text>
            <input v-model="form.value" maxlength="10" type="digit" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.ratePh')" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.cap") }}</text>
            <input v-model="form.cap" maxlength="10" type="digit" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.capPh')" />
          </view>
        </template>
        <template v-if="form.kind === 'GOODS'">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.gift") }}</text>
            <input v-model="form.gift" maxlength="32" class="txt-body cell__input" :placeholder="$t('couponEdit.giftPh')" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.times") }}</text>
            <input v-model="form.times" maxlength="6" type="number" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.timesPh')" />
          </view>
        </template>
        <!-- 门槛：满减券必填、折扣券可选；现金券按定义无门槛、商品券不看金额，都不给这一行 -->
        <view v-if="form.kind === 'FULL_CUT' || form.kind === 'PERCENT'" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.min") }}</text>
          <input v-model="form.minAmount" maxlength="10" type="digit" class="txt-body cell__input sh-num"
                 :placeholder="$t(form.kind === 'FULL_CUT' ? 'couponEdit.minReqPh' : 'couponEdit.minPh')" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.scope") }}</text>
          <text class="txt-body">{{ $t("couponText.scopeAll") }}</text>
        </view>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("couponEdit.groupValidity") }}</text>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.validityMode") }}</text>
          <view class="sh-row segs">
            <text v-for="m in ['RELATIVE', 'ABSOLUTE']" :key="m" class="sh-seg seg"
                  :class="{ 'sh-seg--on': form.validityMode === m }"
                  @tap="form.validityMode = m as 'RELATIVE' | 'ABSOLUTE'">{{ $t(`couponEdit.validity.${m}`) }}</text>
          </view>
        </view>
        <view v-if="form.validityMode === 'RELATIVE'" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.days") }}</text>
          <input v-model="form.validDays" maxlength="6" type="number" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.daysPh')" />
        </view>
        <picker v-else mode="date" :value="form.endDay" :start="today()" @change="form.endDay = $event.detail.value">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.until") }}</text>
            <text class="txt-body sh-num">{{ form.endDay }}</text>
          </view>
        </picker>
      </view>

      <text class="txt-caption sh-muted grp">{{ $t("couponEdit.groupIssue") }}</text>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.total") }}</text>
          <input v-model="form.totalCount" maxlength="6" type="number" class="txt-body cell__input sh-num" :placeholder="$t('couponEdit.totalPh')" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted cell__k">{{ $t("couponEdit.perUser") }}</text>
          <input v-model="form.perUserLimit" maxlength="6" type="number" class="txt-body cell__input sh-num" />
        </view>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="cancel">{{ $t("couponEdit.cancel") }}</view>
          <view class="sh-btn bar__main" @tap="next">{{ $t("couponEdit.next") }}</view>
        </view>
      </sh-actionbar>
    </template>

    <!-- ============================== 确认（s14） -->
    <template v-else>
      <view class="sh-row sh-row--between prog">
        <text class="txt-body txt-bold">{{ $t("couponEdit.stepConfirm") }}</text>
        <text class="txt-caption sh-muted sh-num">{{ $t("couponEdit.progress", { i: 2 }) }}</text>
      </view>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponEdit.name") }}</text>
          <text class="txt-body">{{ preview.title }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponEdit.kind") }}</text>
          <text class="txt-body sh-num">{{ $t(`couponText.kind.${form.kind}`) }} {{ couponRule(tt, preview) }}</text>
        </view>
        <view v-if="form.kind === 'FULL_CUT' || form.kind === 'PERCENT'" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponEdit.min") }}</text>
          <text class="txt-body sh-num">{{ couponThreshold(tt, preview) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponEdit.groupValidity") }}</text>
          <text class="txt-body sh-num">{{ couponValidity(tt, preview) }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ $t("couponEdit.total") }}</text>
          <text class="txt-body sh-num">{{ couponQuantity(tt, preview) }}</text>
        </view>
      </view>

      <view class="sh-row sh-row--between spend">
        <text class="txt-body sh-muted">{{ $t("couponEdit.maxSpend") }}</text>
        <text class="txt-title sh-num">{{ maxSpend == null ? $t("couponEdit.unlimited") : money(maxSpend) }}</text>
      </view>

      <sh-actionbar>
        <view class="sh-row bar">
          <view class="sh-btn sh-btn--muted sh-fill" @tap="cancel">{{ $t("couponEdit.back") }}</view>
          <view class="sh-btn bar__main" :class="{ 'is-disabled': saving }" @tap="save">{{ $t("couponEdit.save") }}</view>
        </view>
      </sh-actionbar>
    </template>

    <sh-sheet :visible="showKind" :title="tt('couponEdit.kind')" @close="showKind = false">
      <view class="sh-cells">
        <view v-for="k in KINDS" :key="k" class="sh-cell sh-row sh-row--between" @tap="form.kind = k; showKind = false">
          <view>
            <text class="txt-body" :class="{ 'txt-primary': form.kind === k }">{{ $t(`couponText.kind.${k}`) }}</text>
            <text class="txt-caption sh-muted pick__d">{{ $t(`couponEdit.kindDesc.${k}`) }}</text>
          </view>
          <sh-icon v-if="form.kind === k" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
        </view>
      </view>
    </sh-sheet>
  </sh-scaffold>
</template>

<style scoped>
.prog {
  padding: 0 8rpx;
}
.grp {
  display: block;
  padding: 0 8rpx;
}
.cell__k {
  flex-shrink: 0;
}
.cell__input {
  flex: 1;
  text-align: end;
}
.segs {
  gap: 8rpx;
}
.seg {
  padding: 12rpx 20rpx;
}
.pick__d {
  display: block;
}
.spend {
  padding: 8rpx 8rpx 0;
}
.bar {
  gap: 16rpx;
  width: 100%;
}
.bar__main {
  flex: 2;
}
</style>
