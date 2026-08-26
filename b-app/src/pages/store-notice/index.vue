<script setup lang="ts">
/**
 * 公告：这一屏只做一件事 —— 说一句话给买家，立刻生效。
 *
 * <p><b>为什么独立成页</b>：它是店铺设置里唯一的日频操作（早上到货、下午售罄），
 * 此前和地址、营业时间挤在一页共用一个保存按钮 ——
 * 改一句话要连带提交整份门面，而店主并不知道那一按会写回什么。
 * 这里走独立写入口（/biz/store/announcement），不可能误伤其它字段。
 */
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const text = ref("");
const until = ref<number | null>(null);
const recentAll = ref<string[]>([]);
/** 正卡在人审里的那条。有它的时候，屏幕上「现在挂着的」和「我刚发的」不是同一句 */
const pending = ref<{ content: string; submittedAt: number } | null>(null);
/**
 * 店铺页上此刻真的挂着的那句。
 *
 * <p>**「撤下」按它判，不按输入框判**：有待审时输入框里是送审的那句，
 * 而店铺页上挂的仍是上一条 —— 两者不是同一件东西，混用会出现
 * 「什么都没挂却给撤下按钮」和「挂着却没有撤下」两种错。
 */
const live = ref("");

/**
 * 这两样要等后端。
 *
 * <p>「同时发到」与「常用里删一条」都依赖 2026-08-24 新增的端点，而线上后端还没有那一版
 * （`??` 的定义文件卡着，HEAD 编不过，见当天的部署记录）。**在那之前先藏起来**：
 * <ul>
 *   <li>同时发到：老后端会把 `alsoStoreNos` 静默丢掉 —— 商家勾了三家店、以为发出去了，
 *       没有任何信号告诉他只发了一家。<b>静默给出错误结果没有自愈路径</b>，最坏的一种。</li>
 *   <li>常用的 ✕：调过去 404，弹一句错误 toast。虽然不沉默，但点了删不掉、连点几次仍在，
 *       同样说不清原因。</li>
 * </ul>
 *
 * <p>后端上线后把这里改成 true，一行的事 —— 代码留着而不是删掉，是因为它们已经验过、
 * 也已经有后端用例守着（ServiceAreaFlowTest）。
 */
const BACKEND_READY = false;

/**
 * 「同时发到」勾中的门店号。**默认空** —— 公告里有相当一部分只对一家店成立
 * （「南门店今天停电」），默认勾上会把它发得到处都是，而这种错要等买家白跑一趟才发现。
 * 反过来「今天到货」三家都成立时，进三次店发三遍是纯粹的重复劳动，所以要有这个。
 */
const alsoStoreNos = ref<string[]>([]);
/** 可选的：本主体除当前店以外还在营业的店 */
const otherStores = computed(() =>
  merchant.stores.filter((x) => x.storeNo !== merchant.storeNo && x.status === "ACTIVE"));
function toggleAlso(storeNo: string) {
  const i = alsoStoreNos.value.indexOf(storeNo);
  if (i >= 0) alsoStoreNos.value.splice(i, 1);
  else alsoStoreNos.value.push(storeNo);
}
/** 服务端那份（判「改没改」）。没改动时发布键是灰的 */
const savedText = ref("");
const savedUntil = ref<number | null>(null);
const loaded = ref(false);
/**
 * 有没有可发布的改动。
 *
 * <p>**勾了「同时发到」也算**：同一句话原样发给另外几家店是一次真的发布，
 * 而正文没动。只看正文与有效期的话，勾完店发现按钮还是灰的 ——
 * 点不动的按钮不会让人去改正文，只会让人以为这功能坏了。
 */
const dirty = computed(() =>
  loaded.value && (text.value !== savedText.value || until.value !== savedUntil.value
    || alsoStoreNos.value.length > 0));

/**
 * 有效期三档。**存失效时刻**（epoch 毫秒）而不是「几天」——
 * 存天数的话，同一条公告在不同时刻保存会得到不同的到期时间。
 * 到期由服务端读时判断，不跑定时任务：这件事经不起漏一次，
 * 「昨天到货」挂一周比没有公告更伤信任。
 */
const TTL_OPTIONS = [
  { key: "today", ms: () => endOfToday() },
  { key: "d3", ms: () => Date.now() + 3 * 24 * 3600 * 1000 },
  { key: "forever", ms: () => null },
] as const;
type TtlKey = (typeof TTL_OPTIONS)[number]["key"];

/** 今天 23:59:59 —— 「今天有效」说的是今天结束，不是「24 小时后」 */
function endOfToday() {
  const d = new Date();
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

const ttlKey = ref<TtlKey>("forever");
function pickTtl(k: TtlKey) {
  ttlKey.value = k;
  const opt = TTL_OPTIONS.find((o) => o.key === k);
  until.value = opt ? opt.ms() : null;
}

/** 到期时刻的人话。只在设了有效期时出现 —— 「长期」没有到期这回事 */
const untilText = computed(() => {
  const at = until.value;
  if (!at) return "";
  const d = new Date(at);
  const sameDay = d.toDateString() === new Date().toDateString();
  const hh = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return t("store.ttl.until", {
    s: sameDay ? `${t("store.ttl.todayAt")} ${hh}` : `${d.getMonth() + 1}/${d.getDate()} ${hh}`,
  });
});

/** 送审时刻的人话。与到期时刻同一套写法（今天只给时分） */
const pendingAt = computed(() => {
  const at = pending.value?.submittedAt;
  if (!at) return "";
  const d = new Date(at);
  const hh = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  return d.toDateString() === new Date().toDateString()
    ? `${t("store.ttl.todayAt")} ${hh}`
    : `${d.getMonth() + 1}/${d.getDate()} ${hh}`;
});

/** 常用里不重复列正在编辑的这条 */
const recent = computed(() => recentAll.value.filter((x) => x && x !== text.value));

async function load() {
  try {
    await merchant.ensureStores().catch(() => null);
    const s = await api.mStore();
    text.value = s.announcement ?? "";
    until.value = s.announcementUntil ?? null;
    recentAll.value = s.announcementRecent ?? [];
    pending.value = s.noticePending ?? null;
    live.value = s.announcement ?? "";
    // 有待审时输入框显示的是**送审的那句**，不是店铺页上还挂着的旧公告 ——
    // 显示旧的，商家会以为自己刚才什么都没改
    if (pending.value) text.value = pending.value.content;
    savedText.value = text.value;
    savedUntil.value = until.value;
    const at = until.value;
    ttlKey.value = !at ? "forever"
      : new Date(at).toDateString() === new Date().toDateString() ? "today" : "d3";
    loaded.value = true;
  } catch {
    uni.showToast({ title: t("store.loadFailed"), icon: "none" });
  }
}

const saving = ref(false);
async function publish() {
  if (!dirty.value || saving.value) return;
  saving.value = true;
  try {
    const saved = await api.mSaveAnnouncement({
      announcement: text.value,
      announcementUntil: until.value,
      alsoStoreNos: alsoStoreNos.value.length ? [...alsoStoreNos.value] : undefined,
    });
    pending.value = saved.noticePending ?? null;
    /*
     * ★ 送审与发布是两个结果，必须分开说。
     *
     * 命中机审时后端**保留旧公告**并返回旧资料。此前这里无条件回填 `saved.announcement`
     * 并弹「已发布」—— 于是商家看到输入框换回上一条、提示说发布成功，
     * 只会以为自己手滑，再改一遍再送一次，队列里堆出一串同样的单子。
     */
    live.value = saved.announcement ?? "";
    if (!pending.value) {
      text.value = saved.announcement ?? text.value;
      recentAll.value = saved.announcementRecent ?? recentAll.value;
    }
    savedText.value = text.value;
    savedUntil.value = until.value;
    // 勾选不留到下一条：分发范围是「这一句发给谁」，不是一项设置
    alsoStoreNos.value = [];
    uni.showToast({
      title: pending.value ? t("store.noticeSubmitted") : t("store.noticeSaved"),
      icon: "none",
    });
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

/**
 * 撤下：把公告清空。
 *
 * <p>没有这个按钮时，「今天卖完了」要靠手动全选删字再点发布 ——
 * 而撤下是这一页仅次于发布的第二常用动作（挂上去的东西总要拿下来）。
 * 给一次确认：清空之后店铺页那一行就没了，而买家可能正照着它来。
 */
async function withdraw() {
  if (saving.value) return;
  const ok = await confirm({ title: String(String(t("store.noticeWithdraw"))), hint: String(String(t("store.noticeWithdrawAsk"))) });
  if (!ok) return;
  text.value = "";
  until.value = null;
  ttlKey.value = "forever";
  await publish();
}

/** 从常用里删一条。写错一次的那句不该赖在候选里，每次发公告都要绕过它 */
async function dropRecent(x: string) {
  if (saving.value) return;
  saving.value = true;
  try {
    const saved = await api.mDropNoticeRecent(x);
    recentAll.value = saved.announcementRecent ?? recentAll.value.filter((r) => r !== x);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="store.noticeTitle" :denied="!merchant.can('biz:store')">
    <view class="sh-card">
      <textarea
        v-model="text"
        class="field__area"
        :placeholder="$t('store.announcementPh')"
        maxlength="60"
      />

      <view class="ttl">
        <text
          v-for="o in TTL_OPTIONS"
          :key="o.key"
          class="sh-chip"
          :class="{ 'sh-chip--primary': ttlKey === o.key }"
          @tap="pickTtl(o.key)"
        >{{ $t(`store.ttl.${o.key}`) }}</text>
        <text v-if="untilText" class="ttl__at">{{ untilText }}</text>
      </view>

      <!-- 同时发到：只有多店主体看得到。默认不勾 —— 见 alsoStoreNos 上的说明 -->
      <view v-if="BACKEND_READY && otherStores.length" class="also">
        <text class="also__label">{{ $t("store.noticeAlso") }}</text>
        <view class="also__opts">
          <text
            v-for="o in otherStores"
            :key="o.storeNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': alsoStoreNos.includes(o.storeNo) }"
            @tap="toggleAlso(o.storeNo)"
          >{{ o.name }}</text>
        </view>
      </view>

      <view class="sh-btn go" :class="{ 'is-off': !dirty || saving }" @tap="publish">
        {{ saving ? "…" : $t("store.noticePublish") }}
      </view>

      <!-- 撤下：只在店铺页上真的挂着东西时出现 -->
      <text v-if="live" class="withdraw" @tap="withdraw">{{ $t("store.noticeWithdraw") }}</text>
    </view>

    <!--
      审核中。**摆在发布区下面、常用上面**：它说的是「你刚发的那句还没上」，
      看不到它的话，商家读到的是「已发布」而店铺页上什么都没变。
    -->
    <view v-if="pending" class="sh-card mt pend">
      <view class="pend__top">
        <text class="pend__tag">{{ $t("store.noticeAuditing") }}</text>
        <text class="pend__at">{{ pendingAt }}</text>
      </view>
      <text class="pend__text">{{ pending.content }}</text>
      <text class="pend__hint">{{ $t("store.noticeAuditingHint") }}</text>
    </view>

    <!-- 常用：店主的公告是在几句话之间轮换，不是每次都写新的。点一下换上，再点发布 -->
    <view v-if="recent.length" class="sh-card mt">
      <text class="field__label">{{ $t("store.noticeRecent") }}</text>
      <view class="recent">
        <view v-for="(r, i) in recent" :key="i" class="recent__row">
          <text class="recent__i" @tap="text = r">{{ r }}</text>
          <sh-icon-btn v-if="BACKEND_READY" name="close" @tap="dropRecent(r)"></sh-icon-btn>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.mt {
  margin-top: 24rpx;
}
/* 同时发到：与有效期同一档视觉，因为它们是同一件事的两个维度（多久、给谁） */
.also {
  margin-top: 24rpx;
}
.also__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.also__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
/* 有效期三档：分段小胶囊，选中靠主色底 —— 不做成按钮，它是「同一件事的三种时长」 */
.ttl {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-top: 16rpx;
}
.ttl__at {
  margin-left: auto;
  font-size: 24rpx;
  color: var(--sh-sub);
}
/* 发布：没有改动时灰着 —— 按下去什么都不会发生的按钮不该长得能按 */
.go {
  margin-top: 32rpx;
}
.go.is-off {
  background: var(--sh-faint);
  color: var(--sh-sub);
}
/* 撤下：文字链，不做成按钮 —— 它与发布不是一对平级动作 */
.withdraw {
  display: block;
  margin-top: 20rpx;
  text-align: center;
  font-size: 26rpx;
  color: var(--sh-sub);
}
/* 审核中：主色浅底，不用警示红 —— 这不是错误，是还没轮到 */
.pend {
  background: var(--sh-primary-tint);
}
.pend__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.pend__tag {
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.pend__at {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.pend__text {
  display: block;
  margin-top: 12rpx;
  font-size: 28rpx;
  line-height: 1.5;
  color: var(--sh-ink);
}
.pend__hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.field__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-sub);
  margin-bottom: 12rpx;
}
/* 常用是内容不是标签：不截断、允许换行 */
.recent {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.recent__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.recent__i {
  flex: 1;
  min-width: 0;
  padding: 16rpx 20rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  font-size: 26rpx;
  color: var(--sh-ink);
  line-height: 1.5;
}
</style>
