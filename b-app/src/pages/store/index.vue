<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 店铺装修（B-11.2.5）+ 店铺码（B-11.2.6）+ 分享素材（B-11.2.7）。
//
// **一期主获客路径的商家侧**（ADR-004 决策 3）：店主把店铺码印在包装袋、把文案发进
// 自己的客户群，老客带着复购习惯进来，获客成本 ≈ 0。
//
// 设计约束：**极简，店主是在手机上弄的**。不做拖拽布局、不做多模块编排 ——
// 一个公告 + 营业时间 + 地址就够了，多一个字段就多一个店主填不完的理由。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { FULFILLMENT_REACH, SERVICE_SCOPE } from "@shared/utils/constants";
import type {
  Community,
  FulfillmentReach,
  MasterData,
  Region,
  ServiceArea,
  ShareKit,
  StoreProfile,
  StoreQrcode,
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
const reaches = [
  FULFILLMENT_REACH.PICKUP,
  FULFILLMENT_REACH.ONSITE,
  FULFILLMENT_REACH.SHIPPING,
] as const;

const reach = computed<FulfillmentReach>(
  () => form.value.fulfillmentReach ?? FULFILLMENT_REACH.PICKUP,
);
const areas = computed<ServiceArea[]>(() => form.value.serviceAreas ?? []);

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
  () => reach.value === FULFILLMENT_REACH.PICKUP && !activeAreas.value.length,
);

function pickReach(v: FulfillmentReach) {
  form.value.fulfillmentReach = v;
}

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
  if (r.hasChild) {
    trail.value = [...trail.value, r];
    await loadRegions(r.regionCode);
  } else {
    addRegion(r);
  }
}

/** 回退到面包屑的第 i 级；i = -1 回省级 */
async function backTo(i: number) {
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
  const [s, q, k, cs, md] = await Promise.allSettled([
    api.mStore(),
    api.mStoreQrcode(),
    api.mShareKit(),
    api.mCommunities(),
    api.mMasterData(),
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
  if (!qrcode.value) return;
  uni.setClipboardData({
    data: qrcode.value.url,
    success: () => uni.showToast({ title: t("store.copied"), icon: "none" }),
  });
}

onShow(load);
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

      <text class="field__label sec">{{ $t("store.reach") }}</text>
      <view
        v-for="r in reaches"
        :key="r"
        class="scope"
        :class="{ 'is-on': reach === r }"
        @tap="pickReach(r)"
      >
        <view class="scope__main">
          <text class="scope__name">{{ $t(`fulfillmentReach.${r}`) }}</text>
          <text class="scope__desc">{{ $t(`store.reachDesc.${r}`) }}</text>
        </view>
        <text class="scope__tick">{{ reach === r ? "✓" : "" }}</text>
      </view>

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
          </view>
          <text v-if="regionLoading" class="hint">{{ $t("common.loading") }}</text>
          <view v-else class="rg__list">
            <view v-for="r in regionList" :key="r.regionCode" class="rg__i">
              <text class="rg__n" @tap="tapRegion(r)">
                {{ r.name }}<text v-if="r.hasChild" class="rg__more"> ›</text>
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
  font-weight: 400;
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
.sec {
  display: block;
  margin-top: 28rpx;
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
  color: var(--sh-primary);
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
  font-size: 26rpx;
  color: var(--sh-ink);
}
.rg__more {
  color: var(--sh-sub);
}
.rg__pick {
  flex-shrink: 0;
  font-size: 24rpx;
  color: var(--sh-primary);
}
.rg__close {
  display: inline-block;
  margin-top: 16rpx;
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
  margin-top: 24rpx;
}
.hint {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
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
  font-size: 26rpx;
  line-height: 1.7;
  color: var(--sh-ink);
}
.copy {
  margin-top: 8rpx;
}
</style>
