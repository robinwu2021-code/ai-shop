<script setup lang="ts">
// 工作台（B-10.1 + B-11 汇总）。
//
// 设计要点：**数字即入口**。商家早上打开 App 只想知道「有几件事要我做」，
// 不需要 Banner、不需要推荐。所以第一屏是待办数字网格，点数字直接进对应列表。
// 这与 C 端首页（逛）是相反的信息架构，不复用页面。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useI18n } from "vue-i18n";
import { useMerchantStore } from "@/stores/merchant";
import { urgentStockItems, canStartNewCount } from "@/shared/stock-urgent";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import { SERVICE_SCOPE } from "@shared/utils/constants";
import { visibleToBuyers } from "@shared/utils/coverage";
import type { MerchantStats, MerchantTodo, PaymentApplyment, StockSummary, StoreProfile } from "@shared/types";
import { prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/*
 * 「有没有开店」这件事知不知道。**不知道的时候什么都别说**：
 * 此前没有这一层 —— 没缓存的冷启（刚装、缓存过期）先亮一下「还没有开店」，数据回来才变成工作台；
 * 更糟的是 loadProfile 的失败被 `.catch(() => null)` 吞掉，**网络不通时也显示「还没有开店 · 先把店开起来」**，
 * 店主看到的是「你没有店」，实际只是没网（2026-09-25 真机上看到的那一闪）。
 * 有缓存的 profile 直接算「知道」：工作台照常秒开，不受这一层影响。
 */
const profileState = ref<"pending" | "ok" | "failed">(merchant.profile ? "ok" : "pending");

const todo = ref<MerchantTodo | null>(null);
/**
 * 库存待办的三个数。**与 todo 分开取** —— 它在另一个域（进销存独立库），
 * 而且理货员常常有 `biz:stock` 却没有 `biz:order:view`：
 * 混进 todo 的话，他一进工作台 mTodo 被拒，连库存这道门也跟着没了。
 */
const stockSummary = ref<StockSummary | null>(null);

/**
 * 进销存那张卡底下的三个快捷。**随状态变，不写死。**
 *
 * 「有人在等」的那几项由 `urgentStockItems` 给 —— **与库存页共用一份**：
 * 各算一份的下场今天演过（加「在途」只加了库存页、加「继续盘点」只加了工作台），
 * 两边都不报错，只是各缺对方一半。
 *
 * 这里额外做的只有一件事：**用固定序补满三个**。工作台是「看一眼顺手做一件」的地方，
 * 空着两格不如给他最常按的那个；库存页不补，那儿的写动作在贴底条里。
 */
const invActs = computed(() => {
  const acts = urgentStockItems(stockSummary.value).map((u) => ({
    key: u.key,
    label: String(t(u.labelKey, u.params ?? {})),
    route: u.route,
    urgent: true,
  }));
  for (const [key, route] of [
    ["purchase", ROUTES.purchaseEdit],
    ["check", ROUTES.stockCheck],
    ["docs", ROUTES.stockDocs],
  ] as const) {
    if (acts.length >= 3) break;
    // 有单开着时不补「盘点」：上面 urgent 里已经有「继续盘点」，
    // 两个并排摆着，点错一个就开出第二张单 —— 而两张锁的账面数是两个时刻的
    if (key === "check" && !canStartNewCount(stockSummary.value)) continue;
    acts.push({ key, label: String(t(`stock.entry.${key}`)), route, urgent: false });
  }
  return acts;
});
const stats = ref<MerchantStats | null>(null);

/*
 * 开张之后有**三件互相独立的事**，商家最常问的也正是这三个问题：
 *   我能开张了吗？ → 主体 ACTIVE
 *   我能收钱了吗？ → 进件 ACTIVE
 *   我的店能被看到吗？ → 服务范围非空
 *
 * 它们各自可失败、互不阻塞：进件没过照样能上架，范围空着照样能收钱。
 * 此前这三个状态散在三个页面里，工作台一个都不提示 ——
 * 于是商家上完架等订单，实际卡在其中一条，而**两者都不报错**。
 */
const payments = ref<PaymentApplyment[]>([]);
const store = ref<StoreProfile | null>(null);

const canReceive = computed(() => payments.value.some((p) => p.canReceiveMoney));
const visible = computed(() => {
  const st = store.value;
  if (!st) return false;
  /*
   * **按新模型判**（ADR-013 阶段二：fulfillmentReach + serviceAreas）。
   *
   * 此前读的是 `serviceScope / serviceCommunityNos` —— 那两个字段已 deprecated，
   * 店铺页保存的是 `serviceAreas`，后端也不再回填旧字段。
   * 于是店主选完小区、保存成功、C 端确实可见了，**工作台那条红字还在**，
   * 点进去一看又是已经选好的 —— 一条永远消不掉、也无从消起的告警。
   *
   * 空数组的含义由 reach 决定：PICKUP 空 = 谁也看不到；ONSITE / SHIPPING 空 = 不限。
   */
  if (st.fulfillmentReach || st.serviceAreas) {
    /*
     * ★ 判据走 `visibleToBuyers`（`@shared/utils/coverage`），**不自己数 length**。
     *
     * 这里原来写的是 `serviceAreas.length > 0` —— 而排除项也占一行。
     * 于是一个「我上门送，就是不送 3 幢」的商家改成只自提之后：
     *   后端  纳入项为空 + 只自提 → 谁也看不到他
     *   工作台 serviceAreas 有 1 条 → 一切正常，一条告警都不出
     * 说的和做的正好相反，而两边都不报错。经营范围页那一处也曾是同一个洞。
     */
    return visibleToBuyers(st.fulfillmentReach, st.serviceAreas);
  }
  // 老数据回落：后端 V33 之前存的店只有老三档
  return st.serviceScope !== SERVICE_SCOPE.COMMUNITY || st.serviceCommunityNos.length > 0;
});

/**
 * 只在「有问题」时出现。全通过还挂一张绿卡，是每天都要划过去的噪音。
 *
 * <b>只给能处理它的人看</b>。这两张卡的数据来自 `/biz/merchant/payment` 与 `/biz/store`，
 * 而店员对这两个端点必被 70006 拒 —— 拒绝会 catch 成空值，空值又恰好长得像
 * 「没进件」「没选社区」。于是店员的工作台上永远挂着两条他既看不懂也点不开的红字，
 * 点「去处理」进去还是 70006。
 *
 * **把权限不足渲染成业务待办是最坏的一种失败**：它不像故障，像是店里真出了事。
 */
const blockers = computed(() => {
  const list: { key: string; route: string }[] = [];
  /*
   * **还没交证照 —— 这条排在最前，也是唯一一条「店开着但一单也不会来」的。**
   *
   * 无证照快速开店的人，店建好了、货也能录，但买家看不到他（后端可见性闸门
   * 按主体状态挡）。不常驻这一条的话，他会以为已经开张了，过几天才发现
   * 一单没有 —— 而那时他既不知道差什么，也不知道去哪补。
   *
   * 不判 `can()`：这是主体级的事，店员看到也无妨（他确实在一家还没开张的店里干活），
   * 而下面两条是权限相关的，见方法注释。
   */
  if (merchant.pendingLicense) {
    list.push({ key: "license", route: ROUTES.apply });
  }
  /*
   * **归集商户不提这一条。** 钱先进平台户、再由平台结算给他，他压根不需要
   * 自己的二级商户号 —— 而这条告警读的是通道进件状态，于是它对归集商户
   * **永远亮着、也永远点不掉**：点进去是一份与他的资金路径无关的进件表单。
   *
   * 这与上面那段说的是同一种失败：告警本身成了噪音，而真正要紧的两条
   * （能不能开张、看不看得见）会被它挤得不再被当回事。
   */
  if (merchant.can("biz:finance") && !merchant.fundsAggregated && !canReceive.value) {
    list.push({ key: "payment", route: ROUTES.payment });
  }
  if (merchant.can("biz:store") && !visible.value) {
    list.push({ key: "scope", route: ROUTES.storeScope });
  }
  /*
   * 协议待本人补勾（三期）。**排在最后**：上面三条是「生意做不成」，
   * 这一条不挡任何事 —— 当前实现只提示不拦截（今天没有提现可挂，
   * 而拦上架或拦收款会打断一家已经审核通过的店的生意，
   * 而协议没勾是平台流程造成的：运营代填时不能替他勾）。
   *
   * 但它要**常驻到他勾为止**：代填的商户只在入驻页见过一次这件事，
   * 而审核通过之后他就不去那一页了 —— 那条提示等于只出现过一瞬。
   *
   * 判据来自后端的 agreementPending（代填 **且** 没勾）。
   * 端上自己按「agreedAt 为空」判的话，全体存量商家都会恒亮这一条。
   */
  if (merchant.agreementPending) {
    list.push({ key: "agreement", route: ROUTES.apply });
  }
  return list;
});

/**
 * 「店铺公告」格子右侧的短状态：审核中 / 未发布 / 几号到期。挂着且不过期就不写 —— 那是常态。
 *
 * <p><b>为什么值得占这一格</b>：公告是这一屏唯一的日频内容，最常见的故障是
 * 「早上挂的今日到货，晚上忘了撤」。只有「公告」两个字的话，挂没挂、哪天没，
 * 只有点进去才知道 —— 于是没人会去点，也就没人会去撤。
 *
 * <p>**只给短状态，不给正文**：此前挂的是「正文前 10 字 · 到期」，放不进等高的格子，
 * 挪到卡顶单独一行后又和「店铺公告」格子成了同一件事的两个位置（2026-09-28 店主指出）。
 * 审核中优先：那时店铺页上挂的和他以为的不是同一句，这件事更要紧。
 * `store` 是这一页本来就要拉的（三条开张告警都读它），不多发请求。
 */
const noticeTag = computed(() => {
  const st = store.value;
  if (!st) return "";
  if (st.noticePending) return String(t("store.noticeAuditing"));
  if (!(st.announcement ?? "").trim()) return String(t("home.noticeNone"));
  const at = st.announcementUntil;
  if (!at) return "";
  const d = new Date(at);
  const sameDay = d.toDateString() === new Date().toDateString();
  return String(t("home.noticeUntil", {
    s: sameDay ? String(t("store.ttl.todayAt")) : `${d.getMonth() + 1}/${d.getDate()}`,
  }));
});

/**
 * 待办格子。数字为 0 的也留着 —— 位置固定，商家才能形成肌肉记忆。
 *
 * <b>每个格子跟着它自己的权限走</b>，不是整块一起给。这 7 个数分属 5 个权限，
 * 而点进去的页面各有各的判权：画一个点进去报 70006 的格子，
 * 比不画它更糟 —— 它每天都在那儿，每天都点不开。
 */
/*
 * 进销存**不进这个九宫格**。
 *
 * 原来它是第八格，数字是「缺货 + 滞销」—— 而店铺健康时那两个数恒为 0，
 * 于是这格永远写着「0 库存」，混在七个订单待办里，读起来像「你没有库存」。
 * 实测那家店库存里有 17 个在售 SKU，格子上却是 0：**格子上的数与里面的东西
 * 是两回事，而商家没有理由知道这一点**。
 *
 * 它是一个模块，不是一个待办数，所以给它自己一张卡（见模板里的 .inv），
 * 三个真数都摆出来，常用的三个动作直达。
 */
const cells = computed(() => {
  const t = todo.value;
  if (!t) return [] as { key: string; n: number; route: string; perm: string }[];
  // 显式标注：否则 TS 会把 route 收窄成 base 里那几个字面量，splice 进来的核销/分拣路由报错
  const base: { key: string; n: number; route: string; perm: string }[] = [
    { key: "toShip", n: t.toShip, route: ROUTES.orders, perm: "biz:ship" },
    { key: "toDeliver", n: t.toDeliver, route: ROUTES.delivery, perm: "biz:ship" },
    /*
     * 待备货：把货送到买家选的那个自提点去。**与「待分拣」是两码事** ——
     * 分拣是在自己的点上分货，备货是把货送出门，而买家常常选别家的点。
     * 之前把 toPick 改成按自提点算之后，这件事在工作台上完全消失了：
     * 有活、没数字、也没入口。
     */
    { key: "toStock", n: t.toStock, route: ROUTES.orders, perm: "biz:ship" },
    { key: "afterSale", n: t.afterSale, route: ROUTES.afterSale, perm: "biz:aftersale" },
    { key: "toReply", n: t.toReply, route: ROUTES.reviews, perm: "biz:review" },
  ];
  // 不承接自提点的商家不该看到核销/分拣 —— 那是自提点承接方的活（ADR-005）
  if (merchant.isPickupPoint) {
    base.splice(2, 0, { key: "toVerify", n: t.toVerify, route: ROUTES.verify, perm: "biz:verify" });
    base.splice(3, 0, { key: "toPick", n: t.toPick, route: ROUTES.picking, perm: "biz:receive" });
  }
  return base.filter((c) => merchant.can(c.perm));
});

/**
 * 功能入口：两列小格，标题四字以内（2026-09-28 店主：入口太多，字小一号、两列排）。
 *
 * <p>说明行一律不要 —— 「经营类目 · 本店卖哪几类」这种是把名字再说一遍。
 * 公告的**现状**（挂没挂、哪天到期）是点进去之前就该知道的事 —— 只放一个短状态在它格子的右侧（noticeTag），
 * 与箭头同一行，格子不会因此变高。
 * 每格跟自己的权限走，与待办格子同一条规矩。
 */
const entries = computed(() =>
  [
    { key: "notice", label: t("home.noticeEntry"), route: ROUTES.storeNotice, perm: "biz:store" },
    { key: "scope", label: t("home.scopeEntry"), route: ROUTES.storeScope, perm: "biz:store" },
    { key: "store", label: t("home.storeEntry"), route: ROUTES.store, perm: "biz:store" },
    // 发货设置：寄件人、地址、默认快递与重量，一次填好，发货时自动带出（TDD-快递100商家寄件 §7）
    { key: "ship", label: t("home.shipEntry"), route: ROUTES.shipSettings, perm: "biz:store" },
    { key: "catalog", label: t("home.catalogEntry"), route: ROUTES.storeCategories, perm: "biz:store:admin" },
    { key: "specs", label: t("home.specsEntry"), route: ROUTES.mySpecs, perm: "biz:goods" },
    { key: "skuIdentity", label: t("home.skuIdentityEntry"), route: ROUTES.skuIdentity, perm: "biz:goods" },
    // 营销是唯一入口：活动 / 优惠券 / 团购都在它下面（2026-09-18 店主）
    { key: "marketing", label: t("home.marketingEntry"), route: ROUTES.marketing, perm: "biz:campaign" },
  ].filter((e) => merchant.can(e.perm)));

/** 核销分拣的两个数：各跟自己的权限走，有活的那一格用主色 */
const fulfillItems = computed(() =>
  [
    { key: "toPick", perm: "biz:receive", n: todo.value?.toPick ?? 0 },
    { key: "toVerify", perm: "biz:verify", n: todo.value?.toVerify ?? 0 },
  ]
    .filter((x) => merchant.can(x.perm))
    .map((x) => ({
      key: x.key,
      value: x.n,
      label: String(t(`home.cell.${x.key}`)),
      tone: x.n ? ("primary" as const) : undefined,
    })));

/**
 * 待办格子几列：**按个数挑，让最后一行别只剩一格**。格子数随角色与门店能力变（真机上福田店 5 个、
 * 自提点 7 个）：写死四列，5 个就是 4 + 1；写死三列，7 个就是 3 + 3 + 1。
 * 余 1 或 2 用三列（5→3+2、6→3+3、9→3+3+3），其余用四列（7→4+3、8→4+4）。
 */
const tileCols = computed(() => {
  const n = cells.value.length;
  return n > 4 && (n % 4 === 1 || n % 4 === 2) ? 3 : 4;
});

const ownedRate = computed(() =>
  stats.value ? `${Math.round(stats.value.ownedTrafficRate * 100)}%` : "—",
);

async function load() {
  if (!merchant.profile) profileState.value = "pending";
  try {
    await merchant.loadProfile();
    profileState.value = "ok";
  } catch {
    // 有缓存就照缓存显示（能用的入口别收起来）；连缓存都没有，才是真的「不知道」
    profileState.value = merchant.profile ? "ok" : "failed";
  }
  if (!merchant.canOperate) return;
  // 门店要先定下来：它决定后面这一屏所有数字属于哪家店
  await merchant.loadStores();
  /*
   * 再等权限到位。`loadStores` 里的 `switchStore` 只是**触发**了 loadScope
   * （`void this.loadScope()`，没有 await），所以紧接着读 `can()` 是一场竞态 ——
   * 输了的表现是：老板的经营数据/进件状态这一屏静悄悄地少几块，刷新一下又有了。
   */
  await merchant.ensureScope();
  /*
   * 三段状态与待办一起取：分开取的话「能不能收钱」会晚一拍出现，
   * 而那一拍里工作台看着是全绿的。
   *
   * **四条都要各自 catch**。前两条原先没有，而它们各需要一个权限
   * （待办要 biz:order:view、经营数据要 biz:customer）——
   * 于是理货员一进工作台，mTodo 被 70006 拒，Promise.all 整体 reject，
   * 四个值一个都赋不上，**整屏空白**。没有报错，也没有入口，
   * 看起来就像这家店什么都没有。
   *
   * 拿不到就是拿不到：下面每一块都自己判空，少一块比整屏没了强得多。
   */
  /*
   * **没权限的先别发**。catch 已经保证了不会整屏空白，但对店员来说
   * 后三条是每次进首页都必然 403 的请求 —— 日志里三条噪音、首屏多三个来回，
   * 而它们的结果本来就不会被画出来（`blockers` 与 `stats` 卡片各自判过 `can()`）。
   */
  [todo.value, stats.value, payments.value, store.value, stockSummary.value] = await Promise.all([
    api.mTodo().catch(() => null),
    merchant.can("biz:customer") ? api.mStats().catch(() => null) : null,
    merchant.can("biz:finance") ? api.mPayments().catch(() => []) : [],
    merchant.can("biz:store") ? api.mStore().catch(() => null) : null,
    // 没权限的先别发 —— 与上面三条同一条规矩
    merchant.can("biz:stock") ? api.mStockSummary().catch(() => null) : null,
  ]);
}

function open(route: string) {
  if (!route) {
    // 未交付的格子给明确说法，不做静默无响应 —— 点了没反应会被当成 bug
    uni.showToast({ title: t("home.laterBatch"), icon: "none" });
    return;
  }
  // tabBar 页只能 switchTab，普通页只能 navigateTo，用错会静默失败
  if (route === ROUTES.orders) uni.switchTab({ url: route });
  else uni.navigateTo({ url: route });
}

function goApply() {
  uni.navigateTo({ url: merchant.isLogin ? ROUTES.apply : ROUTES.login });
}

/**
 * 先开店：填个店名就把店建起来，执照以后再补。
 *
 * <p><b>把它摆在「去入驻」旁边，而不是藏在里面</b>：那张入驻表单要填行业、
 * 主体类型、经营范围、结算账户、传执照 —— 对一个只想先看看这东西能不能用的
 * 街边小店老板，那是一道劝退墙。这条路只问一句「店叫什么」。
 *
 * <p>补证照走的还是入驻表单，服务端会认领这家店 —— 他现在录的商品都还在。
 */
const opening = ref(false);
async function goQuickStart() {
  if (!merchant.isLogin) {
    uni.navigateTo({ url: ROUTES.login });
    return;
  }
  if (opening.value) return;
  // hint 是说明、value 是初值 —— 此前用 showModal 的 content 传说明，
  // 而 editable 下 content 是**初值**：新商家按下确定，店名就成了那一整段话
  const name = await prompt({
    title: String(t("home.quickStartTitle")),
    hint: String(t("home.quickStartBody")),
    placeholder: String(t("home.quickStartPh")),
  });
  if (!name?.trim()) return;
  opening.value = true;
  try {
    await api.mQuickStart({ storeName: name.trim() });
    // 重新拉一次资料 —— 状态、门店、权限全变了
    await merchant.loadProfile();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    opening.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="tab.home" tab="home">
    <!-- 还不知道有没有店：等待时什么都不渲染，失败给重试 —— 都不能说成「还没有开店」 -->
    <sh-empty
      v-if="!merchant.canOperate && profileState !== 'ok'"
      :pending="profileState === 'pending'"
      :failed="profileState === 'failed'"
      @retry="load"
    ></sh-empty>
    <!-- 未入驻：整屏只讲一件事 —— 去开张 -->
    <view v-else-if="!merchant.canOperate" class="empty">
      <text class="txt-display">{{ $t("home.notMerchant") }}</text>
      <text class="sh-muted sh-mt-sm blk">{{ $t("home.notMerchantHint") }}</text>
      <view class="sh-btn go" @tap="goQuickStart">
        {{ opening ? $t("common.loading") : $t("home.quickStart") }}
      </view>
      <sh-go class="applylink" :text="String($t('home.goApplyWithLicense'))" @tap="goApply"></sh-go>
    </view>

    <template v-else>
      <!-- 门店这件事的唯一入口：显示在看哪家店，点进门店管理（切店/改名/开新店） -->
      <biz-store-tag></biz-store-tag>

      <!--
        开张之后卡在哪，这里直说。**只在有问题时出现** ——
        全通过还挂一张绿卡，是每天都要划过去的噪音。
      -->
      <view v-for="b in blockers" :key="b.key" class="sh-notice sh-notice--warning blocker sh-row" @tap="open(b.route)">
        <view class="sh-fill">
          <text class="txt-strong blocker__t">{{ $t(`home.blocker.${b.key}`) }}</text>
          <text class="txt-caption blocker__d">{{ $t(`home.blockerHint.${b.key}`) }}</text>
        </view>
        <text class="txt-caption blocker__go">{{ $t("home.blockerGo") }}</text>
      </view>

      <view class="tiles sh-wrap" :class="`tiles--${tileCols}`">
        <view v-for="c in cells" :key="c.key" class="sh-card tiles__cell" @tap="open(c.route)">
          <text class="txt-hero tiles__n sh-num" :class="c.n ? 'txt-primary' : 'txt-faint'">{{ c.n }}</text>
          <text class="txt-caption tiles__label">{{ $t(`home.cell.${c.key}`) }}</text>
        </view>
      </view>

      <!-- 今日：与进销存同一块读数（sh-stat panel）—— 此前这里手写三等分、数字用标题字阶，
           同一屏上两种「一排数」长得不一样 -->
      <view v-if="stats" class="sh-card">
        <view class="sh-card__head">
          <text class="txt-title">{{ $t("home.today") }}</text>
        </view>
        <sh-stat
          panel
          :items="[
            { value: stats.todayOrders, label: String($t('home.orders')) },
            { value: money(stats.todayGmvMinor, stats.currency), label: String($t('home.gmv')) },
            { value: stats.rating || '—', label: String($t('home.rating')) },
          ]"
        ></sh-stat>
      </view>

      <!--
        进销存：**给它一张自己的卡**，不塞进上面那个待办九宫格。

        它是一个模块（九个页面），而九宫格里那七格是订单流水线上的待办数。
        混在一起时它显示「缺货 + 滞销」＝ 0，和旁边七个 0 长得一模一样，
        商家看不见它 —— 而库存里其实有几十上百个 SKU。

        三个数都摆出来（在售 / 缺货 / 滞销），点哪儿都进库存；
        下面三个是他每天真正要做的动作，直达，不用先进库存页再找。
      -->
      <view v-if="stockSummary && merchant.can('biz:stock')" class="sh-card inv">
        <!-- 「全部」是按钮不是一行字：药丸底 + 箭头，与卡里其他可点的东西同一个长相 -->
        <view class="sh-card__head" @tap="open(ROUTES.stock)">
          <text class="txt-title">{{ $t("home.inv.title") }}</text>
          <view class="sh-chip sh-chip--primary sh-chip--icon">
            {{ $t("home.inv.all") }}
            <sh-icon name="chevronRight" :size="22" color="var(--sh-primary-text)"></sh-icon>
          </view>
        </view>
        <!--
          **与库存页顶部逐字相同的那一块**：同样四个数、同一个库件、同样的顺序。
          原来这里是手写的三等分，少一个「在途」—— 而那是四个数里唯一有人在等的，
          偏偏缺在商家每天第一眼看的地方。手写一份的下场就是它不会跟着改。
        -->
        <sh-stat
          panel
          :items="[
            { key: 'sku', value: stockSummary.itemCount, label: String($t('home.inv.onSale')) },
            { key: 'shortage', value: stockSummary.shortageCount, label: String($t('home.inv.shortage')), tone: 'bad' },
            { key: 'stale', value: stockSummary.staleCount, label: String($t('home.inv.stale')) },
            { key: 'transit', value: stockSummary.inTransitCount, label: String($t('stock.statTransit')), tone: 'warn' },
          ]"
          @change="open(ROUTES.stock)"
        ></sh-stat>
        <view class="inv__acts">
          <!--
            提亮的是**有人在等**的那些：「收货 N」是货停在路上，
            「继续盘点」是一张盘点单开着没收尾、账面还锁着。
            其余的什么时候点都行。
            提亮走库件（主色 + 加重），不在页面里自己写一条类。
          -->
          <view
            v-for="a in invActs"
            :key="a.key"
            class="txt-sub sh-fill inv__act"
            :class="a.urgent ? 'txt-primary txt-bold' : 'txt-ink'"
            @tap="open(a.route)"
          >
            {{ a.label }}
          </view>
        </view>
      </view>


      <!-- 自带客流占比：这是商家最该关心的数字，它直接决定费率档（ADR-004 §6） -->
      <view v-if="stats" class="sh-card owned">
        <view class="sh-card__head">
          <text class="txt-title">{{ $t("home.ownedTraffic") }}</text>
          <text class="txt-display owned__v sh-num txt-primary">{{ ownedRate }}</text>
        </view>
      </view>

      <!--
        入口按 perms 裁剪。**后端拒绝是安全边界，这里只是体验** ——
        两者都要有：只做后端，员工会看到一堆点了报 70006 的入口；
        只做这里，那不是安全。

        判权一律用 merchant.can()，不要按角色名自己推 ——
        两处各推一次迟早分岔，而分岔的表现是「看得见但点了报错」。
      -->
      <!--
        履约台把核销、分拣、到货确认放在一起，而这三件事是**三个权限**。
        入口只判 biz:verify 的话，理货员（只有 biz:receive）一个入口都看不到 ——
        而分拣正是他今天唯一要干的活。有权限没有入口，和没权限一样。

        **两道工序、两个数字、两个去处，不是一张卡赌一个目的地**：
        分拣（备货中→标到货）在前、核销（等人来取）在后，是同一条流水线上
        前后相邻的两步。此前这张卡不管点谁都固定跳核销页——同时有分拣活
        没有核销活时，点进去正好是句"当前没有待核销的订单"，分拣入口
        反而要回首页从待办格子里单独找。数字复用 `todo`（已经在拉了，不多发请求）。
      -->
      <view
        v-if="merchant.isPickupPoint && (merchant.can('biz:verify') || merchant.can('biz:receive'))"
        class="sh-card fulfill"
      >
        <view class="sh-card__head">
          <text class="txt-title">{{ $t("home.fulfillEntry") }}</text>
        </view>
        <sh-stat panel :items="fulfillItems" @change="open($event === 'toPick' ? ROUTES.picking : ROUTES.verify)"></sh-stat>
      </view>

      <!--
        功能入口：两列小格。「规格」与「类目」各自一格（规格页已独立，埋在类目页里等于找不到）；
        「商品编码」也要有门 —— 那一页第一版漏了入口，真机装完才发现。
      -->
      <!--
        格子一律等高：每格一行「名字 + 箭头」。公告的现状只留一个短状态，与箭头同一行 ——
        此前是正文摘要占第二行，那一格比别的高一截、两列对不齐。
      -->
      <view v-if="entries.length" class="sh-card">
        <view class="sh-card__head">
          <text class="txt-title">{{ $t("home.entriesTitle") }}</text>
        </view>
        <view class="entries">
          <view v-for="e in entries" :key="e.key" class="sh-row sh-row--between entry" @tap="open(e.route)">
            <text class="txt-body sh-fill entry__t">{{ e.label }}</text>
            <text v-if="e.key === 'notice' && noticeTag" class="txt-caption sh-muted entry__tag">{{ noticeTag }}</text>
            <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
          </view>
        </view>
      </view>

    </template>
  </sh-scaffold>
</template>

<style scoped>
.blk {
  display: block;
}
.go {
  /* 48rpx 是一整行字的高度，作为「主按钮与上方内容」的距离过头了 */
  margin-top: 28rpx;
}
.blocker {
}

.blocker__t {
  display: block;
}
.blocker__d {
  display: block;
  margin-top: 8rpx;
}
.blocker__go {
  flex-shrink: 0;
  color: var(--sh-primary-text);
}
.tiles {
  gap: 16rpx;
}
/* 面色与圆角交给 `.sh-card`。**内边距留在这里是有意的**：
   四列时格子只有 ~80px 宽，卡片档的 24rpx 会把两位数的数字挤到换行。
   用积木 + 覆盖一条，比整张卡照抄一遍强 —— 覆盖的那条一眼看得出是特例。 */
.tiles__cell {
  /* 最小宽按边框盒算：默认的内容盒会把内边距加在 33% 之外，三列被挤成两列 */
  box-sizing: border-box;
  /* 列数由 tileCols 按个数挑（三列或四列），这里只给四列的宽；三列见下一条 */
  flex: 0 1 calc(25% - 12rpx);
  min-width: calc(25% - 12rpx);
  padding: 20rpx 8rpx;
  text-align: center;
}
.tiles--3 .tiles__cell {
  flex-basis: calc(33.33% - 11rpx);
  min-width: calc(33.33% - 11rpx);
}
.tiles__n {
  display: block;
}
.tiles__label {
  display: block;
  margin-top: 8rpx;
}
/* 进销存卡：四个数 + 一行快捷。
   四个数**不在这里画** —— 用库件 `sh-stat panel`，与库存页顶部是同一块东西。
   原来这里有 .inv__row / .inv__item / .inv__v 三条自己画三等分的规则，
   于是加「在途」时库存页跟上了、这里没有。删掉它们就不会再分叉。 */
.inv__acts {
  display: flex;
  gap: 12rpx;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: var(--sh-hairline);
}
/* 只管版面。**颜色不在这里** —— 基类一旦设 color，
   它就是 scoped 的 (0,2,0)，会压掉模板上挂的库件（.txt-primary 那类），
   而症状是「类挂上了但颜色没变」，没有任何东西会报。 */
/* 占满剩余走 .sh-fill（模板上挂着） */
.inv__act {
  text-align: center;
  padding: 16rpx 0;
  border-radius: 16rpx;
  background: var(--sh-bg);
}

.owned {
  background: var(--sh-primary-tint);
}
/* 功能入口：两列等高的按钮 —— 与进销存那排快捷同一个长相（浅底圆角） */
.entries {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12rpx;
}
.entry {
  height: 88rpx;
  padding: 0 20rpx 0 24rpx;
  border-radius: 16rpx;
  background: var(--sh-bg);
}
/* flex:1 + min-width:0 由 .sh-fill 给（模板上挂着），这里只留截断 */
.entry__t {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 未入驻的整屏空态：它带标题与主按钮，不是通用空态那一行灰字，所以留在页面里 */
/* 字号与颜色由 `sh-go` 给（24rpx / primary-text，即 `.sh-link` 那一档）——
   此前这里是 26rpx，五个同族调用点里唯一的一个例外，没有理由。 */
.entry__tag {
  flex-shrink: 0;
  margin-inline-end: 4rpx;
}
.applylink {
  display: flex;
  margin-top: 24rpx;
}
.empty {
  text-align: center;
  padding: 80rpx 40rpx;
}
</style>
