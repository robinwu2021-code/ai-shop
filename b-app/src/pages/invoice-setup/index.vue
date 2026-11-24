<template>
  <sh-scaffold title-key="salesInvoice.setupTitle" :denied="!merchant.can('biz:invoice:setup')">
    <sh-empty v-if="!loaded || failed" :pending="!loaded" :failed="failed" @retry="load"></sh-empty>

    <template v-else>
      <sh-section :title="String(t('salesInvoice.profile'))" pad>
        <text class="sh-hint txt-quiet">{{ profileStatus }}</text>
      </sh-section>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.regime") }}</text>
          <text class="txt-body">{{ t("salesInvoice.regimeTW") }}</text>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.sellerTaxId") }}</text>
          <input v-model="profile.sellerTaxId" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.sellerName") }}</text>
          <input v-model="profile.sellerName" maxlength="60" class="txt-body sh-fill cell-input" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.gateway") }}</text>
          <view class="sh-row">
            <text class="sh-chip" :class="profile.gatewayCode === '' ? 'sh-chip--primary' : ''" @tap="profile.gatewayCode = ''">{{
              t("salesInvoice.gatewayDefault", { name: gatewayLabel(providers?.defaultGatewayCode ?? "") }) }}</text>
            <text v-for="g in choices" :key="g.code" class="sh-chip" :class="profile.gatewayCode === g.code ? 'sh-chip--primary' : ''"
              @tap="profile.gatewayCode = g.code">{{ gatewayLabel(g.code) }}</text>
          </view>
        </view>
        <view class="sh-cell">
          <text class="sh-hint txt-quiet">{{ t("salesInvoice.gatewayHint") }}</text>
        </view>
        <template v-if="chosen?.requiresAccount">
          <view v-if="accountField" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ credentialLabel(accountField) }}</text>
            <text v-if="accountField.derivedFrom === 'SELLER_TAX_ID'" class="txt-caption txt-quiet">{{ t("salesInvoice.credentialDerived") }}</text>
            <input v-else v-model="profile.providerAccountCode" maxlength="64" class="txt-body sh-num sh-fill cell-input" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.provider") }}</text>
            <text class="txt-caption" :class="providerCredOk ? 'is-success' : 'is-warning'">{{ providerCredOk ? t("salesInvoice.providerCredOk") : t("salesInvoice.providerCredMissing") }}</text>
          </view>
          <view v-if="keyField" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ credentialLabel(keyField) }}</text>
            <input v-model="profile.providerUser" maxlength="64" class="txt-body sh-num sh-fill cell-input"
              :placeholder="String(t('salesInvoice.providerSecretPh'))" />
          </view>
          <view v-if="secretField" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ credentialLabel(secretField) }}</text>
            <input v-model="profile.providerPassword" password maxlength="128" class="txt-body sh-num sh-fill cell-input"
              :placeholder="String(t('salesInvoice.providerSecretPh'))" />
          </view>
        </template>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.qrKey") }}</text>
          <text class="txt-caption" :class="qrKeyConfigured ? 'is-success' : 'is-warning'">{{ qrKeyConfigured ? t("salesInvoice.qrConfigured") : t("salesInvoice.qrMissing") }}</text>
        </view>
        <view class="sh-cell">
          <input v-model="profile.qrAesKeyHex" password maxlength="32" class="txt-body sh-num"
            :placeholder="String(t('salesInvoice.qrKeyPh'))" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.autoIssue") }}</text>
          <sh-switch v-model="profile.autoIssue"></sh-switch>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.rangeLowWater") }}</text>
          <input v-model="profile.rangeLowWater" type="number" maxlength="6" class="txt-body sh-num sh-fill cell-input" />
        </view>
        <view v-if="can(chosen, 'ALLOCATE_RANGE')" class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.autoFetchRanges") }}</text>
          <sh-switch v-model="profile.autoFetchRanges"></sh-switch>
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.memberCarrier") }}</text>
          <input v-model="profile.memberCarrierType" maxlength="6" class="txt-body sh-num sh-fill cell-input"
            :placeholder="String(t('salesInvoice.optional'))" />
        </view>
      </view>
      <view class="sh-card sh-mt-sm">
        <view v-if="profileErrMsg" class="sh-notice sh-notice--danger sh-mb-sm" role="alert">{{ profileErrMsg }}</view>
        <view class="sh-btn" :class="busy ? 'is-disabled' : ''" @tap="saveProfile">{{ t("salesInvoice.save") }}</view>
      </view>

      <view v-if="providerMode" class="sh-card sh-mt-sm">
        <view v-if="current?.providerHoldReason" class="sh-notice sh-notice--danger sh-mb-sm" role="alert">{{ t("salesInvoice.providerHeld", { reason: current.providerHoldReason }) }}</view>
        <text class="sh-hint txt-quiet">{{ t("salesInvoice.providerHint") }}</text>
        <view v-if="providerMsg" class="sh-notice sh-mt-sm" :class="providerFailed ? 'sh-notice--danger' : 'sh-notice--success'" role="status">{{ providerMsg }}</view>
        <view class="sh-row sh-mt-sm">
          <view v-if="can(saved, 'VERIFY_ACCOUNT')" class="sh-btn sh-btn--soft sh-fill" :class="busy ? 'is-disabled' : ''" @tap="verifyProvider">{{ t("salesInvoice.verifyProvider") }}</view>
          <view v-if="can(saved, 'ALLOCATE_RANGE')" class="sh-btn sh-fill" :class="busy ? 'is-disabled' : ''" @tap="fetchProviderRange">{{ t("salesInvoice.fetchRange") }}</view>
        </view>
      </view>

      <template v-if="can(saved, 'MANAGE_TRACKS')">
        <sh-section :title="String(t('salesInvoice.trackTitle'))" pad>
          <text class="sh-hint txt-quiet">{{ t("salesInvoice.trackHint") }}</text>
        </sh-section>
        <view class="sh-cells">
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.period") }}</text>
            <input v-model="track.periodCode" type="number" maxlength="5" class="txt-body sh-num sh-fill cell-input"
              :placeholder="String(t('salesInvoice.periodPh'))" />
          </view>
          <view v-if="tracks" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.trackModeLabel") }} · {{ tracks.periodCode }}</text>
            <text class="txt-body">{{ t(`salesInvoice.trackMode.${tracks.allocationMode}`) }}</text>
          </view>
          <view v-for="(r, i) in tracks?.tracks ?? []" :key="i" class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-num">{{ r.track }} {{ r.startNo }}–{{ r.endNo }}</text>
            <text class="txt-caption txt-quiet">{{ r.invoiceType ?? "" }}</text>
          </view>
          <view v-if="tracks && !tracks.tracks.length" class="sh-cell">
            <text class="sh-hint txt-quiet">{{ t("salesInvoice.trackEmpty") }}</text>
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.invoiceType") }}</text>
            <view class="sh-row">
              <text v-for="ty in INVOICE_TYPES" :key="ty" class="sh-chip" :class="track.invoiceType === ty ? 'sh-chip--primary' : ''"
                @tap="track.invoiceType = ty">{{ ty }}</text>
            </view>
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.track") }}</text>
            <input v-model="track.track" maxlength="2" class="txt-body sh-num sh-fill cell-input" placeholder="AB" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.startNo") }}</text>
            <input v-model="track.startNo" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
          </view>
          <view class="sh-cell sh-row sh-row--between">
            <text class="txt-body sh-muted">{{ t("salesInvoice.endNo") }}</text>
            <input v-model="track.endNo" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
          </view>
        </view>
        <view class="sh-card sh-mt-sm">
          <view v-if="trackMsg" class="sh-notice sh-mb-sm" :class="trackFailed ? 'sh-notice--danger' : 'sh-notice--success'" role="status">{{ trackMsg }}</view>
          <view class="sh-row">
            <view class="sh-btn sh-btn--soft sh-fill" :class="busy ? 'is-disabled' : ''" @tap="queryTracks">{{ t("salesInvoice.trackQuery") }}</view>
            <view class="sh-btn sh-fill" :class="busy ? 'is-disabled' : ''" @tap="uploadTracks">{{ t("salesInvoice.trackUpload") }}</view>
          </view>
        </view>
      </template>

      <sh-section :title="String(t('salesInvoice.ranges'))" pad></sh-section>
      <view v-if="current?.currentPeriod" class="sh-notice" :class="rangeTone" role="status">
        <text>{{ t("salesInvoice.rangeCurrent", { period: current.currentPeriod, n: current.currentRemaining ?? 0 }) }}</text>
        <text v-if="current.nextPeriod"> {{ t("salesInvoice.rangeNext", { period: current.nextPeriod, n: current.nextRemaining ?? 0 }) }}</text>
      </view>
      <sh-empty v-if="!ranges.length" compact :text="String(t('salesInvoice.rangesEmpty'))"></sh-empty>
      <view v-for="r in ranges" :key="r.rangeNo" class="sh-card">
        <view class="sh-row sh-row--between">
          <text class="txt-strong sh-num">{{ r.periodCode }} · {{ r.track }} {{ r.startNo }}–{{ r.endNo }}</text>
          <text class="sh-chip" :class="r.status === 'ACTIVE' ? 'sh-chip--primary' : ''">{{ t(`salesInvoice.rangeStatus.${r.status}`) }}</text>
        </view>
        <view class="sh-row sh-row--between sh-mt-xs">
          <text class="txt-caption txt-quiet sh-num">{{ r.storeNo === "*" ? t("salesInvoice.rangeShared") : r.storeNo }}</text>
          <text class="txt-caption txt-quiet">{{ t("salesInvoice.remaining", { n: r.remaining }) }}</text>
        </view>
        <text class="txt-caption txt-quiet">{{ t(`salesInvoice.rangeOwner.${r.allocationOwner ?? "MANUAL"}`) }}</text>
      </view>

      <sh-section :title="String(t('salesInvoice.addRange'))" pad></sh-section>
      <view class="sh-cells">
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.period") }}</text>
          <input v-model="range.periodCode" type="number" maxlength="5" class="txt-body sh-num sh-fill cell-input"
            :placeholder="String(t('salesInvoice.periodPh'))" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.track") }}</text>
          <input v-model="range.track" maxlength="2" class="txt-body sh-num sh-fill cell-input" placeholder="AB" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.startNo") }}</text>
          <input v-model="range.startNo" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.endNo") }}</text>
          <input v-model="range.endNo" type="number" maxlength="8" class="txt-body sh-num sh-fill cell-input" />
        </view>
        <view class="sh-cell sh-row sh-row--between">
          <text class="txt-body sh-muted">{{ t("salesInvoice.rangeStore") }}</text>
          <input v-model="range.storeNo" maxlength="32" class="txt-body sh-num sh-fill cell-input"
            :placeholder="String(t('salesInvoice.rangeShared'))" />
        </view>
      </view>
      <view class="sh-card sh-mt-sm">
        <view v-if="rangeErrMsg" class="sh-notice sh-notice--danger sh-mb-sm" role="alert">{{ rangeErrMsg }}</view>
        <view class="sh-btn" :class="busy ? 'is-disabled' : ''" @tap="addRange">{{ t("salesInvoice.addRange") }}</view>
      </view>
    </template>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 发票设置：开通税制（首期 TW_EGUI）与登记字轨号段。主体级配置，仅 biz:invoice:setup。
// QR 加密密钥只写不读：页面只显示「已配置 / 未配置」，输入框留空表示不改。
// 供应商（TDD-发票模块 §20）：可选列表由后端按市场给出，默认「跟随平台默认」（gatewayCode 传空串），平台换供应商时自动跟随；
// 迁移来的商户可选正在用的那家。账号输入、核验、领号段按所选供应商的能力出现，不认具体厂商；账号密码只写不读。
// 服务商字轨（TDD-发票-加值中心模板-鲸跃与汎宇 §4）：已保存的供应商有字轨能力（汎宇）才出现；上传只针对下一期。
import { computed, reactive, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { SiCapability, SiCredentialField, SiCredentialSlot, SiProfile, SiProviderOption, SiProviderOptions,
  SiProviderTracks, SiRange } from "@/api/sales-invoice";

const { t, te } = useI18n();
const merchant = useMerchantStore();
const loaded = ref(false);
const failed = ref(false);
const busy = ref(false);
const profileErrMsg = ref("");
const rangeErrMsg = ref("");
const providerMsg = ref("");
const providerFailed = ref(false);
const current = ref<SiProfile | null>(null);
const ranges = ref<SiRange[]>([]);
const providers = ref<SiProviderOptions | null>(null);
/** gatewayCode 空串 = 跟随平台默认 */
const profile = reactive({ sellerTaxId: "", sellerName: "", gatewayCode: "", qrAesKeyHex: "", memberCarrierType: "", autoIssue: false,
  providerAccountCode: "", providerUser: "", providerPassword: "", rangeLowWater: "50", autoFetchRanges: false });
const range = reactive({ periodCode: "", track: "", startNo: "", endNo: "", storeNo: "" });
/** 服务商字轨（仅 trackManageable 的供应商）：查询用期别（空 = 当期）；上传固定为下一期 */
const INVOICE_TYPES = ["07", "08"] as const;
const track = reactive<{ periodCode: string; invoiceType: (typeof INVOICE_TYPES)[number]; track: string; startNo: string; endNo: string }>(
  { periodCode: "", invoiceType: "07", track: "", startNo: "", endNo: "" });
const tracks = ref<SiProviderTracks | null>(null);
const trackMsg = ref("");
const trackFailed = ref(false);

/** 本期用尽标红、低于提醒值标黄 */
const rangeTone = computed(() => {
  const p = current.value;
  if (!p) return "";
  const left = p.currentRemaining ?? 0;
  if (left <= 0) return "sh-notice--danger";
  return left < (p.rangeLowWater ?? 0) ? "sh-notice--warning" : "sh-notice--muted";
});
const qrKeyConfigured = computed(() => !!current.value?.qrKeyConfigured);
const providerCredOk = computed(() => !!current.value?.providerCredentialConfigured);
/** 商家可点选的供应商（默认那家由「跟随平台默认」代表） */
const choices = computed(() => (providers.value?.options ?? []).filter((o) => !o.isDefault));
function option(code: string | undefined) {
  return providers.value?.options.find((o) => o.code === code) ?? null;
}
/** 页面上选中的（未保存）：决定是否出现账号输入、自动领号 */
const chosen = computed(() => option(profile.gatewayCode || providers.value?.defaultGatewayCode));
/** 已保存、当前生效的：未保存的切换不能拿去核验 / 领号 */
const saved = computed(() => option(current.value?.gatewayCode));
const providerMode = computed(() => !!saved.value?.requiresAccount
  && (can(saved.value, "VERIFY_ACCOUNT") || can(saved.value, "ALLOCATE_RANGE")));
/** 能力以后端给的能力集合为准（TDD-发票-供应商能力定义完善 D1），不认厂商 */
function can(o: SiProviderOption | null | undefined, capability: SiCapability) {
  return !!o?.capabilities?.includes(capability);
}
/** 凭据字段按所选供应商的声明渲染（D3）：标签先找该供应商的词条，没有用通用词条 */
function credentialOf(slot: SiCredentialSlot) {
  return chosen.value?.credentials?.find((f) => f.slot === slot) ?? null;
}
const accountField = computed(() => credentialOf("ACCOUNT_CODE"));
const keyField = computed(() => credentialOf("KEY_ID"));
const secretField = computed(() => credentialOf("SECRET"));
const GENERIC_LABEL: Record<SiCredentialSlot, string> = {
  ACCOUNT_CODE: "salesInvoice.providerAccountCode", KEY_ID: "salesInvoice.providerUser", SECRET: "salesInvoice.providerPassword",
};
function credentialLabel(f: SiCredentialField) {
  const key = `salesInvoice.credential.${chosen.value?.code ?? ""}.${f.labelKey}`;
  return te(key) ? String(t(key)) : String(t(GENERIC_LABEL[f.slot]));
}
function gatewayLabel(code: string) {
  const key = `salesInvoice.gatewayName.${code}`;
  return te(key) ? String(t(key)) : code;
}
const profileStatus = computed(() => current.value
  ? String(t(`salesInvoice.profileStatus.${current.value.status}`))
  : String(t("salesInvoice.notOpened")));

function message(e: unknown): string {
  return e instanceof Error && e.message ? e.message : String(t("salesInvoice.failed"));
}

function fill(p: SiProfile | null) {
  current.value = p;
  profile.sellerTaxId = p?.sellerTaxId ?? "";
  profile.sellerName = p?.sellerName ?? "";
  profile.gatewayCode = !p || p.gatewaySource === "DEFAULT" ? "" : p.gatewayCode;
  profile.memberCarrierType = p?.memberCarrierType ?? "";
  profile.qrAesKeyHex = "";
  profile.autoIssue = !!p?.autoIssue;
  profile.providerAccountCode = p?.providerAccountCode ?? "";
  profile.providerUser = "";
  profile.providerPassword = "";
  profile.rangeLowWater = String(p?.rangeLowWater ?? 50);
  profile.autoFetchRanges = !!p?.autoFetchRanges;
}

async function load() {
  failed.value = false;
  try {
    const [p, r, g] = await Promise.all([api.mSiProfile(), api.mSiRanges({}), api.mSiProviders({})]);
    providers.value = g;
    fill(p);
    ranges.value = r;
  } catch {
    failed.value = true;
  } finally {
    loaded.value = true;
  }
}

async function saveProfile() {
  if (busy.value) return;
  if (!profile.sellerTaxId.trim() || !profile.sellerName.trim()) {
    profileErrMsg.value = String(t("salesInvoice.required"));
    return;
  }
  const user = profile.providerUser.trim(), password = profile.providerPassword;
  if (!!user !== !!password) {
    profileErrMsg.value = String(t("salesInvoice.required"));
    return;
  }
  busy.value = true;
  profileErrMsg.value = "";
  try {
    const key = profile.qrAesKeyHex.trim();
    const carrier = profile.memberCarrierType.trim();
    const accountCode = profile.providerAccountCode.trim();
    const lowWaterText = String(profile.rangeLowWater).trim();
    const lowWater = lowWaterText === "" ? null : Number(lowWaterText);
    fill(await api.mSiSaveProfile({
      regimeCode: "TW_EGUI",
      sellerTaxId: profile.sellerTaxId.trim(),
      sellerName: profile.sellerName.trim(),
      gatewayCode: profile.gatewayCode,
      autoIssue: profile.autoIssue,
      ...(lowWater !== null ? { rangeLowWater: lowWater } : {}),
      autoFetchRanges: can(chosen.value, "ALLOCATE_RANGE") && profile.autoFetchRanges,
      ...(key ? { qrAesKeyHex: key } : {}),
      ...(carrier ? { memberCarrierType: carrier } : {}),
      // 由卖方统编带出的代号不送：后端按声明填，避免改了统编后旧代号对不上
      ...(accountCode && accountField.value?.derivedFrom !== "SELLER_TAX_ID" ? { providerAccountCode: accountCode } : {}),
      ...(user ? { providerUser: user, providerPassword: password } : {}),
    }));
    uni.showToast({ title: String(t("salesInvoice.saved")), icon: "none" });
  } catch (e) {
    profileErrMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function addRange() {
  if (busy.value) return;
  const startNo = Number(range.startNo), endNo = Number(range.endNo);
  if (!range.periodCode.trim() || !range.track.trim() || !range.startNo || !range.endNo) {
    rangeErrMsg.value = String(t("salesInvoice.required"));
    return;
  }
  busy.value = true;
  rangeErrMsg.value = "";
  try {
    const store = range.storeNo.trim();
    const added = await api.mSiAddRange({
      regimeCode: "TW_EGUI",
      periodCode: range.periodCode.trim(),
      track: range.track.trim().toUpperCase(),
      startNo,
      endNo,
      ...(store ? { storeNo: store } : {}),
    });
    ranges.value = [added, ...ranges.value];
    Object.assign(range, { track: "", startNo: "", endNo: "", storeNo: "" });
    uni.showToast({ title: String(t("salesInvoice.saved")), icon: "none" });
  } catch (e) {
    rangeErrMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function verifyProvider() {
  if (busy.value) return;
  busy.value = true;
  providerMsg.value = "";
  try {
    const v = await api.mSiVerifyProvider();
    const name = v.name ?? v.taxId;
    providerFailed.value = false;
    providerMsg.value = String(v.qrKeyStored ? t("salesInvoice.verifiedQr", { name }) : t("salesInvoice.verified", { name }));
    if (current.value) current.value = { ...current.value, providerHoldReason: null, providerHeldAt: null,
      ...(v.qrKeyStored ? { qrKeyConfigured: true } : {}) };
  } catch (e) {
    providerFailed.value = true;
    providerMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function fetchProviderRange() {
  if (busy.value) return;
  busy.value = true;
  providerMsg.value = "";
  try {
    const period = range.periodCode.trim();
    const got = await api.mSiFetchProviderRange(period ? { periodCode: period } : {});
    ranges.value = [got, ...ranges.value.filter((r) => r.rangeNo !== got.rangeNo)];
    current.value = await api.mSiProfile();   // 刷新本期 / 下期余量
    providerFailed.value = false;
    providerMsg.value = String(t("salesInvoice.fetched", { track: got.track, start: got.startNo, end: got.endNo }));
  } catch (e) {
    providerFailed.value = true;
    providerMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function queryTracks() {
  if (busy.value) return;
  busy.value = true;
  trackMsg.value = "";
  try {
    const period = track.periodCode.trim();
    tracks.value = await api.mSiProviderTracks(period ? { periodCode: period } : {});
  } catch (e) {
    trackFailed.value = true;
    trackMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

async function uploadTracks() {
  if (busy.value) return;
  const next = current.value?.nextPeriod;
  if (!next || !track.track.trim() || track.startNo === "" || track.endNo === "") {
    trackFailed.value = true;
    trackMsg.value = String(t("salesInvoice.required"));
    return;
  }
  busy.value = true;
  trackMsg.value = "";
  try {
    const got = await api.mSiUploadProviderTracks({
      periodCode: next,
      invoiceType: track.invoiceType,
      track: track.track.trim().toUpperCase(),
      ranges: [{ startNo: Number(track.startNo), endNo: Number(track.endNo) }],
    });
    trackFailed.value = false;
    trackMsg.value = String(t("salesInvoice.trackUploaded", { track: got.track, books: got.books }));
    Object.assign(track, { track: "", startNo: "", endNo: "" });
  } catch (e) {
    trackFailed.value = true;
    trackMsg.value = message(e);
  } finally {
    busy.value = false;
  }
}

onShow(load);
</script>

<style scoped>
.cell-input {
  text-align: end;
}
</style>
