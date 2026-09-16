<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 门店管理（M6）。
//
// 与「店铺设置」的分工：那一页管**一家店的门面**（公告/营业时间/地址/主推），
// 这一页管**有几家店、哪家是哪家**。分开是因为前者天天改、后者一年动不了几次，
// 且后者每个动作都有硬约束（额度、默认店唯一、收款号必须是自己的）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api, ApiError } from "@/api";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { CrossStoreOverview, MerchantPlan, PaymentApplyment, Store } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";

/**
 * 门店额度用尽（后端 `ErrorCode.STORE_QUOTA_EXCEEDED`）。
 *
 * **它与别的建店失败不是一类**：名字重了改个名就好，这个改什么都一样被拒 ——
 * 他要做的是升档或停用一家旧店。按普通 toast 处理的话，
 * 店主会以为是表单填错了，反复改门店名。
 */
const QUOTA_EXCEEDED = 70020;

const { t } = useI18n();

const stores = ref<Store[]>([]);
const plan = ref<MerchantPlan | null>(null);
const payments = ref<PaymentApplyment[]>([]);
/** 每家店今天怎么样。多店才取 —— 一家店的数字工作台上已经有了 */
const overview = ref<CrossStoreOverview | null>(null);
const busy = ref(false);

/** 新建表单：默认收起 —— 大多数商家只有一家店，天天看到一个空表单是噪音 */
const adding = ref(false);
const form = ref({ name: "", address: "" });
/**
 * 这家店挂在哪张证照下（02 屏）。
 *
 * <p><b>只有一张证照时这一步整个不渲染</b>（`merchant.multiEntity` 为 false）——
 * 给单证照商家一个只有一个选项的单选是纯负担，而这类噪音最终会让他连真正
 * 要选的那次也不看。空串 = 当前证照，与不传等价。
 *
 * <p>额度按证照算：挂到另一张下时撞的是**那张**的门店额度。所以这一步要在
 * 填名字之前 —— 先选证照，那句「门店 2/3」才说的是对的那张。
 */
const entityNo = ref("");
/**
 * 选的是不是「当前证照之外」的那一张。
 *
 * <p>用来把上面那句额度提示换成一句不含数字的话 —— 见模板里的说明：
 * `/biz/plan` 只给当前证照的额度，拿它去说另一张证照的事就是个错的数。
 */
const onOtherEntity = computed(() =>
  !!entityNo.value && entityNo.value !== merchant.profile?.merchantNo);

/** 可挑的收款号：只列**已开通**的。没开通的挂上去，下一单就收不了款 */
const payOptions = computed(() =>
  payments.value.filter((p) => p.canReceiveMoney && p.payMerchantNo),
);

onShow(load);

/** 这次没取到。**与「确定为空」是两件事** —— 整页内容都挂在拉来的数据后面 */
const failed = ref(false);

async function load() {
  // 门店列表就是这一页：兜成 `[]` 的结果是「还没有门店」，
  // 而店主看到这句会去建店 —— 建完发现原来那几家还在
  try {
    stores.value = await api.mStoreList();
    failed.value = false;
  } catch {
    failed.value = true;
  }
  /*
   * **每次都重取**，不是 ensure。这一页会建店、会停用店，而分组正是「哪张证照下有几家店」——
   * 用 ensure 的话建完店回到「我的」，那一行还写着建店之前的数字，
   * 而他刚做完的事就是让那个数字变大。
   */
  void merchant.loadEntityGroups();
  payments.value = await api.mPayments().catch(() => []);
  // 静默失败：拿不到套餐只是少一句额度提示，不该让这一页报错
  plan.value = await api.mMyPlan().catch(() => null);
  await loadOverview();
}

/**
 * 把「今天各店怎么样」取回来贴到门店卡上。
 *
 * <p>**列表就是总览**：原先这一页顶上还有一张「跨店总览」卡，下面才是门店列表 ——
 * 同一批门店在一屏里排了两遍，上面那张只是把人送去另一页再看一遍名字。
 * 现在数字直接长在卡上：想切店的人顺手就看见哪家忙，不必先决定「我是要切还是要看」。
 *
 * <p>没买跨店数据（70023）时**静默留白**：门店列表与套餐无关，
 * 少的只是几个数字，不该把这一页变成一张付费墙。想看的人点底部那行进详细对比，
 * 那一页有示例态和升档说明。
 */
async function loadOverview() {
  overview.value = null;
  if (stores.value.length < 2 || !merchant.can("biz:customer")) return;
  try {
    overview.value = await api.mCrossStoreOverview();
  } catch {
    // 没买（70023）与真的取不到（网络/500）在这一页是同一种后果：少几个数字。
    // 切店、改名、开新店都还能做，所以既不提示也不报错。
  }
}

/**
 * 门店 + 它今天的数。**在这里拼好再交给模板**：模板里反复调函数取同一行，
 * 既读不清也每次渲染都重算一遍。没有数的店 `stat` 为 null，那一块整个不画 ——
 * 一排「—」比留白更像坏了。
 */
const rows = computed(() =>
  stores.value.map((s) => ({
    ...s,
    stat: overview.value?.stores.find((x) => x.storeNo === s.storeNo) ?? null,
    currency: overview.value?.currency ?? "CNY",
  })));

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function create() {
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("stores.needName"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  void (async () => {
    try {
      await api.mCreateStore({
        name: form.value.name.trim(),
        address: form.value.address.trim(),
        ...(entityNo.value ? { entityNo: entityNo.value } : {}),
      });
      form.value = { name: "", address: "" };
      entityNo.value = "";
      adding.value = false;
    } catch (e) {
      // ★ 额度被挡**不走那个通用 toast** —— 见 onQuotaBlocked
      if (e instanceof ApiError && e.code === QUOTA_EXCEEDED) {
        await onQuotaBlocked();
      } else {
        uni.showToast({ title: (e as Error).message, icon: "none" });
      }
    } finally {
      busy.value = false;
      await load();
    }
  })();
}

/**
 * 额度被挡时的出路（步骤 4.2）。
 *
 * <p>**当场给出下一步**，而不是只说一句「额度不足」：他正要开第二家店，
 * 这是购买意图最明确的一刻 —— 把他打回一个 toast，这次意图就没了。
 *
 * <p>还没试用过的人**直接给「免费试用」**：需求原话是「不要只弹额度不足」。
 * 试用过的、或者已经是付费档的，跳到套餐页（那里有档位对比与联系方式）。
 */
async function onQuotaBlocked() {
  const p = plan.value ?? (await api.mMyPlan().catch(() => null));
  const canTrial = !!p?.trialTier;
  const ok = await confirm({
    title: String(t("plan.blockedTitle")),
    hint: String(t("plan.blockedBody", { name: p?.planName ?? "" })),
    confirmText: String(
      canTrial ? t("plan.blockedTrial", { n: p?.trialDays ?? 0 }) : t("plan.blockedView"),
    ),
  });
  if (!ok) return;
  if (!canTrial) {
    uni.navigateTo({ url: ROUTES.plan });
    return;
  }
  try {
    plan.value = await api.mStartTrial();
    uni.showToast({ title: String(t("plan.trialStarted")), icon: "none" });
    // 额度立即生效，所以**当场把他刚填的那家店建出来** ——
    // 让他再点一遍「保存」是把一次成功拆成两步，而中间那一步会掉人
    if (form.value.name.trim()) {
      await api.mCreateStore({
        name: form.value.name.trim(),
        address: form.value.address.trim(),
        ...(entityNo.value ? { entityNo: entityNo.value } : {}),
      });
      form.value = { name: "", address: "" };
      entityNo.value = "";
      adding.value = false;
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("plan.trialFailed")), icon: "none" });
  }
}

/**
 * 改名。
 *
 * **地址一起带过去**：`mRenameStore` 收的是整个 `StoreEditReq`，
 * 只传 name 的话地址会被后端当成「改成空」——「改个名字顺手把地址清了」
 * 是那种要过很久才有人发现的错。改地址本身另说，这里只保证不弄丢它。
 */
async function rename(s: Store) {
  const name = ((await prompt({
    title: String(t("stores.rename")),
    placeholder: String(t("stores.namePh")),
    value: s.name,
  })) ?? "").trim();
  if (!name || name === s.name) return;
  run(() => api.mRenameStore(s.storeNo, { name, address: s.address }));
}

/** 停用是「不再接新单」，已有的单照常履约 —— 文案要说清，否则没人敢点 */
function toggleStatus(s: Store) {
  run(() => api.mSetStoreStatus(s.storeNo, s.status !== "ACTIVE"));
}

function makeDefault(s: Store) {
  run(() => api.mSetDefaultStore(s.storeNo));
}

function goPlan() {
  uni.navigateTo({ url: ROUTES.plan });
}

/**
 * 切到这家店。**留在本页**而不是跳走：切完常常还要顺手看这家的收款号、
 * 员工数对不对。工作台等页面回来时按 onShow 重取，拿到的就是新店的数字。
 */
function switchTo(s: Store) {
  merchant.pickStore(s.storeNo);
  uni.showToast({ title: t("stores.switched", { name: s.name }), icon: "none" });
}

/**
 * 这家店现在能不能切过去。
 *
 * <p>**只对能进的店给**：停业店切过去，每一页都查出空数据，而人只会觉得
 * 「今天没单」。当前那家也不给 —— 点了什么都不会发生。
 *
 * <p>抽成函数是因为它现在有三个用处（整卡的点击区、卡片的可点样式、
 * 右下角那句提示）。散在模板里写三遍，迟早有一处忘了改。
 */
function canSwitchTo(s: Store) {
  return s.storeNo !== merchant.storeNo && s.status === "ACTIVE";
}

/**
 * 整张卡就是切店的点击区。
 *
 * <p>改版前只有一个小小的「切到这家」按钮可点，而卡片本体点了毫无反应 ——
 * 店主的原话是「点击后不能切换」。他点的是店名。
 *
 * <p>写成具名函数而不是模板里的 `canSwitchTo(s) && switchTo(s)`：
 * 那种内联短路表达式在小程序编译下不保险，而这一页三端都要跑。
 */
function onCardTap(s: Store) {
  if (canSwitchTo(s)) switchTo(s);
}

/**
 * 这张卡还有没有「状态操作」要摆（设为默认 / 停用 / 去看套餐 / 切换提示）。
 *
 * <p>没有就整行不渲染。默认店的动作只剩改名，而改名已经贴到店名旁边了 ——
 * 留一个空的带上边框的行，等于凭空多一条横线。
 */
function hasActs(s: Store) {
  return canSwitchTo(s) || s.planSuspended || !s.isDefault;
}

/** 传空 = 回到主体默认收款号，是合法操作 */
function pickPayment(s: Store, payMerchantNo?: string) {
  run(() => api.mSetStorePayment(s.storeNo, payMerchantNo));
}

</script>

<template>
  <sh-scaffold title-key="stores.title" :denied="!merchant.can('biz:store:admin')"
    :failed="failed"
    @retry="load"
  >
    <!--
      这一页只答**此刻**的两个问题：哪家在做什么（数字长在卡上），我要切到哪家。
      一段时间里谁更好是另一类问题，在「经营数据 › 跨店对比」——
      同一屏里既摆今天又摆近 30 天，两个数会被读成互相矛盾。
    -->
    <view
      v-for="s in rows"
      :key="s.storeNo"
      class="sh-card st"
      :class="{ 'st--switchable': canSwitchTo(s) }"
      @tap="onCardTap(s)"
    >
      <view class="st__top sh-row sh-row--between">
        <!--
          ★ **「重命名」贴着店名，不占一整行。**
          它改的就是这个名字，放在名字旁边是它本来该在的位置；而挪走之后，
          默认店那张卡（唯一动作就是改名）连动作行带那条分隔线一起消失 ——
          改版前那是一整行 + 一条横线只为放三个字。
        -->
        <view class="st__name sh-row">
          <text class="txt-title">{{ s.name }}</text>
          <text class="txt-caption st__rename" @tap.stop="rename(s)">{{ $t("stores.rename") }}</text>
        </view>
        <view class="tags">
          <text v-if="s.storeNo === merchant.storeNo" class="sh-chip sh-chip--primary">{{ $t("stores.currentTag") }}</text>
          <text v-if="s.isDefault" class="sh-chip">{{ $t("stores.default") }}</text>
          <!--
            ★ 两种只读必须分开显示：`status` 一模一样，而下一步完全不同 ——
            平台压的要补缴/升档，自己停的点一下启用就开。
            不分开的表现是店主反复点那个对降级店无效的「启用」。
          -->
          <text v-if="s.planSuspended" class="sh-chip sh-chip--danger">{{ $t("stores.planSuspended") }}</text>
          <text v-else-if="s.status !== 'ACTIVE'" class="sh-chip">{{ $t("stores.disabled") }}</text>
          <!--
            ★ **归集商户不显示这一条。** 钱先进平台户、再由平台结算给他，
            他压根不走自己的收款通道 —— 而 `payReady` 读的就是通道进件状态。
            于是它对每一家归集门店恒亮、也点不掉：这家店本来就能做生意。
            与工作台那条「还不能收款」是同一个缺陷的第二份。
          -->
          <text v-if="!s.payReady && !merchant.fundsAggregated" class="sh-chip sh-chip--danger">
            {{ $t("stores.payNotReady") }}
          </text>
        </view>
      </view>

      <text v-if="s.address" class="txt-caption addr">{{ s.address }}</text>

      <!--
        今天这家店怎么样。**待办三项照抄跨店总览的口径**（待发货/待自送/待备货）：
        待核销与待分拣是自提点维度、不限本商家，摆进门店卡会被读成「这家店的活」。
        为 0 的也留着，位置固定才形成肌肉记忆。
      -->
      <view v-if="s.stat" class="today">
        <text class="txt-sub today__line">
          {{ $t("stores.todayLine", {
            n: s.stat.todayOrders,
            gmv: money(s.stat.todayGmvMinor, s.currency),
          }) }}
        </text>
        <view class="todo">
          <view class="todo__i">
            <text class="txt-title todo__v sh-num" :class="s.stat.toShip ? 'txt-primary' : 'txt-faint'">
              {{ s.stat.toShip }}
            </text>
            <text class="txt-caption todo__l">{{ $t("crossStore.toShip") }}</text>
          </view>
          <view class="todo__i">
            <text class="txt-title todo__v sh-num" :class="s.stat.toDeliver ? 'txt-primary' : 'txt-faint'">
              {{ s.stat.toDeliver }}
            </text>
            <text class="txt-caption todo__l">{{ $t("crossStore.toDeliver") }}</text>
          </view>
          <view class="todo__i">
            <text class="txt-title todo__v sh-num" :class="{ 'is-zero': !s.stat.toStock }">
              {{ s.stat.toStock }}
            </text>
            <text class="txt-caption todo__l">{{ $t("crossStore.toStock") }}</text>
          </view>
        </view>
      </view>

      <!--
        ★ **标签与取值同一行。**
        改版前「收款账户」与它的值、「授权员工」与它的数各占一行，于是一张卡上
        散着五六行长短不一的文字，眼睛要上下扫一遍才拼得出一条信息
        （店主原话：「主体默认号、改名、收款到都在不同的行，一下看不明白」）。
        标签定宽左列、取值右列，横着读完一条。
      -->
      <view class="facts">
        <view class="fact sh-row">
          <text class="txt-caption fact__k">{{ $t("stores.payment") }}</text>
          <!-- 收款号：空 = 用主体默认号，这是常态不是缺配置 -->
          <view class="fact__v sh-wrap">
            <text
              class="sh-chip"
              :class="{ 'sh-chip--primary': !s.payMerchantNo }"
              @tap.stop="pickPayment(s, undefined)"
            >
              {{ $t("stores.payDefault") }}
            </text>
            <text
              v-for="p in payOptions"
              :key="p.payMerchantNo"
              class="sh-chip"
              :class="{ 'sh-chip--primary': s.payMerchantNo === p.payMerchantNo }"
              @tap.stop="pickPayment(s, p.payMerchantNo)"
            >
              {{ p.channelName }}
            </text>
          </view>
        </view>
        <view class="fact sh-row">
          <text class="txt-caption fact__k">{{ $t("stores.staffLabel") }}</text>
          <text class="txt-body fact__v">{{ $t("stores.staffValue", { n: s.staffCount }) }}</text>
        </view>
      </view>

      <!--
        动作一排，**全部同一视觉层级**。改版前「切到这家」是按钮、其余是链接，
        四个动作两种样式，看着像两类不同的东西。
        切店已经由整张卡承担（见 st--switchable），这里不再重复一个按钮；
        改名挪去了店名旁边 —— 它改的就是那个名字。

        **整行带 v-if**：默认店没有任何状态操作，渲染一个空的带上边框的行
        等于凭空多一条横线，那正是「重命名独立一行浪费空间」的另一半。

        `@tap.stop` 一个都不能少：卡片本身是切店的点击区，
        不拦住冒泡的话，点「停用」会**顺带把当前店切过去**。
      -->
      <view v-if="hasActs(s)" class="acts">
        <text v-if="!s.isDefault && s.status === 'ACTIVE'" class="sh-link" @tap.stop="makeDefault(s)">
          {{ $t("stores.setDefault") }}
        </text>
        <!-- 默认店没有停用入口：后端也会拒，但按钮就不该出现在那儿 -->
        <!--
          降级压下的店**不给「启用」按钮**：点了后端也不会放行（额度还是不够），
          而一个点了没反应的按钮比没有按钮更让人困惑。给的是「去看套餐」。
        -->
        <text v-if="s.planSuspended" class="sh-link" @tap.stop="goPlan">{{ $t("stores.planSuspendedAct") }}</text>
        <text v-else-if="!s.isDefault" class="sh-link" @tap.stop="toggleStatus(s)">
          {{ s.status === "ACTIVE" ? $t("stores.disable") : $t("stores.enable") }}
        </text>
        <text v-if="canSwitchTo(s)" class="txt-caption acts__hint">{{ $t("stores.switchTo") }} ›</text>
      </view>
    </view>


    <view v-if="!adding" class="sh-btn sh-btn--soft add" @tap="adding = true">
      {{ $t("stores.add") }}
    </view>

    <view v-else class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("stores.add") }}</text>
      <!--
        额度说明放在表单里而不是报错后才说：让人白填一遍再被拒是没道理的。
        **带上真实数字**（「成长版 · 门店 2/3」）—— 一句泛泛的「有上限」
        既不能让他放心也不能让他行动。
      -->
      <!--
        ★ 选了**另一张**证照时不能再显示这个数。

        `plan` 来自 `/biz/plan`，问的是**当前证照**的额度；而额度是按证照算的
        （`mch_entity_plan` 挂在 `entity_no` 上）。照原样显示的话，他选了「张记水果」
        却看到「孵化版 · 门店 1/1」—— 那是另一张证照的数，他会以为自己建不了。
        而端上今天拿不到别张证照的额度（那个接口只给当前这张），所以这里
        **给一句诚实的话，而不是一个错的数**。
      -->
      <text class="sh-hint">
        {{ onOtherEntity
          ? $t("stores.quotaOnThatEntity")
          : (plan
            ? $t("plan.meSub", { name: plan.planName, used: plan.storeUsed, quota: plan.storeQuota })
            : $t("stores.quotaHint")) }}
      </text>

      <!--
        挂在哪张证照下。**只有多证照时才出现** —— 单证照商家看到的表单
        与多证照之前一模一样。放在店名之前：先定证照，上面那句「门店 2/3」
        才说的是对的那张证照的额度。
      -->
      <view v-if="merchant.multiEntity" class="field">
        <text class="field__label">{{ $t("stores.underEntity") }}</text>
        <view class="picks sh-wrap">
          <sh-option
            v-for="g in merchant.entityGroups"
            :key="g.entity.entityNo"
            class="pick"
            :selected="entityNo === g.entity.entityNo
              || (!entityNo && g.entity.entityNo === merchant.profile?.merchantNo)"
            @tap="entityNo = g.entity.entityNo"
          >
            <text class="txt-sub pick__name">{{ g.entity.name }}</text>
            <text class="txt-caption pick__sub">{{ $t("entities.storeCount", { n: g.entity.storeCount }) }}</text>
          </sh-option>
        </view>
        <text class="sh-hint">{{ $t("stores.underEntityHint") }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("stores.name") }}</text>
        <input maxlength="64" v-model="form.name" class="field__input" :placeholder="$t('stores.namePh')" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("stores.address") }}</text>
        <input maxlength="255" v-model="form.address" class="field__input" :placeholder="$t('stores.addressPh')" />
      </view>

      <view class="sh-btn submit" @tap="create">{{ $t("common.save") }}</view>
      <view class="sh-btn sh-btn--soft cancel" @tap="adding = false">{{ $t("common.cancel") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.picks {
  margin-top: 12rpx;
}
.pick {
  /* 只留版面：描边、圆角、选中态都归 sh-option */
  /* 内边距不再由调用点覆盖：小程序上这个 class 会同时落在宿主与组件根上，
     一份内边距吃两遍。档位归 sh-option（20rpx），两端也就一致了 */
  flex: 1 1 40%;
  min-width: 220rpx;
}
.pick__name {
  display: block;
  color: var(--sh-ink);
}
.pick__sub {
  display: block;
  margin-top: 4rpx;
}

/* 横向不再自己加内边距：页面边距由 sh-scaffold 统一给，这里再加一道，
   标题就比下方卡片多缩进一截（同一屏里两条左边界，看着像没对齐） */
/* `<text>` 默认 inline —— 不给 block，标题与这行说明会**挤在同一行**
   （「门店管理这里管有几家店…」），而 margin-top 对 inline 元素也不起作用。
   apply / login 两页早就是这么写的，payment / stores 漏了。 */

.tags {
  display: flex;
  gap: 8rpx;
}
.st__name {
  /* 店名可能很长，标签区要保得住：名字压缩，标签不被挤到下一行 */
  flex: 1;
  min-width: 0;
  margin-right: 16rpx;
  align-items: baseline;
  gap: 16rpx;
}
/* 改名是次要动作：跟着店名走，但不与店名争视线 */
.st__rename {
  flex: none;
  color: var(--sh-primary-text);
}
/* 整张卡是切店的点击区时给一个可点的暗示 —— 没有它，「能点」这件事无从得知 */
.st--switchable {
  border: var(--sh-hairline-soft);
}
.addr {
  display: block;
  margin-top: 8rpx;
}

/* 标签 + 取值横排。左列定宽，多条信息的取值才会对齐成一条竖线 */
.facts {
  margin-top: 20rpx;
  padding-top: 16rpx;
  border-top: var(--sh-hairline-soft);
}
.fact {
  align-items: flex-start;
}
.fact + .fact {
  margin-top: 16rpx;
}
.fact__k {
  width: 140rpx;
  flex: none;
  /* 与右侧取值的首行对齐：取值那边可能是 chip（带内边距），纯文本会偏上 */
  padding-top: 6rpx;
}
.fact__v {
  flex: 1;
  min-width: 0;
}
.acts {
  display: flex;
  align-items: center;
  gap: 28rpx;
  margin-top: 20rpx;
  padding-top: 16rpx;
  border-top: var(--sh-hairline-soft);
}
/* 「切换至此店 ›」推到最右：它说的是整张卡的行为，不是与左边并列的第四个动作 */
.acts__hint {
  margin-left: auto;
}
.field {
  margin-top: 20rpx;
}

.submit {
  margin-top: 28rpx;
}
.cancel {
  margin-top: 16rpx;
}

/* 今日一行 + 待办三格：与跨店总览同一套口径，也同一套样式 */
.today {
  margin-top: 16rpx;
  padding-top: 16rpx;
  border-top: var(--sh-hairline-soft);
}
.today__line {
  display: block;
  color: var(--sh-ink);
}
.todo {
  display: flex;
  margin-top: 16rpx;
}
.todo__i {
  flex: 1;
  text-align: center;
}
.todo__v {
  display: block;
}
.todo__l {
  display: block;
  margin-top: 8rpx;
}
</style>
