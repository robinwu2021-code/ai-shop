<script setup lang="ts">
// 我的资质 —— 传证、看有效期、看这张证能解锁哪几类。
//
// **此前商家侧没有任何入口**：只有入驻申请那一步能传（而线上入驻申请 0 条，
// 商家都是运营直接建的），传完也看不到。于是「上架被拒 → 去哪补证」这条路在 B 端是断的：
// 他看到「你还没有该授权」，然后没有下一步 —— 线上 mch_qualification 0 条、
// 所有商家 category_codes 全 NULL，就是这么来的。
//
// 这一页要说清楚三件事，缺一件它就变成一个传完没反应的上传框：
//   1. 我传过哪些证、有没有过期
//   2. 我现在能卖哪几类（已授权），还差哪几类
//   3. **传了 ≠ 解锁了** —— 授权是平台看过证之后的动作
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { pickImages } from "@shared/ports/media";
import type { AuthCodeInfo, MyQualifications, QualificationType } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/**
 * 看/传**哪张证照**的证件。从证照详情页（多证照）进来时带上，其余情况为空。
 *
 * <p>空 = 当前证照，与多证照之前一模一样 —— 存量单证照账号永远走这一支。
 * 传了别人的证照号后端直接 403，不会静默落到当前这张。
 */
const entityNo = ref("");
const data = ref<MyQualifications | null>(null);
const loading = ref(false);
const uploading = ref(false);

/**
 * 可传的证件类型。**与后端 QualificationType 同值域** ——
 * 多一个少一个都会让那张证在上架校验里对不上任何门槛。
 */
const TYPES: { type: QualificationType; labelKey: string }[] = [
  { type: "BUSINESS_LICENSE", labelKey: "qual.typeLicense" },
  { type: "FOOD_PERMIT", labelKey: "qual.typeFoodPermit" },
  { type: "FOOD_WORKSHOP", labelKey: "qual.typeWorkshop" },
  { type: "OTHER", labelKey: "qual.typeOther" },
];

const form = ref<{ qualType: QualificationType; qualName: string; qualNumber: string; imageUrl: string; expireAt: string } | null>(null);

async function load() {
  loading.value = true;
  try {
    data.value = await api.mQualifications(entityNo.value || undefined);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

/** 已解锁的类目码 —— 用来把码字典分成「能卖」与「还不能卖」两段 */
const granted = computed(() => new Set(data.value?.grantedCodes ?? []));

/**
 * 还不能卖的那几类。**只列真的需要证的** ——
 * 无门槛的码（日用百货、家政）本来就人人能卖，摆在「还差」里只会吓人。
 */
const locked = computed<AuthCodeInfo[]>(() =>
  (data.value?.catalog ?? []).filter((c) => !granted.value.has(c.code) && !!c.qualType),
);
const unlocked = computed<AuthCodeInfo[]>(() =>
  (data.value?.catalog ?? []).filter((c) => granted.value.has(c.code)),
);

/** 这一类要的证，我传过没有 —— 传过但还没授权时，界面上要给的是「等平台核」而不是「去传证」 */
function submitted(c: AuthCodeInfo) {
  return (data.value?.items ?? []).some((q) => q.qualType === c.qualType && q.status === "VALID");
}

function expiryText(at?: number | null) {
  if (!at) return t("qual.longTerm");
  const days = Math.ceil((at - Date.now()) / 86400000);
  if (days < 0) return t("qual.expired");
  if (days <= 30) return t("qual.expiringIn", { n: days });
  return new Date(at).toISOString().slice(0, 10);
}

function startAdd(type: QualificationType) {
  const preset = TYPES.find((x) => x.type === type);
  form.value = {
    qualType: type,
    qualName: preset ? String(t(preset.labelKey)) : "",
    qualNumber: "",
    imageUrl: "",
    expireAt: "",
  };
}

async function pickPhoto() {
  if (!form.value) return;
  uploading.value = true;
  try {
    const urls = await pickImages(1, ["album", "camera"]);
    if (urls[0]) form.value.imageUrl = urls[0].tempPath;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  const f = form.value;
  if (!f || !f.qualName.trim()) return;
  try {
    await api.mSaveQualification({
      qualType: f.qualType,
      qualName: f.qualName.trim(),
      qualNumber: f.qualNumber.trim() || undefined,
      imageUrl: f.imageUrl || undefined,
      // 空 = 长期有效。不要拿 0 或一个很大的数字冒充：过期扫描会把前者当成已过期
      expireAt: f.expireAt ? Date.parse(f.expireAt) : null,
      // 与读同一张证照 —— 少了它会「看的是第二张、传到第一张上」，而两边都不报错
      ...(entityNo.value ? { entityNo: entityNo.value } : {}),
    });
    form.value = null;
    uni.showToast({ title: t("qual.submitted"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onLoad((q) => {
  entityNo.value = q?.entityNo ?? "";
});
onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="qual.title" :denied="!merchant.can('biz:store')">
    <text class="sh-muted intro">{{ $t("qual.intro") }}</text>

    <!-- 已传的证 -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("qual.mine") }}</text>
      <sh-empty v-if="!loading && !data?.items.length" :text='$t("qual.emptyMine")'></sh-empty>
      <view v-for="q in data?.items ?? []" :key="q.qualNo" class="row">
        <view class="row__main">
          <text class="row__name">{{ q.qualName }}</text>
          <text class="sh-muted row__no">{{ q.qualNumber || "—" }}</text>
        </view>
        <!-- 有效期贴在右边：过期的证与没传是同一个后果（上架被拒），要一眼看得见 -->
        <text class="row__exp" :class="{ 'is-bad': q.status !== 'VALID' }">
          {{ expiryText(q.expireAt) }}
        </text>
      </view>

      <view class="adds">
        <text v-for="x in TYPES" :key="x.type" class="sh-chip" @tap="startAdd(x.type)">
          ＋ {{ $t(x.labelKey) }}
        </text>
      </view>
    </view>

    <!-- 已解锁 -->
    <view v-if="unlocked.length" class="sh-card mt">
      <text class="sh-h2">{{ $t("qual.unlocked") }}</text>
      <view class="cats">
        <text v-for="c in unlocked" :key="c.code" class="sh-chip sh-chip--primary">
          {{ (c.categoryNames ?? []).length ? (c.categoryNames ?? []).join("、") : c.name }}
        </text>
      </view>
    </view>

    <!--
      还不能卖的：这一段是这一页存在的理由。
      对每一类说清「要哪张证」与「现在轮到谁动」—— 传过了是等平台核，没传是去传。
    -->
    <view v-if="locked.length" class="sh-card mt">
      <!--
        闸门关着的时候这一段的**语气要跟着变**：它此刻描述的不是「卖不了」，
        而是「还没授权」。照旧说「还不能卖」是在制造一个不存在的障碍 ——
        商家会以为要先等平台核完才能上架，而他其实现在就能上。
      -->
      <text class="sh-h2">{{ $t(merchant.categoryGateEnforced ? "qual.locked" : "qual.notGranted") }}</text>
      <view v-for="c in locked" :key="c.code" class="lock">
        <view class="lock__main">
          <text class="lock__cats">
            {{ (c.categoryNames ?? []).length ? (c.categoryNames ?? []).join("、") : c.name }}
          </text>
          <text class="sh-muted lock__need">{{ c.requiredQualification }}</text>
        </view>
        <text v-if="submitted(c)" class="lock__wait">{{ $t("qual.waiting") }}</text>
        <text v-else class="link" @tap="startAdd((c.qualType ?? 'OTHER') as QualificationType)">
          {{ $t("qual.goUpload") }}
        </text>
      </view>
      <text class="sh-muted hint">
        {{ $t(merchant.categoryGateEnforced ? "qual.lockedHint" : "qual.notGrantedHint") }}
      </text>
    </view>

    <!-- 上传表单 -->
    <view v-if="form" class="sh-card mt">
      <text class="sh-h2">{{ $t("qual.add") }}</text>
      <view class="kv">
        <text class="kv__k">{{ $t("qual.fieldName") }}</text>
        <input v-model="form.qualName" class="field__input" />
      </view>
      <view class="kv">
        <text class="kv__k">{{ $t("qual.fieldNumber") }}</text>
        <input v-model="form.qualNumber" class="field__input" />
      </view>
      <view class="kv">
        <text class="kv__k">{{ $t("qual.fieldExpire") }}</text>
        <input v-model="form.expireAt" class="field__input" placeholder="2027-12-31" />
      </view>
      <text class="sh-muted hint">{{ $t("qual.expireHint") }}</text>
      <view class="kv kv--top">
        <text class="kv__k">{{ $t("qual.fieldPhoto") }}</text>
        <view class="photo" @tap="pickPhoto">
          <sh-cover v-if="form.imageUrl" class="photo__img" :src="form.imageUrl"></sh-cover>
          <text v-else class="photo__plus">{{ uploading ? "…" : "＋" }}</text>
        </view>
      </view>
      <view class="acts">
        <view class="sh-btn sh-btn--muted" @tap="form = null">{{ $t("common.cancel") }}</view>
        <view class="sh-btn" @tap="submit">{{ $t("qual.submit") }}</view>
      </view>
      <text class="sh-muted hint">{{ $t("qual.submitHint") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.intro {
  display: block;
  padding: 0 8rpx 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.mt {
  margin-top: 16rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 2rpx solid var(--sh-line);
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__no {
  font-size: 24rpx;
}
.row__exp {
  font-size: 24rpx;
  color: var(--sh-sub);
  flex: none;
}
/* 过期与撤销：与「没传」是同一个后果，用危险色而不是灰 */
.row__exp.is-bad {
  color: var(--sh-danger);
  font-weight: 600;
}
.adds {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 20rpx;
}
.cats {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.lock {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 14rpx 0;
}
.lock__main {
  flex: 1;
  min-width: 0;
}
.lock__cats {
  display: block;
  font-size: 26rpx;
  color: var(--sh-ink);
}
.lock__need {
  font-size: 24rpx;
}
/* 等平台核：不是错误也不是可点的动作，所以既不用危险色也不做成链接 */
.lock__wait {
  font-size: 24rpx;
  color: var(--sh-warning);
  flex: none;
}
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.5;
}
.kv {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 16rpx;
}
.kv--top {
  align-items: flex-start;
}
.kv__k {
  flex: 0 0 160rpx;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.field__input {
  flex: 1;
  min-width: 0;
  height: 76rpx;
  padding: 0 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.photo {
  width: 160rpx;
  height: 112rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  display: flex;
  align-items: center;
  justify-content: center;
}
.photo__img {
  width: 160rpx;
  height: 112rpx;
  border-radius: 16rpx;
}
.photo__plus {
  font-size: 40rpx;
  color: var(--sh-sub);
}
.acts {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
.acts .sh-btn {
  flex: 1;
}
</style>
