<script setup lang="ts">
/**
 * 页 A「经营范围与送货」（方案 v3）：开店的两个决策，各一张卡。
 *
 * - 经营范围：**门店级**（2026-10-08 起，V381）——每家店各有各的范围，改这家店不影响别家；
 *   此前是主体级、全店共用，店主改一家其他店全跟着变，跨城多店根本不成立。
 *   统一列表，不分组、不标行政级别；一个入口「添加范围」。读写都跟着当前门店（X-Store-No）。
 *   改动攒到页面级吸底保存条。
 * - 送货方式：门店级；开关即点即存（它是独立端点，攒到大保存里会出现
 *   「范围存了送货没存」这种一半一半的状态）；开着的路下面一行配置摘要。
 *
 * 装修与获客拆去了页 B（pages/store）：那是日常会反复改的内容，和这两个决策不同频。
 */
import { computed, onUnmounted, ref, watch } from "vue";
import { onBackPress, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { AREA_LEVEL, FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import { includedAreas } from "@shared/utils/coverage";
import type { ScopePreview } from "@shared/types";
import type { StorePaySetting } from "@/api/contract";
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

// ---------------------------------------------------------------- 经营范围（门店级，跟着当前门店）
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
/** 排除项是从范围里挖掉的洞，不是范围本身。判据与工作台共用一份，见 @shared/utils/coverage */
const isExclude = (a: ServiceArea) => !includedAreas([a]).length;
/**
 * 生效中的**纳入**项。
 *
 * 排除项必须踢出去，它喂着两处：门店子集选择器（「本店只服务这几块」里
 * 出现一条「不送 3 幢」，选它没有任何意义），以及「范围空不空」那个判据 ——
 * 而后者关系到货看不看得见，见 emptyIsBlocking。
 */
const activeAreas = computed(() => areas.value.filter((a) => !areaPending(a) && !isExclude(a)));

/**
 * 这一条要不要等运营。只读服务端回显的 `status === PENDING`。
 *
 * 2026-08-24 起所有粒度自选即生效（MerchantStoreServiceImpl#replaceAreas 一律写 ACTIVE），
 * 选择器那边当天就改成只读 status（biz-region-picker 的 areaPending），这边漏了：
 * 新勾的省/市/区和排除项被说成「整区、整市需运营审核」。留着读 PENDING 是为了兼容审核闸拿掉之前的存量待审记录。
 */
function areaPending(a: ServiceArea) {
  return a.status === "PENDING";
}
const dirty = computed(() => loaded.value && JSON.stringify(areas.value) !== snapshot.value);

/**
 * 已选项的显示拆成「名字 + 路径」：name 存的是整条路径（"浙江省 / 杭州市 / 西湖区 / 阳光花园"），
 * 列表里主标题取最后一段，路径作次要文字 —— 不分组、不标级别，一眼看得出是哪里。
 */
function splitName(a: ServiceArea) {
  /*
   * POLYGON 与 UNLIMITED **没有地名**（ADR-034）：前者的 refCode 是服务端算的几何指纹、
   * 后者恒为 `*`。照原来的 `a.name || a.refCode` 会把一串 32 位十六进制或一个星号
   * 当成地名显示给商家 —— 他根本不知道那是什么。
   */
  if (a.level === AREA_LEVEL.UNLIMITED) {
    return { main: t("store.unlimitedArea"), path: "" };
  }
  if (a.level === AREA_LEVEL.POLYGON) {
    const n = polygonVertexCount(a);
    return { main: n ? t("store.polygonArea", { n }) : t("store.polygonAreaBroken"), path: "" };
  }
  const parts = (a.name || a.refCode).split(" / ");
  return { main: parts[parts.length - 1] ?? a.refCode, path: parts.slice(0, -1).join(" · ") };
}
function isWhole(a: ServiceArea) {
  // 「整个」是给区划级加的后缀（「西湖区 整个」）。多边形与不限不是区划，不加
  return a.level !== "COMMUNITY" && a.level !== AREA_LEVEL.POLYGON && a.level !== AREA_LEVEL.UNLIMITED;
}

function removeArea(a: ServiceArea) {
  form.value.serviceAreas = areas.value.filter((x) => !(x.level === a.level && x.refCode === a.refCode));
}

const pickerOpen = ref(false);
function setAreas(v: ServiceArea[]) {
  form.value.serviceAreas = v;
}

/*
 * 画图页画完回传顶点。走事件而不是 URL 参数：顶点串可能上千字符，塞 query 会被截断，
 * 而截断后的 JSON 解析失败只会表现成「画了半天保存上去是空的」。
 */
uni.$on("store-scope:polygon", (geometry: string) => {
  if (!geometry) return;
  form.value.serviceAreas = [
    ...areas.value,
    { level: AREA_LEVEL.POLYGON, refCode: "", name: "", mode: "INCLUDE", geometry } as ServiceArea,
  ];
});
onUnmounted(() => uni.$off("store-scope:polygon"));
/** 用文字填（TDD-经营范围文字录入）：识别结果写进清单的未保存态，预览与保存条照常出现 */
const textOpen = ref(false);
const storeNear = computed(() =>
  form.value.latE6 != null && form.value.lngE6 != null ? { latE6: form.value.latE6, lngE6: form.value.lngE6 } : null);

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

/** 显式的「全平台不限」项（ADR-034）。它只对快递/自送生效 —— 自提没有落点 */
const unlimitedArea = computed(() => areas.value.find((a) => a.level === AREA_LEVEL.UNLIMITED && !isExclude(a)));
const unlimitedOn = computed(() => !!unlimitedArea.value);

/**
 * 覆盖项为空 = **谁也看不到**，与送货方式无关（ADR-034）。
 *
 * 此前这里按送货方式分两种含义：只自提是故障、开了自送/快递是「不限」。
 * 那条隐式规则已经删掉 —— 「没框范围」有四种成因（框写到别家店、没物化、框成排除、
 * 门店级错位），任何一种都会让商家在不知情的情况下铺满全平台。现在「不限」必须显式勾。
 *
 * 跟着改这一句是必须的：不改的话，开着快递而范围为空的商家会在这一页看到
 * 「不限地区」，而实际是谁也看不到 —— **页面方向正好说反**，而他永远查不出来。
 */
const emptyIsBlocking = computed(() => !activeAreas.value.length);

/** 「不限」这一条对当前送货方式有没有意义：只开自提时它不生效，要提醒 */
const unlimitedIdle = computed(() => unlimitedOn.value && !deliveryOn.value && !expressOn.value);

/** 多边形那一条的顶点数（显示用）。几何坏了就不显示数字，不猜 */
function polygonVertexCount(a: ServiceArea) {
  if (!a.geometry) return 0;
  try {
    const pts = JSON.parse(a.geometry) as unknown[];
    return Array.isArray(pts) ? pts.length : 0;
  } catch {
    return 0;
  }
}

/** 切换「全平台不限」。开 = 加一条范围项，关 = 删掉它 —— 它就是一条范围，不是开关位 */
function toggleUnlimited() {
  if (unlimitedOn.value) {
    form.value.serviceAreas = areas.value.filter((a) => a.level !== AREA_LEVEL.UNLIMITED);
    return;
  }
  form.value.serviceAreas = [
    ...areas.value,
    { level: AREA_LEVEL.UNLIMITED, refCode: "*", name: "", mode: "INCLUDE" } as ServiceArea,
  ];
}

/** 去画配送范围。独立页承载原生地图（放弹层会把子树打掉），回来时监听事件收顶点 */
function openPolygonDraw() {
  uni.navigateTo({ url: "/pages/store-scope-polygon/index" });
}

/**
 * 选择器的「不限态」：任意一行都能单独打排除（「我全平台送，就是不送 3 幢」）。
 *
 * 判据从「没有纳入项 + 开了自送/快递」换成「有显式 UNLIMITED 项 + 开了自送/快递」（ADR-034）——
 * 后端那条「没框 = 不限再减排除」的隐式分支已经删了。不跟着换的话，范围为空的商家
 * 会拿到一个「可以到处打排除」的界面，而他其实谁也看不到，排除谁都没有意义。
 */
const unlimitedPickerMode = computed(() => unlimitedOn.value && (deliveryOn.value || expressOn.value));

/**
 * 范围预览：**保存之前**问一次「改成这样会覆盖到哪儿、那儿有多少买家」。
 *
 * <p>此前商家勾完只能看到自己勾了几条 —— 而他真正想知道的是「这几条到底盖住多少地方」。
 * 「框了一个街道」可能展开成 30 个聚落，也可能一个都没有（那条街道下还没开通任何聚落），
 * 两者在他的清单上长得一模一样，只有保存之后从「订单没来」里才察觉。
 *
 * <p>脏了才问：没改动时问一次也只会得到「当前 = 改后」，白发一次请求。
 */
const preview = ref<ScopePreview | null>(null);
const previewing = ref(false);
let previewSeq = 0;
watch([areas, deliveryOn, expressOn], async () => {
  if (!loaded.value || !dirty.value) {
    preview.value = null;
    return;
  }
  const mine = ++previewSeq;
  previewing.value = true;
  try {
    const r = await api.mScopePreview(areas.value);
    // 慢请求回来得比新的晚 —— 不判一下的话，界面上显示的是上一版范围的预览
    if (mine === previewSeq) preview.value = r;
  } catch {
    if (mine === previewSeq) preview.value = null;
  } finally {
    if (mine === previewSeq) previewing.value = false;
  }
}, { deep: true });

/** 这次没取到。**与「确定为空」是两件事** —— 整页内容都挂在拉来的数据后面 */
const failed = ref(false);

/**
 * ③ 收款方式（门店级，即点即存）。与送货方式同一种交互 —— 都是开店时定一次的经营决定。
 * 读不到就不渲染整卡，不摆一对不知真假的开关。
 */
const paySetting = ref<StorePaySetting | null>(null);
const savingPay = ref("");
const payEditable = computed(() => merchant.can("biz:store:admin"));

async function loadPaySetting() {
  try {
    paySetting.value = await api.mStorePaySetting(merchant.storeNo || "default");
  } catch {
    paySetting.value = null;
  }
}

async function togglePay(key: "offlinePayEnabled" | "codEnabled") {
  const cur = paySetting.value;
  if (!cur || savingPay.value) return;
  if (!payEditable.value) {
    uni.showToast({ title: t("store.payAdminOnly"), icon: "none" });
    return;
  }
  const turningOn = !cur[key];
  // 没证先说清楚：打到后端也是 80012，但那时商家已经以为「开上了」
  if (key === "offlinePayEnabled" && turningOn && !cur.qualified) {
    uni.showToast({ title: t("store.payNeedLicense"), icon: "none" });
    return;
  }
  if (key === "codEnabled" && turningOn && !cur.offlinePayEnabled) return;
  savingPay.value = key;
  try {
    paySetting.value = await api.mSaveStorePaySetting(cur.storeNo, { [key]: turningOn });
    if (key === "offlinePayEnabled" && turningOn) {
      uni.showToast({ title: t("store.payGoodsHint"), icon: "none", duration: 3000 });
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    savingPay.value = "";
  }
}

async function loadFulfillment() {
  try {
    fulfillment.value = await api.mStoreFulfillment(merchant.storeNo || "default");
  } catch {
    fulfillment.value = null;
  }
}

/**
 * 存一次送货方式。**开关先动，再去存**（2026-10-07 报障：「切换商家配送，反应很慢」）。
 *
 * <p>此前开关的位置绑的是服务端回包，于是按下去到它动为止，界面上**什么都不发生** ——
 * 而这个接口在生产上实测 24~27 秒（它要连带重建社区池）。后端那一半已经挪到后台跑，
 * 这里再补上即时反馈：慢的那条路修好了，但网络本来就可能慢，开关不该等任何人。
 *
 * <p>存失败就**原样翻回去**并说一句 —— 乐观更新的代价只有这一条：
 * 不回滚的话，界面显示开着、实际没开，而那正是最难发现的一种。
 */
async function persistChannels(
  next: StoreFulfillment["channels"],
  channel: string,
  pickupNos?: string[],
) {
  const before = fulfillment.value;
  savingChannel.value = channel;
  if (before) fulfillment.value = { ...before, channels: next };
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
    // 翻回去：显示开着、实际没开是最难发现的那一种
    if (before) fulfillment.value = before;
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
    failed.value = false;
  } else {
    // `loaded` 已经挡住了保存（见 `dirty`），但界面上一个字都没有 ——
    // 商家看到的是一张空的范围表，会以为自己什么都没设过
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
    failed.value = true;
  }
  applies.value = ap.status === "fulfilled" ? ap.value : [];
}

function normalize(p: StoreProfile): StoreProfile {
  return { ...p, serviceAreas: p.serviceAreas ?? [] };
}

/** 差值带正负号；0 时不显示一个「+0」——那读起来像「没算出来」 */
function diffText(n: number) {
  return n === 0 ? "" : n > 0 ? ` +${n}` : ` ${n}`;
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
    void loadPaySetting();
  });
  void load();
  void loadRule();
});
</script>

<template>
  <sh-scaffold title-key="store.scopeTitle" :denied="!merchant.can('biz:store')"
    :failed="failed"
    @retry="load"
  >
    <biz-store-tag readonly></biz-store-tag>

    <!-- ① 经营范围（门店级：顶上那枚门店胶囊就是它属于的那家店） -->
    <view class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.scope") }}</text>
        <!-- 添加是这张卡的动作：放卡头右侧的药丸，不再是卡底一整条粉色大块 -->
        <view class="sh-row">
          <text class="sh-chip" @tap="textOpen = true">{{ $t("store.text.entry") }}</text>
          <!-- 画配送范围：进独立页承载原生地图（放弹层会把 <map> 整棵子树打掉） -->
          <text class="sh-chip" @tap="openPolygonDraw">{{ $t("store.drawArea") }}</text>
          <view class="sh-chip sh-chip--primary sh-chip--icon" @tap="pickerOpen = true">
            <sh-icon name="plus" :size="22" color="var(--sh-primary-text)"></sh-icon>
            {{ $t("store.addArea") }}
          </view>
        </view>
      </view>

      <view v-if="areas.length" class="list">
        <view v-for="a in areas" :key="`${a.level}:${a.refCode}`" class="sh-row sh-row--divided item">
          <view class="sh-fill">
            <text class="txt-body item__name" :class="{ 'txt-quiet': areaPending(a) || isExclude(a) }">
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

      <!--
        「全平台不限」是一条**范围项**，不是开关位 —— 勾上就往清单里加一条，取消就删掉它。
        做成显式的理由见 ADR-034：此前它由「没框范围 + 开了快递/自送」隐式成立，
        而「没框范围」有四种成因，任何一种都会让商家在不知情的情况下铺满全平台。
      -->
      <view class="sh-row sh-row--divided item" @tap="toggleUnlimited">
        <view class="sh-fill">
          <text class="txt-body">{{ $t("store.unlimitedArea") }}</text>
          <text class="txt-caption sh-muted">{{ $t("store.unlimitedHint") }}</text>
        </view>
        <switch :checked="unlimitedOn" @tap.stop="toggleUnlimited"></switch>
      </view>
      <!-- 勾了不限却只开自提：这一条当下不生效，必须说出来，否则他以为已经全平台了 -->
      <text v-if="unlimitedIdle" class="txt-caption warn">{{ $t("store.unlimitedIdle") }}</text>

      <!--
        一条生效的纳入项都没有 = **谁也看不到**，与送货方式无关（ADR-034）。
        此前这里按送货方式分两句（只自提说「要配范围」、开了快递说「不限地区」），
        而后者在新规则下是**反的**：范围为空就是看不见，页面不能给他一句相反的承诺。
      -->
      <text v-if="emptyIsBlocking" class="txt-caption warn">{{ $t("store.areaNeeded") }}</text>

      <!--
        范围预览：**改完还没保存**时才出现。
        商家勾完只知道自己勾了几条，而「框了一个街道」可能展开成 30 个聚落、
        也可能一个都没有（那条街道下还没开通任何聚落）—— 两者在清单上长得一样。
        两个基数都给：光说「会覆盖 12 个」，他答不出那是多了还是少了。
      -->
      <view v-if="preview && dirty" class="pv">
        <text class="txt-body pv__t">{{ $t("store.previewTitle") }}</text>
        <text class="txt-caption pv__l">
          {{ $t("store.previewCommunities", {
            a: preview.currentCommunities, b: preview.nextCommunities,
            d: diffText(preview.nextCommunities - preview.currentCommunities),
          }) }}
        </text>
        <text class="txt-caption pv__l">
          {{ $t("store.previewBuyers", {
            a: preview.currentBuyers, b: preview.nextBuyers,
            d: diffText(preview.nextBuyers - preview.currentBuyers),
          }) }}
        </text>
        <!--
          **改完之后一个聚落都不覆盖**要单独说一句红的：这一条与「范围为空」不是一回事 ——
          他确实框了东西（比如一个还没开通任何聚落的街道），只是展开出来是空的，
          而那在他的清单上完全看不出来。
        -->
        <text v-if="preview.nextCommunities === 0" class="txt-caption pv__warn">{{ $t("store.previewZero") }}</text>
      </view>
      <text v-if="areas.some((a) => areaPending(a) && !isExclude(a))" class="sh-hint">{{ $t("store.areaPendingHint") }}</text>


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
    <view v-if="fulfillment" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.fulfillCard") }}</text>
      </view>

      <template v-for="c in channelRows" :key="c.channel">
        <view class="ch sh-row" :class="{ 'is-off': c.denied || c.locked }" @tap="toggleChannel(c.channel)">
          <view class="sh-fill">
            <text class="txt-body ch__name">{{ $t(`channel.${c.channel}`) }}</text>
            <!-- 名字已经说清是什么，不再配说明；只在「开不了」时说一句为什么 -->
            <text v-if="c.locked || c.denied" class="txt-caption ch__desc" :class="{ 'is-warning': c.locked }">{{ c.locked ? $t("store.channelLocked") : $t("store.channelDenied") }}</text>
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
            <view class="rate__btns">
              <text class="sh-btn sh-btn--sm sh-btn--muted" @tap="ruleOpen = false">{{ $t("store.collapse") }}</text>
              <text class="sh-btn sh-btn--sm sh-btn--soft" @tap="saveRule">{{ $t("store.saveRate") }}</text>
            </view>
          </view>
        </view>
        <view v-if="c.enabled && c.channel === 'MERCHANT_DELIVERY'" class="sum sh-row sh-row--between">
          <template v-if="!subsetOpen">
            <text class="txt-caption sum__t sh-fill">{{ subsetSummary(c) }}</text>
            <sh-go class="sum__go" @tap.stop="openSubset(c)">{{ $t("store.subset.edit") }}</sh-go>
          </template>
          <view v-else class="rate" @tap.stop>
            <view class="subset__opt sh-row sh-row--between" :class="{ 'is-on': subsetAll }" @tap="subsetAll = true">
              <text class="txt-body subset__t txt-ink">{{ $t("store.subset.all") }}</text>
              <sh-icon v-if="subsetAll" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
            <view class="subset__opt sh-row sh-row--between" :class="{ 'is-on': !subsetAll }" @tap="subsetAll = false">
              <text class="txt-body subset__t txt-ink">{{ $t("store.subset.only") }}</text>
              <sh-icon v-if="!subsetAll" name="check" :size="26" color="var(--sh-primary-text)"></sh-icon>
            </view>
            <view v-if="!subsetAll" class="subset__list">
              <view v-for="a in activeAreas" :key="a.areaNo || a.refCode" class="subset__row sh-row sh-row--between" @tap="toggleSubsetArea(a)">
                <text class="txt-body subset__name sh-fill txt-ink">{{ splitName(a).main }}<text v-if="isWhole(a)" class="txt-caption"> {{ $t("store.whole") }}</text></text>
                <sh-check :model-value="subsetPicked.includes(a.areaNo || '')"></sh-check>
              </view>
              <text v-if="!activeAreas.length" class="sh-hint">{{ $t("store.subset.noAreas") }}</text>
            </view>
            <view class="rate__btns">
              <text class="sh-btn sh-btn--sm sh-btn--muted" @tap="subsetOpen = false">{{ $t("store.collapse") }}</text>
              <text class="sh-btn sh-btn--sm sh-btn--soft" @tap="saveSubset(c)">{{ $t("common.save") }}</text>
            </view>
          </view>
        </view>
        <view v-if="c.enabled && c.channel === 'EXPRESS'" class="sum sh-row sh-row--between">
          <text class="txt-caption sum__t sh-fill">{{ $t("store.sumExpress") }}</text>
        </view>
      </template>
    </view>

    <!-- ③ 收款方式（门店级，即点即存） -->
    <view v-if="paySetting" class="sh-card">
      <view class="sh-card__head">
        <text class="txt-title">{{ $t("store.payCard") }}</text>
      </view>
      <view class="ch sh-row" :class="{ 'is-off': !payEditable }" @tap="togglePay('offlinePayEnabled')">
        <view class="sh-fill">
          <text class="txt-body ch__name">{{ $t("store.payOffline") }}</text>
          <text v-if="!paySetting.qualified" class="txt-caption ch__desc is-warning">{{ $t("store.payNeedLicense") }}</text>
        </view>
        <sh-switch :model-value="paySetting.offlinePayEnabled" :disabled="savingPay === 'offlinePayEnabled'"></sh-switch>
      </view>
      <!-- 货到付款从属于线下收款：线下没开时不出现，免得多一个点了没反应的开关 -->
      <view v-if="paySetting.offlinePayEnabled" class="ch sh-row" :class="{ 'is-off': !payEditable }" @tap="togglePay('codEnabled')">
        <view class="sh-fill">
          <text class="txt-body ch__name">{{ $t("store.payCod") }}</text>
        </view>
        <sh-switch :model-value="paySetting.codEnabled" :disabled="savingPay === 'codEnabled'"></sh-switch>
      </view>
      <text v-if="!payEditable" class="sh-hint">{{ $t("store.payAdminOnly") }}</text>
    </view>

    <biz-pickup-sheet
      :visible="pickupSheetOpen"
      @close="pickupSheetOpen = false"
      :store-no="merchant.storeNo || 'default'"
      :selected="neighborRefs.map((r) => r.pickupNo)"
      @done="savePickups"
    ></biz-pickup-sheet>

    <biz-region-picker
      :visible="pickerOpen"
      @close="pickerOpen = false"
      :areas="areas"
      :bare-exclude="unlimitedPickerMode"
      @update:areas="setAreas"
    ></biz-region-picker>

    <biz-scope-text
      :visible="textOpen"
      :areas="areas"
      :pickup-only="pickupOn && !deliveryOn && !expressOn"
      :near="storeNear"
      @close="textOpen = false"
      @apply="setAreas"
    ></biz-scope-text>

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
.pv {
  margin-top: 16rpx;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
/* 字号交给字阶类（txt-body / txt-caption），这里只管布局与颜色 —— 见 规范-字体 */
.pv__t {
  display: block;
}
.pv__l {
  display: block;
  margin-top: 8rpx;
}
.pv__warn {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-danger);
}
.progress {
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: var(--sh-hairline);
}
/* 送货方式：紧凑开关行 */
.ch {
  gap: 24rpx;
  padding: 24rpx 0;
}
/* 线画在「后一行」的头顶（同 .sh-row--divided 的规矩）：画在每行脚下的话，
   卡片最后一行下面会悬着一条线，真机上看像内容被截断了 */
.ch + .ch,
.ch + .sum,
.sum + .sum,
.sum + .ch {
  border-top: var(--sh-hairline);
}
/* 卡头已经留了一档距离，第一行不再叠上自己的上内边距 */
.sh-card__head + .ch {
  padding-top: 0;
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
  padding: 16rpx 0;
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

/* 展开态的两个动作：小药丸靠右。**此前是整宽 soft + 整宽 muted** ——
   一个局部表单的保存长得和页面主动作一样重，而这一页的主动作是底部的「保存」 */
.rate__btns {
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  margin-top: 16rpx;
}
</style>
