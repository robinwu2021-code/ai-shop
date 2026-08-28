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

async function load() {
  stores.value = await api.mStoreList().catch(() => []);
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

/** 传空 = 回到主体默认收款号，是合法操作 */
function pickPayment(s: Store, payMerchantNo?: string) {
  run(() => api.mSetStorePayment(s.storeNo, payMerchantNo));
}

</script>

<template>
  <sh-scaffold title-key="stores.title" :denied="!merchant.can('biz:store:admin')">
    <!--
      这一页只答**此刻**的两个问题：哪家在做什么（数字长在卡上），我要切到哪家。
      一段时间里谁更好是另一类问题，在「经营数据 › 跨店对比」——
      同一屏里既摆今天又摆近 30 天，两个数会被读成互相矛盾。
    -->
    <view v-for="s in rows" :key="s.storeNo" class="sh-card st">
      <view class="st__top sh-row sh-row--between">
        <text class="txt-title">{{ s.name }}</text>
        <view class="tags">
          <text v-if="s.storeNo === merchant.storeNo" class="txt-caption tag tag--primary">{{ $t("stores.currentTag") }}</text>
          <text v-if="s.isDefault" class="txt-caption tag">{{ $t("stores.default") }}</text>
          <!--
            ★ 两种只读必须分开显示：`status` 一模一样，而下一步完全不同 ——
            平台压的要补缴/升档，自己停的点一下启用就开。
            不分开的表现是店主反复点那个对降级店无效的「启用」。
          -->
          <text v-if="s.planSuspended" class="txt-caption tag is-danger">{{ $t("stores.planSuspended") }}</text>
          <text v-else-if="s.status !== 'ACTIVE'" class="txt-caption tag">{{ $t("stores.disabled") }}</text>
          <!-- 收不了钱要显眼：店开着但钱进不来，是最容易被忽略的一种坏 -->
          <text v-if="!s.payReady" class="txt-caption tag is-danger">{{ $t("stores.payNotReady") }}</text>
        </view>
      </view>

      <text v-if="s.address" class="addr">{{ s.address }}</text>
      <text class="txt-caption meta">{{ $t("stores.staffCount", { n: s.staffCount }) }}</text>

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

      <!-- 收款号：空 = 用主体默认号，这是常态不是缺配置 -->
      <view class="pay">
        <text class="txt-caption pay__label">{{ $t("stores.payment") }}</text>
        <view class="pay__opts sh-wrap">
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': !s.payMerchantNo }"
            @tap="pickPayment(s, undefined)"
          >
            {{ $t("stores.payDefault") }}
          </text>
          <text
            v-for="p in payOptions"
            :key="p.payMerchantNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': s.payMerchantNo === p.payMerchantNo }"
            @tap="pickPayment(s, p.payMerchantNo)"
          >
            {{ p.channelName }}
          </text>
        </view>
      </view>

      <view class="acts">
        <!--
          切当前店。**只对能进的店给**：停业店切过去，每一页都查出空数据，
          而人只会觉得「今天没单」。当前那家不给 —— 点了什么都不会发生。
        -->
        <text
          v-if="s.storeNo !== merchant.storeNo && s.status === 'ACTIVE'"
          class="sh-btn sh-btn--soft sh-btn--sm"
          @tap="switchTo(s)"
        >{{ $t("stores.switchTo") }}</text>
        <!--
          改名。后端与契约一直都在，**这一页却只有建店/停用/设默认/挂收款号四个动作** ——
          于是开错一个字的店名只能停用重建，而重建会丢掉这家店的历史。
        -->
        <text class="sh-link" @tap="rename(s)">{{ $t("stores.rename") }}</text>
        <text v-if="!s.isDefault && s.status === 'ACTIVE'" class="sh-link" @tap="makeDefault(s)">
          {{ $t("stores.setDefault") }}
        </text>
        <!-- 默认店没有停用入口：后端也会拒，但按钮就不该出现在那儿 -->
        <!--
          降级压下的店**不给「启用」按钮**：点了后端也不会放行（额度还是不够），
          而一个点了没反应的按钮比没有按钮更让人困惑。给的是「去看套餐」。
        -->
        <text v-if="s.planSuspended" class="sh-link" @tap="goPlan">{{ $t("stores.planSuspendedAct") }}</text>
        <text v-else-if="!s.isDefault" class="sh-link" @tap="toggleStatus(s)">
          {{ s.status === "ACTIVE" ? $t("stores.disable") : $t("stores.enable") }}
        </text>
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
  flex: 1 1 40%;
  min-width: 220rpx;
  padding: 16rpx 20rpx;
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
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.addr,
.meta {
  display: block;
  margin-top: 8rpx;
}
.pay {
  margin-top: 20rpx;
}
.pay__label {
  display: block;
}
.pay__opts {
  margin-top: 12rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 20rpx;
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
