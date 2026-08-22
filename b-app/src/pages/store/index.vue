<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";
import { getLocation } from "@shared/ports/location";

const merchant = useMerchantStore();
// 店铺装修（B-11.2.5）+ 店铺码（B-11.2.6）+ 分享素材（B-11.2.7）。
//
// **一期主获客路径的商家侧**（ADR-004 决策 3）：店主把店铺码印在包装袋、把文案发进
// 自己的客户群，老客带着复购习惯进来，获客成本 ≈ 0。
//
// 设计约束：**极简，店主是在手机上弄的**。不做拖拽布局、不做多模块编排 ——
// 一个公告 + 营业时间 + 地址就够了，多一个字段就多一个店主填不完的理由。
import { computed, ref, watch } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import type {
  Community,
  CommunityApply,
  FulfillmentReach,
  MasterData,
  Region,
  ServiceArea,
  ShareKit,
  StoreProfile,
  StoreQrcode,
  StoreFulfillment,
} from "@shared/types";

const { t } = useI18n();

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
/** 可选社区。真实环境按商家已签约的自提点给，一期先给全量 */
const communities = ref<Community[]>([]);
/**
 * 小区列表**没加载出来**（区别于「加载成功但一个都没有」）。
 *
 * 这两件事在界面上长得一模一样 —— 都是一片空白 —— 而后果完全不同：
 * 前者是页面坏了、刷新可能就好；后者是平台还没开小区，等也没用。
 * 不分开的话，店主对着空白只会一直点「保存」，而保存永远过不去
 * （靠自提点履约却一个小区都没勾）。
 */
const communitiesFailed = ref(false);

/** 主数据。这一版只用来兜底老三档的读，档位本身已由履约能力取代 */
const master = ref<MasterData | null>(null);

/**
 * 履约能力三选一（ADR-013 阶段二）。**只回答「怎么送到你手上」**，
 * 送得到哪儿由下面的覆盖项列表单独说。
 *
 * 为什么不再用老三档：三档把两件事压进一个字段，于是「三个小区 + 整个西湖区」
 * 这种再普通不过的诉求没有字段可写 —— 店主只能选「全市」（卖到送不到的地方）
 * 或「仅本社区」（丢掉那个区）。
 */
const areas = computed<ServiceArea[]>(() => form.value.serviceAreas ?? []);

/**
 * 门店送货方式（方案 v4）：channel 挂门店、多路开关，取代三档单选。
 *
 * 与经营范围是两张卡两个接口：范围是主体级（全店共用），送货是门店级。
 * 开关**即点即存** —— 它是独立端点，攒到大保存里会出现「范围存了送货没存」
 * 这种一半一半的状态。
 */
const fulfillment = ref<StoreFulfillment | null>(null);
const fulfillmentStore = ref("default");
const savingChannel = ref("");

const channelRows = computed(() => fulfillment.value?.channels ?? []);
const pickupOn = computed(() =>
  channelRows.value.some(
    (c) => (c.channel === "STORE_PICKUP" || c.channel === "NEIGHBOR_PICKUP") && c.enabled,
  ),
);
const deliveryOn = computed(() =>
  channelRows.value.some((c) => c.channel === "MERCHANT_DELIVERY" && c.enabled),
);
const expressOn = computed(() =>
  channelRows.value.some((c) => c.channel === "EXPRESS" && c.enabled),
);

async function loadFulfillment() {
  try {
    fulfillment.value = await api.mStoreFulfillment(fulfillmentStore.value);
  } catch {
    // 老后端没有这个端点：卡片整个不渲染（fulfillment 为 null），页面其余功能照常
    fulfillment.value = null;
  }
}

async function toggleChannel(channel: string) {
  const cur = fulfillment.value;
  if (!cur || savingChannel.value) return;
  const row = cur.channels.find((c) => c.channel === channel);
  if (!row || row.denied) return;
  const next = cur.channels.map((c) =>
    c.channel === channel ? { ...c, enabled: !c.enabled } : c,
  );
  if (!next.some((c) => c.enabled)) {
    // 与服务端同一条硬规则，在端上先说人话，而不是让保存报一个笼统的错
    uni.showToast({ title: t("store.fulfillNone"), icon: "none" });
    return;
  }
  savingChannel.value = channel;
  try {
    fulfillment.value = await api.mSaveStoreFulfillment(fulfillmentStore.value, {
      channels: next.map((c) => ({
        channel: c.channel,
        enabled: c.enabled,
        templateNo: c.templateNo ?? undefined,
      })),
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message || t("store.fulfillFailed"), icon: "none" });
  } finally {
    savingChannel.value = "";
  }
}

/**
 * 覆盖项为空的含义**由履约能力决定** —— 同一个空列表两种意思：
 * 自提是「谁也看不到」（拦），上门/快递是「不限」（正常）。
 * 这是新模型最容易被端上写错的一处，所以判断只留这一个出口。
 */
const activeAreas = computed(() => areas.value.filter((a) => a.status !== "PENDING"));
/**
 * 判的是**生效中的**覆盖项，不是清单长度：区、街道级要运营审，待审的不参与展开。
 * 按长度判的话，商家勾了一个待审的区就以为万事大吉 —— 而 C 端仍然谁也看不到他。
 */
const emptyIsBlocking = computed(
  () => pickupOn.value && !deliveryOn.value && !expressOn.value && !activeAreas.value.length,
);

function hasArea(level: string, refCode: string) {
  return areas.value.some((a) => a.level === level && a.refCode === refCode);
}

function removeArea(level: string, refCode: string) {
  form.value.serviceAreas = areas.value.filter((a) => !(a.level === level && a.refCode === refCode));
}

function toggleCommunity(c: Community) {
  if (hasArea("COMMUNITY", c.communityNo)) removeArea("COMMUNITY", c.communityNo);
  else form.value.serviceAreas = [...areas.value, { level: "COMMUNITY", refCode: c.communityNo, name: c.name }];
}

// ---------------------------------------------------------------- 提报新社区（阶段三）
/**
 * 商家开在一个平台还没开的小区里，此前**无路可走** —— 只能找 BD 口头说，
 * 说完没人知道进展。入口放在小区清单正下方：那是他发现「怎么没有我这儿」的那一刻。
 */
const applies = ref<CommunityApply[]>([]);
const applyOpen = ref(false);
const applyForm = ref({ name: "", address: "", note: "" });

/** 待审的提报也要显示 —— 不显示的话商家会以为没提交成功，隔天再提一次 */
const pendingApplies = computed(() => applies.value.filter((a) => a.status === "PENDING"));
/** 驳回的要把理由显眼地摆出来：不给理由，他只会原样再提一次 */
const rejectedApplies = computed(() => applies.value.filter((a) => a.status === "REJECTED"));

async function submitApply() {
  const name = applyForm.value.name.trim();
  if (!name) {
    uni.showToast({ title: t("store.applyNeedName"), icon: "none" });
    return;
  }
  try {
    /*
     * 提报时带上当前定位（尽力而为，拿不到就不带）。
     *
     * **这不是锦上添花**：裁决通过时聚落的坐标就来自这里 ——
     * 没有坐标的聚落，买家用定位永远找不到（withinRadius 对空坐标恒 false），
     * 而全仓没有第二个采集坐标的地方：商家正站在那儿，运营在办公室补不出来。
     */
    const loc = await getLocation();
    const a = await api.mApplyCommunity({
      name,
      address: applyForm.value.address.trim() || undefined,
      note: applyForm.value.note.trim() || undefined,
      latE6: loc ? Math.round(loc.lat * 1e6) : undefined,
      lngE6: loc ? Math.round(loc.lng * 1e6) : undefined,
    });
    applies.value = [a, ...applies.value];
    applyForm.value = { name: "", address: "", note: "" };
    applyOpen.value = false;
    uni.showToast({ title: t("store.applySubmitted"), icon: "none" });
  } catch (e) {
    // 重复提报会被后端拦（同名待审只能有一条）—— 原样把话说给商家
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  }
}

// ---------------------------------------------------------------- 区划选择器
/**
 * 逐级往下点，一次只拉一级。
 *
 * 不一次拉整棵树是因为全国到街道是 4.4 万行 —— 端上要等好几秒，
 * 而店主真正会点开的只有其中一条路径。
 */
const regionOpen = ref(false);
/** 面包屑。空 = 停在省级。**可回退**：点中间任意一级都跳回去 */
const trail = ref<Region[]>([]);
const regionList = ref<Region[]>([]);
const regionLoading = ref(false);

async function openRegions() {
  regionOpen.value = true;
  streetView.value = null;
  trail.value = [];
  await loadRegions(undefined);
}

async function loadRegions(parent?: string) {
  regionLoading.value = true;
  try {
    regionList.value = await api.mRegions(parent);
  } catch {
    regionList.value = [];
    uni.showToast({ title: t("store.regionFailed"), icon: "none" });
  } finally {
    regionLoading.value = false;
  }
}

/** 点一级：有下级就钻进去，到叶子就直接加成覆盖项 */
async function tapRegion(r: Region) {
  // 街道/镇是导航的终点：点它进聚落视图，不再往下钻区划
  if (r.level === "STREET") {
    await enterStreet(r);
    return;
  }
  if (r.hasChild) {
    trail.value = [...trail.value, r];
    await loadRegions(r.regionCode);
  } else {
    addRegion(r);
  }
}

// ---------------------------------------------------------------- 街道聚落视图
/**
 * 点到街道/镇那一层，不再往下钻区划（导航止于 L4），
 * 而是平铺**这条街道下已开通的聚落**（小区/村）+ 提报入口。
 * 这正是聚落模型的样子：小区挂街道、村挂镇，两者同列。
 */
const streetView = ref<Region | null>(null);
const streetSettles = ref<Community[]>([]);
const streetLoading = ref(false);

async function enterStreet(r: Region) {
  streetView.value = r;
  streetApplyOpen.value = false;
  streetLoading.value = true;
  try {
    const all = await api.mCommunities();
    streetSettles.value = all.filter((c) => c.regionCode === r.regionCode);
  } catch {
    streetSettles.value = [];
  } finally {
    streetLoading.value = false;
  }
}

/** 勾一个聚落。名字带上街道前缀 —— 光一个「新桥」全国有好几个 */
function addSettle(c: Community) {
  if (hasArea("COMMUNITY", c.communityNo)) {
    uni.showToast({ title: t("store.areaDup"), icon: "none" });
    return;
  }
  const name = [...trail.value.map((x) => x.name), streetView.value?.name, c.name]
    .filter(Boolean).join(" / ");
  form.value.serviceAreas = [...areas.value, { level: "COMMUNITY", refCode: c.communityNo, name }];
  regionOpen.value = false;
  streetView.value = null;
}

// ---------------------------------------------------------------- 街道内提报（带官方村名词典）
const streetApplyOpen = ref(false);
const streetApplyName = ref("");
/** 词典命中并被选中的官方村；名字再被改动就作废（改了名就不再是那个村） */
const pickedVillage = ref<Region | null>(null);
const dictSuggests = ref<Region[]>([]);
let dictTimer: ReturnType<typeof setTimeout> | undefined;

/**
 * 名称联想。**只在还没选中词典项时查** —— 选中后再查会把提示又顶出来。
 * 城市小区通常匹配不到词典（词典里是村/居委会），自然落成自由输入 → ESTATE；
 * 农村输村名会命中 → 选中即 VILLAGE + originCode。商家不需要回答「小区还是村」。
 */
watch(streetApplyName, (v: string) => {
  if (pickedVillage.value && v !== cleanVillageName(pickedVillage.value.name)) {
    pickedVillage.value = null;
  }
  clearTimeout(dictTimer);
  const kw = v.trim();
  if (!kw || pickedVillage.value || !streetView.value) {
    dictSuggests.value = [];
    return;
  }
  dictTimer = setTimeout(async () => {
    try {
      const list = await api.mVillageDict(streetView.value!.regionCode, kw);
      dictSuggests.value = list.slice(0, 5);
    } catch {
      dictSuggests.value = [];
    }
  }, 300);
});

/** 「富城村村民委员会」→「富城村」：聚落叫的是地名，不是机构名 */
function cleanVillageName(official: string): string {
  return official.replace(/(村民委员会|居民委员会|村委会|居委会|委员会)$/, "") || official;
}

function pickVillage(r: Region) {
  pickedVillage.value = r;
  streetApplyName.value = cleanVillageName(r.name);
  dictSuggests.value = [];
}

async function submitStreetApply() {
  const street = streetView.value;
  const name = streetApplyName.value.trim();
  if (!street || !name) {
    uni.showToast({ title: t("store.applyNeedName"), icon: "none" });
    return;
  }
  try {
    // 定位尽力而为：裁决通过时聚落的坐标就来自这里
    const loc = await getLocation();
    const a = await api.mApplyCommunity({
      name,
      regionCode: street.regionCode,
      kind: pickedVillage.value ? "VILLAGE" : "ESTATE",
      originCode: pickedVillage.value?.regionCode,
      latE6: loc ? Math.round(loc.lat * 1e6) : undefined,
      lngE6: loc ? Math.round(loc.lng * 1e6) : undefined,
    });
    applies.value = [a, ...applies.value];
    streetApplyOpen.value = false;
    streetApplyName.value = "";
    pickedVillage.value = null;
    uni.showToast({ title: t("store.applySubmitted"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  }
}

/** 回退到面包屑的第 i 级；i = -1 回省级 */
async function backTo(i: number) {
  streetView.value = null;
  trail.value = trail.value.slice(0, i + 1);
  await loadRegions(trail.value[i]?.regionCode);
}

/**
 * 把当前这一级整个加进来 —— 「整个西湖区」而不是把西湖区下的街道逐个点一遍。
 * 中间层级也要能选，否则店主为了框一个区得点开十几个街道。
 */
function addRegion(r: Region) {
  if (hasArea(r.level, r.regionCode)) {
    uni.showToast({ title: t("store.areaDup"), icon: "none" });
    return;
  }
  // 名字拼整条路径：光一个「西湖区」全国有好几个，两条同名的商家分不出删哪条
  const name = [...trail.value.map((x) => x.name), r.name].join(" / ");
  form.value.serviceAreas = [...areas.value, { level: r.level as ServiceArea["level"], refCode: r.regionCode, name }];
  regionOpen.value = false;
}

const qrcode = ref<StoreQrcode | null>(null);
const kit = ref<ShareKit | null>(null);

async function load() {
  /*
   * **allSettled 而不是 all。**
   *
   * http-client 在 `code !== 0` 时 reject，而 Promise.all 是全有全无：
   * 四个请求里任何一个失败（店铺码还没生成、分享素材接口抖一下），
   * 后面四个赋值一个都不会执行 —— 页面于是静默退回 form 的初始值：
   * 经营范围显示「仅本社区」、覆盖小区空、公告空。
   *
   * 店主看到的是一个**看起来正常、其实什么都没加载**的页面，
   * 而他照着上面的内容点保存，就把默认值覆盖到真实数据上去了。
   * 一个请求失败不该有这种后果。
   */
  const [s, q, k, cs, md, ap] = await Promise.allSettled([
    api.mStore(),
    api.mStoreQrcode(),
    api.mShareKit(),
    api.mCommunities(),
    api.mMasterData(),
    api.mMyCommunityApplies(),
  ]);
  if (s.status === "fulfilled") {
    form.value = normalize(s.value);
  } else {
    // 这一项失败 = 整页没有真实数据可编辑，必须说出来，不能让人在默认值上编辑
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
  communitiesFailed.value = cs.status === "rejected";
  communities.value = cs.status === "fulfilled" ? cs.value : [];
  // 店铺码与分享素材缺了只是少两块展示，不影响编辑，静默降级即可
  qrcode.value = q.status === "fulfilled" ? q.value : null;
  kit.value = k.status === "fulfilled" ? k.value : null;
  // 取不到主数据不阻断编辑，档位退到上面那个保守默认
  master.value = md.status === "fulfilled" ? md.value : null;
  applies.value = ap.status === "fulfilled" ? ap.value : [];
}

/**
 * 存量店铺可能只有老三档（后端 V33 回填过，但 mock 与老缓存里还有裸数据）。
 * 在**读的这一刻**补齐成新模型，页面里就只剩一套字段要照顾 ——
 * 两套字段在模板里并存，迟早有一个分支忘了改。
 */
function normalize(p: StoreProfile): StoreProfile {
  if (p.fulfillmentReach && p.serviceAreas) return p;
  const byScope: Record<string, FulfillmentReach> = {
    COMMUNITY: FULFILLMENT_REACH.PICKUP,
    CITY: FULFILLMENT_REACH.ONSITE,
    PLATFORM: FULFILLMENT_REACH.SHIPPING,
  };
  return {
    ...p,
    fulfillmentReach: p.fulfillmentReach ?? byScope[p.serviceScope] ?? FULFILLMENT_REACH.PICKUP,
    serviceAreas:
      p.serviceAreas
      ?? (p.serviceCommunityNos ?? []).map((no) => ({
        level: "COMMUNITY" as const,
        refCode: no,
        name: communities.value.find((c) => c.communityNo === no)?.name ?? no,
      })),
  };
}

async function save() {
  /*
   * 靠自提点履约却一个覆盖项都没有 —— **必须拦住**。
   * 存下去的话这家店在 C 端对谁都不可见：店主看着自己的商品好好地上着架，
   * 一个订单也不来，还完全不知道为什么。这是那种自己永远查不出来的故障。
   *
   * 上门/快递空着**不拦** —— 那是「不限」，是个合法选择。
   */
  if (emptyIsBlocking.value) {
    uni.showToast({ title: t("store.areaNeeded"), icon: "none" });
    return;
  }
  form.value = normalize(await api.mSaveStore(form.value));
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
  // url 可空：后端未配对外域名时返回 null，此时按钮本来就不显示，这里再兜一道
  const url = qrcode.value?.url;
  if (!url) return;
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

onShow(() => {
  load();
  loadFulfillment();
});
</script>

<template>
  <sh-scaffold title-key="store.title" :denied="!merchant.can('biz:store')">
    <text class="sh-h1">{{ $t("store.title") }}</text>

    <!--
      经营范围。放在装修**之前** —— 公告写不写只影响好看，范围选错直接决定有没有生意：
      选大了卖到送不到的地方（下单后提不了货 → 退款），选小了整片小区搜不到这家店。

      两段式（ADR-013）：先说**怎么送**，再说**送到哪儿**。
      合成一个三档单选是上一版的做法，代价是「三个小区 + 一个区」根本填不出来。
    -->
    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("store.scope") }}</text>
      <text class="hint">{{ $t("store.scopeHint") }}</text>

      <text class="field__label sec">{{ $t("store.fulfillTitle") }}</text>
      <!-- 多路开关（方案 v4）：即点即存，独立于下面的大保存。denied 的路置灰给原因，不隐藏 -->
      <template v-if="fulfillment">
        <view
          v-for="c in channelRows"
          :key="c.channel"
          class="scope"
          :class="{ 'is-on': c.enabled, 'is-off': c.denied }"
          @tap="toggleChannel(c.channel)"
        >
          <view class="scope__main">
            <text class="scope__name">{{ $t(`channel.${c.channel}`) }}</text>
            <text class="scope__desc">{{
              c.denied ? $t("store.channelDenied") : $t(`store.channelDesc.${c.channel}`)
            }}</text>
          </view>
          <text class="scope__tick">{{
            savingChannel === c.channel ? "…" : c.enabled ? "✓" : ""
          }}</text>
        </view>
      </template>

      <!-- 覆盖项：小区与区划混在一张清单里，因为它们对店主是同一件事 —— 「我做哪儿」 -->
      <view class="cms">
        <text class="field__label">{{ $t("store.areas") }}</text>
        <view v-if="areas.length" class="cms__list">
          <text
            v-for="a in areas"
            :key="`${a.level}:${a.refCode}`"
            class="sh-chip cms__i is-on"
            @tap="removeArea(a.level, a.refCode)"
          >
            {{ a.name }}<text v-if="a.status === 'PENDING'" class="pend"> · {{ $t("store.areaPending") }}</text> ×
          </text>
        </view>
        <!-- 空列表的含义两分：自提是故障，上门/快递是「不限」。绝不能显示同一句话 -->
        <text v-if="emptyIsBlocking" class="warn">{{ $t("store.areaNeeded") }}</text>
        <text v-else-if="!areas.length" class="hint">{{ $t("store.areaUnlimited") }}</text>
        <!-- 有待审项就说清楚它现在不算数，否则商家以为已经铺开了 -->
        <text v-if="areas.length > activeAreas.length" class="hint">
          {{ $t("store.areaPendingHint") }}
        </text>

        <text class="field__label sec">{{ $t("store.scopeCommunities") }}</text>
        <view class="cms__list">
          <text
            v-for="c in communities"
            :key="c.communityNo"
            class="sh-chip cms__i"
            :class="{ 'is-on': hasArea('COMMUNITY', c.communityNo) }"
            @tap="toggleCommunity(c)"
          >
            {{ c.name }}
          </text>
        </view>
        <!-- 加载失败与「真的一个小区都没有」要分开说：前者刷新可能就好，后者等也没用 -->
        <text v-if="communitiesFailed" class="warn">
          {{ $t("store.communitiesFailed") }}
        </text>
        <text v-else-if="!communities.length" class="warn">
          {{ $t("store.communitiesEmpty") }}
        </text>

        <!-- 提报入口紧挨小区清单：这里正是他发现「怎么没有我这儿」的那一刻 -->
        <view v-if="!applyOpen" class="mini apply__open" @tap="applyOpen = true">
          {{ $t("store.applyEntry") }}
        </view>
        <view v-else class="rg">
          <text class="hint">{{ $t("store.applyHint") }}</text>
          <input v-model="applyForm.name" class="field__input" :placeholder="$t('store.applyNamePh')" />
          <input v-model="applyForm.address" class="field__input" :placeholder="$t('store.applyAddressPh')" />
          <input v-model="applyForm.note" class="field__input" :placeholder="$t('store.applyNotePh')" />
          <view class="btns">
            <text class="sh-btn sh-btn--soft apply__go" @tap="submitApply">{{ $t("common.submit") }}</text>
            <text class="mini" @tap="applyOpen = false">{{ $t("common.cancel") }}</text>
          </view>
        </view>

        <!-- 提报进展。不显示的话商家会以为没提交成功，隔天再提一次同样的 -->
        <text v-for="a in pendingApplies" :key="a.applyNo" class="hint">
          {{ a.name }} · {{ $t("store.applyPending") }}
        </text>
        <text v-for="a in rejectedApplies" :key="a.applyNo" class="warn">
          {{ a.name }} · {{ $t("store.applyRejected") }}{{ a.reason ? `：${a.reason}` : "" }}
        </text>

        <!-- 区划：逐级点。整个区/街道也能直接选，否则框一个区要点开十几个街道 -->
        <view v-if="!regionOpen" class="sh-btn sh-btn--soft addr" @tap="openRegions">
          {{ $t("store.addRegion") }}
        </view>
        <view v-else class="rg">
          <view class="rg__crumb">
            <text class="rg__c" @tap="backTo(-1)">{{ $t("store.regionRoot") }}</text>
            <text v-for="(x, i) in trail" :key="x.regionCode" class="rg__c" @tap="backTo(i)">
              / {{ x.name }}
            </text>
            <text v-if="streetView" class="rg__c">/ {{ streetView.name }}</text>
          </view>
          <text v-if="regionLoading || streetLoading" class="hint">{{ $t("common.loading") }}</text>

          <!--
            街道/镇视图：导航止于 L4，这一层平铺**聚落**（小区/村同列）。
            街道本身仍可整个选中；聚落逐个勾；没有的提报 —— 三件事一屏说完。
          -->
          <view v-else-if="streetView" class="rg__list">
            <view class="rg__i">
              <text class="rg__n">{{ streetView.name }}</text>
              <text class="rg__pick" @tap="addRegion(streetView)">{{ $t("store.pickThis") }}</text>
            </view>
            <text class="hint">{{ $t("store.settleUnder") }}</text>
            <view v-for="c in streetSettles" :key="c.communityNo" class="rg__i">
              <text class="rg__n" @tap="addSettle(c)">{{ c.name }}</text>
              <text class="rg__pick" @tap="addSettle(c)">{{ $t("store.pickThis") }}</text>
            </view>
            <text v-if="!streetSettles.length" class="hint">{{ $t("store.settleEmpty") }}</text>

            <view v-if="!streetApplyOpen" class="rg__i" @tap="streetApplyOpen = true">
              <text class="rg__n">{{ $t("store.settleApplyEntry") }}</text>
            </view>
            <view v-else class="sapply">
              <text class="hint">{{ $t("store.applyToStreet", { s: streetView.name }) }}</text>
              <input
                v-model="streetApplyName"
                class="field__input"
                :placeholder="$t('store.dictHint')"
              />
              <!-- 词典联想：命中官方村点一下即关联；城市小区打不出命中，自然落成自由输入 -->
              <view v-if="dictSuggests.length" class="sapply__sug">
                <text
                  v-for="d in dictSuggests"
                  :key="d.regionCode"
                  class="sh-chip"
                  @tap="pickVillage(d)"
                >
                  {{ d.name }}
                </text>
              </view>
              <text v-if="pickedVillage" class="hint">
                {{ $t("store.dictPicked", { s: pickedVillage.name }) }}
              </text>
              <view class="btns">
                <text class="sh-btn sh-btn--soft apply__go" @tap="submitStreetApply">
                  {{ $t("common.submit") }}
                </text>
                <text class="mini" @tap="streetApplyOpen = false">{{ $t("common.cancel") }}</text>
              </view>
            </view>
          </view>

          <view v-else class="rg__list">
            <view v-for="r in regionList" :key="r.regionCode" class="rg__i">
              <text class="rg__n" @tap="tapRegion(r)">
                {{ r.name }}<text v-if="r.hasChild || r.level === 'STREET'" class="rg__more"> ›</text>
              </text>
              <!-- 有下级的也要能整个选中：「整个西湖区」是最常见的诉求 -->
              <text class="rg__pick" @tap="addRegion(r)">{{ $t("store.pickThis") }}</text>
            </view>
          </view>
          <text class="mini rg__close" @tap="regionOpen = false">{{ $t("common.cancel") }}</text>
        </view>
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
      <!-- 真的小程序码。扫了直接进 C 端门店页，不依赖备案域名 -->
      <view class="qr">
        <image
          v-if="qrcode?.imageBase64"
          class="qr__img"
          :src="`data:image/png;base64,${qrcode.imageBase64}`"
          mode="widthFix"
        />
        <template v-else>
          <text class="qr__ph">▦</text>
          <!-- **不画一张假码**：占位图会被印到包装袋上，而它扫不出任何东西 -->
          <text class="sh-muted qr__note">{{ $t("store.qrcodePending") }}</text>
        </template>
        <text v-if="qrcode?.storeCode" class="qr__code sh-num">{{ qrcode.storeCode }}</text>
      </view>
      <!-- 链接只在真的有域名时显示 —— 后端未配时返回 null，这里就整行不出现 -->
      <text v-if="qrcode?.url" class="link sh-num">{{ qrcode.url }}</text>
      <view class="btns">
        <text v-if="qrcode?.url" class="mini" @tap="copyLink">{{ $t("store.copyLink") }}</text>
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
.qr__img {
  width: 320rpx;
}
.qr__code {
  display: block;
  margin-top: 12rpx;
  font-size: 30rpx;
  letter-spacing: 4rpx;
  color: var(--sh-ink);
}
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
  font-size: 28rpx;
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
  font-weight: 400;
  color: var(--sh-primary-text);
}
.cms {
  margin-top: 20rpx;
}
.cms__list {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 14rpx;
}
.cms__i.is-on {
  background: var(--sh-primary);
  /* 同 apply：主色上的前景走 --sh-on-primary，不写死白字 */
  color: var(--sh-on-primary);
}
.sec {
  display: block;
  margin-top: 16rpx;
}
.addr {
  margin-top: 20rpx;
}
.rg {
  margin-top: 20rpx;
  padding: 20rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.rg__crumb {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.rg__list {
  margin-top: 12rpx;
}
.rg__i {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16rpx 0;
  border-top: 2rpx solid var(--sh-line);
}
.rg__n {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.rg__more {
  color: var(--sh-sub);
}
/* 街道内提报的小表单与联想 chip */
.sapply {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 12rpx 0;
}
.sapply__sug {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.rg__pick {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.rg__close {
  display: inline-block;
  margin-top: 16rpx;
}
.apply__open {
  display: inline-block;
  margin-top: 20rpx;
}
.apply__go {
  flex: 1;
}
.pend {
  font-size: 24rpx;
  opacity: 0.85;
}
.warn {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-danger);
}
.mt {
  margin-top: 16rpx;
}
.hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
.save {
  margin-top: 24rpx;
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
  font-size: 48rpx;
  line-height: 1;
  color: var(--sh-sub);
}
.qr__note {
  display: block;
  margin-top: 20rpx;
  font-size: 24rpx;
}
.link {
  display: block;
  font-size: 24rpx;
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
  font-size: 24rpx;
  line-height: 1.7;
  color: var(--sh-ink);
}
.copy {
  margin-top: 8rpx;
}
</style>
