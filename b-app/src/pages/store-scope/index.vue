<script setup lang="ts">
/**
 * 页 A「经营范围与送货」（方案 v3）：开店的两个决策，各一张卡。
 *
 * - 经营范围：主体级，全店共用；统一列表，不分组、不标行政级别；一个入口「添加范围」。
 *   改动攒到页面级吸底保存条。
 * - 送货方式：门店级；开关即点即存（它是独立端点，攒到大保存里会出现
 *   「范围存了送货没存」这种一半一半的状态）；开着的路下面一行配置摘要。
 *
 * 装修与获客拆去了页 B（pages/store）：那是日常会反复改的内容，和这两个决策不同频。
 */
import { computed, ref } from "vue";
import { onBackPress, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import { confirm } from "@ai-shop/ui/prompt";
import type {
  CommunityApply,
  DeliveryRule,
  ServiceArea,
  StoreFulfillment,
  StoreProfile,
} from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

// ---------------------------------------------------------------- 经营范围（主体级）
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
/** 打开页面时的快照，用来判「有没有改」 */
const snapshot = ref("");

/**
 * 展示用的整条门店地址 = 地图给的那截 + 商家手填的门牌号。
 *
 * 两截分开存（重选地图不会冲掉门牌号），但**给人看的时候必须是一整条** ——
 * 只显示前半截的话，商家填了门牌号却在这儿看不见，会以为没保存上。
 */
const fullAddress = computed(() =>
  [form.value.address, form.value.addressDetail].filter((x) => x && x.trim()).join(" "),
);

const areas = computed<ServiceArea[]>(() => form.value.serviceAreas ?? []);
/** 排除项是从范围里挖掉的洞，不是范围本身 */
const isExclude = (a: ServiceArea) => a.mode === "EXCLUDE";
/**
 * 生效中的**纳入**项。
 *
 * 排除项必须踢出去，它喂着两处：门店子集选择器（「本店只服务这几块」里
 * 出现一条「不送 3 幢」，选它没有任何意义），以及「范围空不空」那个判据 ——
 * 而后者关系到货看不看得见，见 emptyIsBlocking。
 */
const activeAreas = computed(() => areas.value.filter((a) => !areaPending(a) && !isExclude(a)));

/**
 * 这一条要不要等运营。**判据与后端同一句话**：小区/村、街道自助生效，区/市/省要审
 * （MerchantStoreServiceImpl#selfEffective）。
 *
 * 为什么不能只看 `status`：那是服务端回显的，**刚勾上还没保存的那几条没有** ——
 * 而那正是最需要提示的时刻：商家勾完整个市、关掉面板，以为立刻就能卖。
 */
function areaPending(a: ServiceArea) {
  if (a.status) return a.status === "PENDING";
  return a.level !== "COMMUNITY" && a.level !== "STREET";
}
const dirty = computed(() => loaded.value && JSON.stringify(areas.value) !== snapshot.value);

/**
 * 已选项的显示拆成「名字 + 路径」：name 存的是整条路径（"浙江省 / 杭州市 / 西湖区 / 阳光花园"），
 * 列表里主标题取最后一段，路径作次要文字 —— 不分组、不标级别，一眼看得出是哪里。
 */
function splitName(a: ServiceArea) {
  const parts = (a.name || a.refCode).split(" / ");
  return { main: parts[parts.length - 1] ?? a.refCode, path: parts.slice(0, -1).join(" · ") };
}
function isWhole(a: ServiceArea) {
  return a.level !== "COMMUNITY";
}

function removeArea(a: ServiceArea) {
  form.value.serviceAreas = areas.value.filter((x) => !(x.level === a.level && x.refCode === a.refCode));
}

const pickerOpen = ref(false);
function setAreas(v: ServiceArea[]) {
  form.value.serviceAreas = v;
}

// 提报进度：不显示的话商家会以为没提交成功，隔天再提一次同样的
const applies = ref<CommunityApply[]>([]);
const pendingApplies = computed(() => applies.value.filter((a) => a.status === "PENDING"));
const rejectedApplies = computed(() => applies.value.filter((a) => a.status === "REJECTED"));
// ---------------------------------------------------------------- 送货方式（门店级）
const fulfillment = ref<StoreFulfillment | null>(null);
const savingChannel = ref("");
const channelRows = computed(() => fulfillment.value?.channels ?? []);
const on = (ch: string) => channelRows.value.some((c) => c.channel === ch && c.enabled);
const pickupOn = computed(() => on("STORE_PICKUP") || on("NEIGHBOR_PICKUP"));
const deliveryOn = computed(() => on("MERCHANT_DELIVERY"));
const expressOn = computed(() => on("EXPRESS"));

/**
 * 覆盖项为空的含义**由送货方式决定**：只有自提是「谁也看不到」（拦），
 * 开了自送/快递是「不限」（正常）。判的是生效中的项 —— 待审的不参与展开。
 */
const emptyIsBlocking = computed(
  () => pickupOn.value && !deliveryOn.value && !expressOn.value && !activeAreas.value.length,
);

/**
 * 「不限」那句提示的判据是**没有纳入项**，不是「一条都没有」。
 *
 * 只写了排除的自送商家（「我上门送，就是不送 3 幢」）在后端走的正是
 * 「没框 = 不限」那个分支再减去排除（`includes.isEmpty()`）。这里若还看
 * `areas.length`，他会看到提示消失、以为自己框了一片范围 ——
 * 而实际生效的是全平台减掉那一栋，两句话差着整个平台。
 */
const noIncludes = computed(() => !areas.value.some((a) => !isExclude(a)));

async function loadFulfillment() {
  try {
    fulfillment.value = await api.mStoreFulfillment(merchant.storeNo || "default");
  } catch {
    fulfillment.value = null;
  }
}

async function persistChannels(
  next: StoreFulfillment["channels"],
  channel: string,
  pickupNos?: string[],
) {
  savingChannel.value = channel;
  try {
    fulfillment.value = await api.mSaveStoreFulfillment(merchant.storeNo || "default", {
      channels: next.map((c) => ({
        channel: c.channel,
        enabled: c.enabled,
        templateNo: c.templateNo ?? undefined,
        // 只在管理取货点时带：不带 = 不改引用
        pickupNos: pickupNos && c.channel === "NEIGHBOR_PICKUP" ? pickupNos : undefined,
      })),
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || t("store.fulfillFailed"), icon: "none" });
  } finally {
    savingChannel.value = "";
  }
}

async function toggleChannel(channel: string) {
  const cur = fulfillment.value;
  if (!cur || savingChannel.value) return;
  const row = cur.channels.find((c) => c.channel === channel);
  if (!row || row.denied) return;
  if (row.locked) {
    uni.showToast({ title: t("store.channelLocked"), icon: "none" });
    return;
  }
  const next = cur.channels.map((c) => (c.channel === channel ? { ...c, enabled: !c.enabled } : c));
  if (!next.some((c) => c.enabled)) {
    uni.showToast({ title: t("store.fulfillNone"), icon: "none" });
    return;
  }
  // 门店自取的取货地址就是门店地址：没地址先说清楚、给入口，不把请求打到后端再看一句报错
  if (channel === "STORE_PICKUP" && !row.enabled && !form.value.address) {
    if (
      await confirm({
        title: String(t("store.sumNoAddress")),
        hint: String(t("store.needAddressBody")),
        confirmText: String(t("store.goAddress")),
      })
    ) {
      goAddress();
    }
    return;
  }
  if (row.enabled) {
    // 关路前确认：列出只勾了这一路的在售商品（P1 走真清单），关掉后买家下不了单。
    // 商品不自动改 —— 动在售商品要商家自己点头
    const impacted = await api.mFulfillmentImpact(merchant.storeNo || "default", channel).catch(() => []);
    const names = impacted.slice(0, 5).map((g) => `· ${g.title}`).join("\n");
    const more = impacted.length > 5 ? "\n" + t("store.offMore", { n: impacted.length - 5 }) : "";
    const body = impacted.length
      ? t("store.offConfirmList", { n: impacted.length }) + "\n" + names + more
      : t("store.offConfirmBody");
    const ok = await confirm({ title: String(t("store.offConfirmTitle", { s: t(`channel.${channel}`) })), hint: String(body), confirmText: String(t("store.offAnyway")) });
    if (!ok) return;
  }
  await persistChannels(next, channel);
}

// 取货点（P1）：社区自提点这一路引用了哪些点；管理走弹层，保存与开关同一个 PUT
const pickupSheetOpen = ref(false);
const neighborRefs = computed(() =>
  channelRows.value.find((c) => c.channel === "NEIGHBOR_PICKUP")?.pickups ?? [],
);
const neighborSummary = computed(() => {
  const refs = neighborRefs.value;
  if (!refs.length) return "";
  const names = refs.slice(0, 2).map((r) => r.name + (r.status !== "ACTIVE" ? `（${t(`store.pickup.st${r.status}`)}）` : ""));
  return refs.length > 2 ? t("store.pickup.sumMore", { a: names.join(" · "), n: refs.length - 2 }) : names.join(" · ");
});
async function savePickups(pickupNos: string[]) {
  const cur = fulfillment.value;
  if (!cur) return;
  await persistChannels(cur.channels, "NEIGHBOR_PICKUP", pickupNos);
}

// 范围子集（P2）：自送默认送整个经营范围，可收窄到其中几项；EXPRESS 不收窄（全国）
const subsetOpen = ref(false);
const subsetAll = ref(true);
const subsetPicked = ref<string[]>([]);
function subsetSummary(c: StoreFulfillment["channels"][number]) {
  if (c.scopeMode !== "SUBSET") return t("store.subset.sumAll");
  return t("store.subset.sumOnly", { n: c.areaNos?.length ?? 0 });
}
function openSubset(c: StoreFulfillment["channels"][number]) {
  subsetAll.value = c.scopeMode !== "SUBSET";
  subsetPicked.value = [...(c.areaNos ?? [])];
  subsetOpen.value = true;
}
function toggleSubsetArea(a: ServiceArea) {
  const no = a.areaNo;
  if (!no) return;
  subsetPicked.value = subsetPicked.value.includes(no)
    ? subsetPicked.value.filter((x) => x !== no)
    : [...subsetPicked.value, no];
}
async function saveSubset(c: StoreFulfillment["channels"][number]) {
  const cur = fulfillment.value;
  if (!cur) return;
  if (!subsetAll.value && !subsetPicked.value.length) {
    uni.showToast({ title: t("store.subset.needOne"), icon: "none" });
    return;
  }
  savingChannel.value = c.channel;
  try {
    fulfillment.value = await api.mSaveStoreFulfillment(merchant.storeNo || "default", {
      channels: cur.channels.map((x) => ({
        channel: x.channel,
        enabled: x.enabled,
        templateNo: x.templateNo ?? undefined,
        scopeMode: x.channel === c.channel ? (subsetAll.value ? "ALL" : "SUBSET") : undefined,
        areaNos: x.channel === c.channel && !subsetAll.value ? subsetPicked.value : undefined,
      })),
    });
    subsetOpen.value = false;
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    savingChannel.value = "";
  }
}

// 自送费率：行内展开编辑，读写既有 deliveryRule 接口（单位分；输入按元）
const rule = ref<DeliveryRule | null>(null);
const ruleOpen = ref(false);
const ruleForm = ref({ minOrder: "", fee: "", free: "" });
const yuan = (minor: number) => (minor / 100).toString();
const toMinor = (s: string) => Math.round(Number(s || 0) * 100);

async function loadRule() {
  rule.value = await api.mDeliveryRule().catch(() => null);
}
function openRule() {
  const r = rule.value;
  ruleForm.value = {
    minOrder: r ? yuan(r.minOrderMinor) : "0",
    fee: r ? yuan(r.feeMinor) : "0",
    free: r ? yuan(r.freeThresholdMinor) : "0",
  };
  ruleOpen.value = true;
}
async function saveRule() {
  const next: DeliveryRule = {
    radius: rule.value?.radius ?? 3000,
    minOrderMinor: toMinor(ruleForm.value.minOrder),
    feeMinor: toMinor(ruleForm.value.fee),
    freeThresholdMinor: toMinor(ruleForm.value.free),
  };
  if ([next.minOrderMinor, next.feeMinor, next.freeThresholdMinor].some((n) => !Number.isFinite(n) || n < 0)) {
    uni.showToast({ title: t("store.rateInvalid"), icon: "none" });
    return;
  }
  // 免配门槛低于起送价是无意义配置，服务端也拒；端上先说人话
  if (next.freeThresholdMinor && next.freeThresholdMinor < next.minOrderMinor) {
    uni.showToast({ title: t("store.rateHint"), icon: "none" });
    return;
  }
  try {
    rule.value = await api.mSaveDeliveryRule(next);
    ruleOpen.value = false;
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
const deliverySummary = computed(() => {
  const r = rule.value;
  if (!r) return "";
  const a = money(r.minOrderMinor);
  const b = money(r.feeMinor);
  // 没设免配门槛就别硬套「满 X 免配」的句式 —— 会拼成「满 不免 免配」
  return r.freeThresholdMinor
    ? t("store.sumDelivery", { a, b, c: money(r.freeThresholdMinor) })
    : t("store.sumDeliveryNoFree", { a, b });
});

// ---------------------------------------------------------------- 加载 / 保存
async function load() {
  const [s, ap] = await Promise.allSettled([api.mStore(), api.mMyCommunityApplies()]);
  if (s.status === "fulfilled") {
    form.value = normalize(s.value);
    snapshot.value = JSON.stringify(form.value.serviceAreas ?? []);
    loaded.value = true;
  } else {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
  applies.value = ap.status === "fulfilled" ? ap.value : [];
}

function normalize(p: StoreProfile): StoreProfile {
  return { ...p, serviceAreas: p.serviceAreas ?? [] };
}

async function save() {
  if (emptyIsBlocking.value) {
    uni.showToast({ title: t("store.areaNeeded"), icon: "none" });
    return;
  }
  /*
   * 只回传这一页管的字段。旧三档 serviceScope **不能原样回传**：存量主体里还有
   * PLATFORM 这种已不在开放白名单里的值，回传就被 assertServiceScopeAllowed 拒
   * （「当前不支持这个经营范围」）——而人根本没碰过它。空 = 服务端不改。
   * fulfillmentReach 是服务端「范围为空是否合法」的判据，按开关推导一致的值。
   */
  const reach = expressOn.value ? "SHIPPING" : deliveryOn.value ? "ONSITE" : "PICKUP";
  const payload = {
    ...form.value,
    serviceScope: "",
    serviceCommunityNos: [],
    serviceCityCode: undefined,
    fulfillmentReach: reach,
  } as unknown as StoreProfile;
  try {
    form.value = normalize(await api.mSaveStore(payload));
    snapshot.value = JSON.stringify(form.value.serviceAreas ?? []);
    uni.showToast({ title: t("common.saved"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function discard() {
  form.value.serviceAreas = JSON.parse(snapshot.value || "[]");
}

/** 返回时拦未保存：丢改动要他自己点头 */
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

function goAddress() {
  uni.navigateTo({ url: ROUTES.store });
}

onShow(() => {
  void merchant.ensureStores().then(() => {
    void loadFulfillment();
  });
  void load();
  void loadRule();
});
</script>

<template>
  <sh-scaffold title-key="store.scopeTitle" :denied="!merchant.can('biz:store')">
    <biz-store-tag readonly></biz-store-tag>

    <!-- ① 经营范围（主体级） -->
    <view class="sh-card">
      <view class="head sh-row sh-row--between sh-row--baseline">
        <text class="txt-title">{{ $t("store.scope") }}</text>
        <text class="txt-caption head__sub">{{ $t("store.scopeAll") }}</text>
      </view>
      <text class="sh-hint">{{ $t("store.scopeLead") }}</text>

      <view v-if="areas.length" class="list">
        <view v-for="a in areas" :key="`${a.level}:${a.refCode}`" class="sh-row sh-row--divided item">
          <view class="sh-fill">
            <text class="txt-strong item__name" :class="{ 'txt-quiet': areaPending(a) || isExclude(a) }">
              {{ splitName(a).main }}<text v-if="isWhole(a)" class="txt-caption"> {{ $t("store.whole") }}</text>
            </text>
            <text v-if="splitName(a).path" class="txt-caption item__path">{{ splitName(a).path }}</text>
          </view>
          <!-- 排除项不标出来，「已排除 3 幢」和「已覆盖 3 幢」在这张清单上长得一模一样 -->
          <text v-if="isExclude(a)" class="sh-chip sh-chip--danger">{{ $t("store.areaExcluded") }}</text>
          <text v-else-if="areaPending(a)" class="sh-chip sh-chip--warning">{{ $t("store.areaPending") }}</text>
          <sh-icon-btn name="close" @tap="removeArea(a)"></sh-icon-btn>
        </view>
      </view>

      <!-- 空列表的含义两分：只自提是故障，开了自送/快递是「不限」。绝不能显示同一句话 -->
      <text v-if="emptyIsBlocking" class="txt-caption warn">{{ $t("store.areaNeeded") }}</text>
      <text v-else-if="noIncludes" class="sh-hint">{{ $t("store.areaUnlimited") }}</text>
      <text v-if="areas.length > activeAreas.length" class="sh-hint">{{ $t("store.areaPendingHint") }}</text>

      <view class="sh-btn sh-btn--soft add" @tap="pickerOpen = true">
        {{ $t("store.addArea") }}
      </view>

      <view v-if="pendingApplies.length || rejectedApplies.length" class="progress">
        <text v-if="pendingApplies.length" class="sh-hint">
          {{ $t("store.applyProgress", { n: pendingApplies.length }) }} · {{ pendingApplies.map((a) => a.name).join("、") }}
        </text>
        <text v-for="a in rejectedApplies" :key="a.applyNo" class="txt-caption warn">
          {{ a.name }} · {{ $t("store.applyRejected") }}{{ a.reason ? `：${a.reason}` : "" }}
        </text>
      </view>
    </view>

    <!-- ② 送货方式（门店级，即点即存） -->
    <view v-if="fulfillment" class="sh-card sh-mt-sm">
      <view class="head sh-row sh-row--between sh-row--baseline">
        <text class="txt-title">{{ $t("store.fulfillCard") }}</text>
      </view>

      <template v-for="c in channelRows" :key="c.channel">
        <view class="ch sh-row" :class="{ 'is-off': c.denied || c.locked }" @tap="toggleChannel(c.channel)">
          <view class="sh-fill">
            <text class="txt-strong ch__name">{{ $t(`channel.${c.channel}`) }}</text>
            <text class="txt-caption ch__desc" :class="{ 'is-warning': c.locked }">{{ c.locked ? $t("store.channelLocked") : c.denied ? $t("store.channelDenied") : $t(`store.channelDesc.${c.channel}`) }}</text>
          </view>
          <sh-switch
            :model-value="c.enabled"
            :disabled="savingChannel === c.channel"
          ></sh-switch>
        </view>

        <!-- 开着的路：一行配置摘要 -->
        <view v-if="c.enabled && c.channel === 'STORE_PICKUP'" class="sum sh-row sh-row--between" :class="{ 'sum--warn': !form.address }">
          <text class="txt-caption sum__t sh-fill">{{ fullAddress ? $t("store.sumPickupAddr", { s: fullAddress }) : $t("store.sumNoAddress") }}</text>
          <sh-go class="sum__go" @tap.stop="goAddress">{{ $t("store.goAddress") }}</sh-go>
        </view>
        <view v-if="c.enabled && c.channel === 'NEIGHBOR_PICKUP'" class="sum sh-row sh-row--between" :class="{ 'sum--warn': !neighborRefs.length && !form.address }">
          <text class="txt-caption sum__t sh-fill">{{ neighborSummary ? $t("store.pickup.sumRefs", { s: neighborSummary }) : (form.address ? $t("store.pickup.sumNone") : $t("store.pickup.sumNoneNoAddr")) }}</text>
          <sh-go class="sum__go" @tap.stop="pickupSheetOpen = true">{{ $t("store.pickup.manage") }}</sh-go>
        </view>
        <view v-if="c.enabled && c.channel === 'MERCHANT_DELIVERY'" class="sum sh-row sh-row--between">
          <template v-if="!ruleOpen">
            <text class="txt-caption sum__t sh-fill">{{ deliverySummary || $t("store.sumDeliveryUnset") }}</text>
            <sh-go class="sum__go" @tap.stop="openRule">{{ $t("store.edit") }}</sh-go>
          </template>
          <view v-else class="rate" @tap.stop>
            <view class="rate__grid">
              <view class="sh-fill">
                <text class="field__label">{{ $t("store.minOrder") }}</text>
                <input v-model="ruleForm.minOrder" class="field__input" type="digit" :maxlength="8" />
              </view>
              <view class="sh-fill">
                <text class="field__label">{{ $t("store.fee") }}</text>
                <input v-model="ruleForm.fee" class="field__input" type="digit" :maxlength="8" />
              </view>
              <view class="sh-fill">
                <text class="field__label">{{ $t("store.freeThreshold") }}</text>
                <input v-model="ruleForm.free" class="field__input" type="digit" :maxlength="8" />
              </view>
            </view>
            <text class="sh-hint">{{ $t("store.rateHint") }}</text>
            <view class="rate__btns">
              <text class="sh-btn sh-btn--soft rate__save" @tap="saveRule">{{ $t("store.saveRate") }}</text>
              <text class="txt-sub sh-btn sh-btn--muted rate__cancel" @tap="ruleOpen = false">{{ $t("store.collapse") }}</text>
            </view>
          </view>
        </view>
        <view v-if="c.enabled && c.channel === 'MERCHANT_DELIVERY'" class="sum sh-row sh-row--between">
          <template v-if="!subsetOpen">
            <text class="txt-caption sum__t sh-fill">{{ subsetSummary(c) }}</text>
            <sh-go class="sum__go" @tap.stop="openSubset(c)">{{ $t("store.subset.edit") }}</sh-go>
          </template>
          <view v-else class="rate" @tap.stop>
            <text class="sh-hint">{{ $t("store.subset.hint") }}</text>
            <view class="subset__opt sh-row sh-row--between" :class="{ 'is-on': subsetAll }" @tap="subsetAll = true">
              <text class="txt-sub subset__t txt-ink">{{ $t("store.subset.all") }}</text>
              <sh-icon v-if="subsetAll" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
            <view class="subset__opt sh-row sh-row--between" :class="{ 'is-on': !subsetAll }" @tap="subsetAll = false">
              <text class="txt-sub subset__t txt-ink">{{ $t("store.subset.only") }}</text>
              <sh-icon v-if="!subsetAll" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
            <view v-if="!subsetAll" class="subset__list">
              <view v-for="a in activeAreas" :key="a.areaNo || a.refCode" class="subset__row sh-row sh-row--between" @tap="toggleSubsetArea(a)">
                <text class="txt-sub subset__name sh-fill txt-ink">{{ splitName(a).main }}<text v-if="isWhole(a)" class="txt-caption"> {{ $t("store.whole") }}</text></text>
                <sh-check :model-value="subsetPicked.includes(a.areaNo || '')"></sh-check>
              </view>
              <text v-if="!activeAreas.length" class="sh-hint">{{ $t("store.subset.noAreas") }}</text>
            </view>
            <view class="rate__btns">
              <text class="sh-btn sh-btn--soft rate__save" @tap="saveSubset(c)">{{ $t("common.save") }}</text>
              <text class="txt-sub sh-btn sh-btn--muted rate__cancel" @tap="subsetOpen = false">{{ $t("store.collapse") }}</text>
            </view>
          </view>
        </view>
        <view v-if="c.enabled && c.channel === 'EXPRESS'" class="sum sh-row sh-row--between">
          <text class="txt-caption sum__t sh-fill">{{ $t("store.sumExpress") }}</text>
        </view>
      </template>
    </view>

    <biz-pickup-sheet
      v-model:visible="pickupSheetOpen"
      :store-no="merchant.storeNo || 'default'"
      :selected="neighborRefs.map((r) => r.pickupNo)"
      @done="savePickups"
    ></biz-pickup-sheet>

    <biz-region-picker
      v-model:visible="pickerOpen"
      :areas="areas"
      @update:areas="setAreas"
    ></biz-region-picker>

    <!-- 吸底保存条：范围有未保存改动时才浮现 -->
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
.head__sub {
  flex-shrink: 0;
}

.warn {
  display: block;
  margin-top: 12rpx;
  color: var(--sh-danger);
}
.list {
  margin-top: 12rpx;
}

.item__name {
  display: block;
}

.item__path {
  display: block;
  margin-top: 2rpx;
}
.add {
  margin-top: 20rpx;
}
.progress {
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: var(--sh-hairline);
}
/* 送货方式：紧凑开关行 */
.ch {
  gap: 24rpx;
  padding: 22rpx 0;
  border-bottom: var(--sh-hairline);
}
.ch.is-off {
  opacity: 0.55;
}

.ch__name {
  display: block;
}
.ch__desc {
  display: block;
  margin-top: 4rpx;
}
.switch.is-busy {
  opacity: 0.6;
}
/*
 * 开着的那一路下面的配置摘要。**与上面的开关行同构**：白底、同一条细分隔线、
 * 同样的左右结构（左说明 / 右动作）—— 此前它是一个灰底圆角胶囊，
 * 整页都是「白卡 + 行 + 分隔线」，就它一个是另一套组件，看着像别处贴过来的。
 *
 * 缩进 24rpx 表达从属关系：它说的是上面那一路的事，不是并列的第五路。
 */
.sum {
  padding: 16rpx 0 16rpx 24rpx;
  border-bottom: var(--sh-hairline);
}
/* 缺配置是**状态**不是装饰：起始侧一条竖杠 + 文字变色，不换整块底色。
   用逻辑属性而不是 border-left —— 阿语下起始侧在右，写死 left 那条杠会留在错的一边 */
.sum--warn {
  border-inline-start: 4rpx solid var(--sh-warning);
  padding-inline-start: 20rpx;
}
.sum--warn .sum__t {
  color: var(--sh-warning);
}
.subset__opt {
  padding: 14rpx 0;
  border-bottom: var(--sh-hairline);
}

.subset__list {
  margin-top: 8rpx;
}
.subset__row {
  padding: 12rpx 0;
}

/* 「去设置 / 管理 / 编辑」这类行内动作，形态与字号由 `sh-go` 给。
   **此前四处里三处在文字尾巴上挂一个 `›` 字符，第四处什么都没有** ——
   同一个类长出两种样子。这里只留「不被压缩」。 */
.sum__go {
  flex-shrink: 0;
}
.rate {
  flex: 1;
}
.rate__grid {
  display: flex;
  gap: 12rpx;
}

.rate__btns {
  display: flex;
  gap: 16rpx;
  margin-top: 12rpx;
}
.rate__save {
  flex: 1;
}
/*
 * 展开态里的两个按钮：主操作 soft、次操作 muted，**都是 .sh-btn**。
 * 此前次操作是本页自造的 `.mini`（16rpx 圆角的小灰块）—— 与旁边的胶囊按钮
 * 既不同形也不同高，并排时基线都对不齐。收窄内边距是为了不让它在一行里占太满，
 * 形状与配色仍走设计系统。
 */
.rate__cancel {
  flex-shrink: 0;
  padding: 24rpx 32rpx;
}
</style>
