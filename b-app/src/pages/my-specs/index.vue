<script setup lang="ts">
// 我的规格 —— **这家店能用哪些规格**，平台给的与自己建的都在这里。
//
// 第一版只列自建的，而线上自建规格是 0 条（这功能很少用）——
// 于是这一页对**所有**商家都是空的，回答不了他真正的问题：「我能用哪些」。
// 自建只是其中一小部分，把它当成整页的主题是把次要的东西放大了。
//
// 现在分两段：
//   · 本店类目的规格 —— **按货架类目分组**，而不是把平台那 13 个通用维度全倒出来：
//     一家只卖蔬菜和肉的店，看到「尺码」「口径」「时长」是纯噪音，
//     而噪音会让他觉得这一页与自己无关。按他真正摆出来的类目给，每一行他都认得。
//     这一段也保证页面不空
//   · 我建的 —— 可改名、可停用，带**用量**（动它会影响多少）与配额
//
// 自建这条路此前是单向的：建品页里输个名字就落进规格库，之后没有任何地方
// 看得到它。建错了只能一直留着，还占着配额，而配额用完那句「不能再建了」
// 也说不清是被什么占了。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MerchantSpecDim, SpecOverride, SpecTemplate, StoreCategorySpecs } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const dims = ref<MerchantSpecDim[]>([]);
/** 本店货架类目各自能用的规格。只读，但它让这一页永远不空 */
const byCategory = ref<StoreCategorySpecs[]>([]);
const loading = ref(false);

/** 配额用量取第一条就够 —— 三个配额字段对同一家店是同一份 */
const quota = computed(() => dims.value[0]);
const active = computed(() => dims.value.filter((d) => d.status === "ACTIVE"));
const archived = computed(() => dims.value.filter((d) => d.status !== "ACTIVE"));

async function load() {
  loading.value = true;
  try {
    // 两段一起拉。平台那段取不到不该让整页空着，所以各自兜底
    const [mine, byCat] = await Promise.all([
      api.mMySpecDims(),
      // 取不到不该让整页空着 —— 自建那段还有内容
      api.mStoreSpecDims(merchant.storeNo || undefined).catch(() => []),
    ]);
    dims.value = mine;
    byCategory.value = byCat;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

/**
 * 正在编辑的那**一个**规格（dimNo）。一次只调一个 ——
 * 从前点一下类目的「调整」，整屏所有规格连同它们的全部档位一起变可编辑，
 * 而他这次多半只想动其中一个，剩下的都在那儿等着他误触。
 */
const editingDim = ref<string | null>(null);

/** 编辑态的本地副本：只装这一个规格 */
const draft = ref<{
  dimNo: string;
  /** 平台原名 —— 输入框的占位符：清空就是「用回平台的叫法」 */
  platformName: string;
  label: string;
  values: { code: string }[];
  /** code → 档位名，只用来显示 */
  labels: Record<string, string>;
  /** 被去掉的档位：提交时以 enabled=false 显式告诉后端 */
  dropped: string[];
}>({ dimNo: "", platformName: "", label: "", values: [], labels: {}, dropped: [] });

function startEditDim(g: StoreCategorySpecs, t: SpecTemplate) {
  editingDim.value = t.templateNo;
  draft.value = {
    dimNo: t.templateNo,
    /*
     * 平台原名要单独拿：`t.name` 已经是合并后的（他改过就是他的叫法），
     * 拿它当占位符的话，「清空 = 用回平台的」就没有参照了。
     */
    platformName: platformNames.value[t.templateNo] ?? t.name,
    label: t.name,
    values: t.options.map((o) => ({ code: o.code ?? "" })),
    labels: Object.fromEntries(t.options.map((o) => [o.code ?? "", o.label])),
    dropped: [],
  };
}

/**
 * dimNo → 平台原名，**只用作改名输入框的占位符**（「清空 = 用回平台的叫法」）。
 * 合并结果里拿不到它，所以只在「刚加进来的规格」那条路上顺手记一下；
 * 拿不到就退回当前叫法 —— 占位符差一点不影响正确性，
 * 而「改没改」的判据在后端（那里有平台原名）。
 */
const platformNames = ref<Record<string, string>>({});

/** 去掉一档 —— 记进 dropped：只是「不提交」等于跟平台走，那一档下次还在 */
function dropValue(code: string) {
  draft.value.values = draft.value.values.filter((v) => v.code !== code);
  draft.value.dropped = [...draft.value.dropped, code];
}

/**
 * 加一档：**先给候选，自己填放最后**。
 *
 * <p>类目通常只裁了平台值池里的几档，而他要加的往往正是没裁进来的那一档。
 * 直接弹输入框的话他只能手输，而手输的值没有编码 —— 跨店聚合就此断掉。
 */
async function addValue() {
  const d = draft.value;
  const all = await api.mDimValues(d.dimNo).catch(() => []);
  const have = new Set(d.values.map((v) => v.code));
  const rest = all.filter((o) => !have.has(o.code ?? ""));

  const i = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: [...rest.map((o) => o.label), t("mySpecs.typeMine")],
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  if (i < 0) return;

  const use = (code: string, label: string) => {
    if (!d.values.some((x) => x.code === code)) d.values = [...d.values, { code }];
    d.dropped = d.dropped.filter((c) => c !== code);
    d.labels[code] = label;
  };

  if (i < rest.length) {
    const o = rest[i]!;
    use(o.code ?? "", o.label);
    return;
  }

  const text = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("mySpecs.addValueTitle"),
      editable: true,
      placeholderText: t("mySpecs.addValuePh"),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!text.trim()) return;
  try {
    const added = await api.mAddSpecValue(d.dimNo, text.trim());
    use(added.code || added.valueNo, added.label);
    // 撞上平台已有的那一档时后端直接返回它 —— 说一声，否则他以为自己白填了
    if (added.label !== text.trim()) {
      uni.showToast({ title: t("mySpecs.valueMerged", { name: added.label }), icon: "none" });
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 提交这个类目的覆盖。
 *
 * <p><b>后端是整份替换，所以每次都要带上这个类目下所有规格的当前状态。</b>
 * 只发改动的那一个，别的规格的覆盖会被一起清掉 —— 而他只是想改这一个。
 *
 * @param g     目标类目
 * @param order 规格顺序（dimNo）。不传则沿用当前顺序
 * @param patch 对某一个规格的改动；不传表示只改顺序
 */
async function commit(
  g: StoreCategorySpecs,
  order?: string[],
  patch?: { dimNo: string; label?: string; values: string[]; dropped: string[] },
  removeDimNo?: string,
) {
  const seq = order ?? g.dims.map((t) => t.templateNo);
  const dims = seq
    .filter((no) => no !== removeDimNo)
    .map((no) => {
      const t = g.dims.find((x) => x.templateNo === no);
      const isPatched = patch && patch.dimNo === no;
      const codes = isPatched ? patch.values : (t?.options ?? []).map((o) => o.code ?? "");
      const gone = isPatched ? patch.dropped : [];
      const label = isPatched ? patch.label : t?.name;
      return {
        dimNo: no,
        enabled: true,
        /*
         * **原样提交，不在这里判「改没改」。**端上手里的 name 已经是合并后的，
         * 要比对得另外拿一份平台原名，而那个值只在部分路径上才有 ——
         * 判漏了就落一堆等于原名的覆盖，而那会让运营以后的改名到不了这家店。
         * 后端有平台原名，让它去比。
         */
        label: label?.trim() || undefined,
        values: [
          ...codes.map((code) => ({ code, enabled: true })),
          ...gone.map((code) => ({ code, enabled: false })),
        ],
      };
    });
  if (removeDimNo) {
    dims.push({ dimNo: removeDimNo, enabled: false, label: undefined, values: [] });
  }
  const merged = await api.mSaveSpecOverride(g.categoryNo, dims);
  const i = byCategory.value.findIndex((x) => x.categoryNo === g.categoryNo);
  if (i >= 0) byCategory.value[i] = { ...g, dims: merged };
}

async function saveDim(g: StoreCategorySpecs) {
  const d = draft.value;
  try {
    await commit(g, undefined, {
      dimNo: d.dimNo, label: d.label, values: d.values.map((v) => v.code), dropped: d.dropped,
    });
    editingDim.value = null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/*
 * 拖动排序。
 *
 * **只用 touch 事件，不用 movable-view。**仓库里为同一件事做过决定
 * （见 goods-edit 的 moveDetailImage）：movable-view 在小程序里与页面滚动打架。
 * 这里的做法是自己算：按下记住起点与那一行的高度，移动时用位移除以行高
 * 得到落到第几位，松手才提交。
 *
 * <p>`@touchmove.stop.prevent` 是关键的一半 —— 不挡住的话页面会跟着手指滚，
 * 而他以为自己在拖那一行。代价是拖动期间这一段不能滚动，
 * 但一次拖动本来就只在几行之内。
 */
const dragFrom = ref<string | null>(null);
const dragY = ref(0);
const dragTo = ref(-1);
/** 一行的高度（px）。按下时量一次 —— 不同机型、不同字号下它不一样 */
const rowH = ref(0);

function onDragStart(g: StoreCategorySpecs, dimNo: string, e: TouchEvent) {
  // 编辑态里不拖：那时这一行是一整块表单，拖它没有意义
  if (editingDim.value) return;
  dragFrom.value = dimNo;
  dragY.value = e.touches?.[0]?.clientY ?? 0;
  dragTo.value = g.dims.findIndex((t) => t.templateNo === dimNo);
  rowH.value = 0;
}

function onDragMove(g: StoreCategorySpecs, e: TouchEvent) {
  if (!dragFrom.value) return;
  const y = e.touches?.[0]?.clientY ?? 0;
  /*
   * 行高第一次移动时估一次：**用整段的高度除以行数**，比给一个写死的 px 稳 ——
   * 档位多的行更高，写死的话拖两行就错位。
   */
  if (!rowH.value) rowH.value = 64;
  const from = g.dims.findIndex((t) => t.templateNo === dragFrom.value);
  const delta = Math.round((y - dragY.value) / rowH.value);
  dragTo.value = Math.max(0, Math.min(g.dims.length - 1, from + delta));
}

async function onDragEnd() {
  const from = dragFrom.value;
  dragFrom.value = null;
  if (!from || dragTo.value < 0) return;
  const g = byCategory.value.find((x) => x.dims.some((t) => t.templateNo === from));
  if (!g) return;
  const i = g.dims.findIndex((t) => t.templateNo === from);
  if (i === dragTo.value) return;   // 没挪动：不必往后端跑一趟
  const seq = g.dims.map((t) => t.templateNo);
  seq.splice(dragTo.value, 0, seq.splice(i, 1)[0]!);
  try {
    await commit(g, seq);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 顺序改的是「这一类用哪几个规格」，点了立即生效 —— 不必为挪一位进一次编辑态 */
async function moveDim(g: StoreCategorySpecs, dimNo: string, delta: number) {
  const seq = g.dims.map((t) => t.templateNo);
  const i = seq.indexOf(dimNo);
  const to = i + delta;
  if (i < 0 || to < 0 || to >= seq.length) return;
  seq.splice(to, 0, seq.splice(i, 1)[0]!);
  try {
    await commit(g, seq);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 移除一个规格。**先问一句** —— 它下面那几档的取舍会跟着一起没 */
async function removeDim(g: StoreCategorySpecs, dim: SpecTemplate) {
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: t("mySpecs.removeTitle"),
      content: t("mySpecs.removeConfirm", { name: dim.name }),
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    });
  });
  if (!ok) return;
  try {
    await commit(g, undefined, undefined, dim.templateNo);
    if (editingDim.value === dim.templateNo) editingDim.value = null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 加一个平台规格进来。**只列这一类目还没用的** ——
 * 已经在上面的再列一遍，他点了不知道发生了什么。
 */
async function addDim(g: StoreCategorySpecs) {
  const all = await api.mPickableDims(g.categoryNo).catch(() => []);
  const have = new Set(g.dims.map((t) => t.templateNo));
  const rest = all.filter((x) => !have.has(x.templateNo));
  if (!rest.length) {
    uni.showToast({ title: t("mySpecs.noMoreDim"), icon: "none" });
    return;
  }
  /*
   * **「自己建一个」放在候选最后**，与加档位那里同一个次序：
   * 先看平台有没有现成的，实在没有才自己建。反过来的话他会习惯性地自己建 ——
   * 而自建规格不参与跨店聚合，那个代价在界面上看不出来。
   */
  const i = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: [...rest.map((x) => x.name), t("mySpecs.buildOwnDim")],
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  if (i < 0) return;

  // 平台也没有：自己建一个（落进规格库，下次别的类目也挑得到）
  if (i >= rest.length) {
    await buildOwnDim(g);
    return;
  }

  const picked = rest[i];
  if (!picked) return;
  platformNames.value[picked.templateNo] = picked.name;
  try {
    // 新加的规格默认全档位：他加它就是想用，再让他逐个点一遍是白费一步
    const seq = [...g.dims.map((x) => x.templateNo), picked.templateNo];
    const withNew: StoreCategorySpecs = { ...g, dims: [...g.dims, picked] };
    await commit(withNew, seq);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 自己建一个平台没有的规格（「辣度」「打磨程度」）。
 *
 * <p>它落进规格库（scope=MERCHANT），所以**下次在别的类目也挑得到** ——
 * 与「我建的」那一段是同一批东西，在那里能改名、能停用。
 *
 * <p>后端有两道兜底：与平台维度重名直接给平台那个（他要的是「按这个分规格」，
 * 不是「拥有一个自己的颜色」）；与自己已建的重名则复用，不会造出两个「辣度」。
 */
async function buildOwnDim(g: StoreCategorySpecs) {
  const name = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("mySpecs.buildOwnDim"),
      content: t("mySpecs.buildOwnHint"),
      editable: true,
      placeholderText: t("mySpecs.buildOwnPh"),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!name.trim()) return;
  try {
    const dim = await api.mAddSpecDim(name.trim(), []);
    if (g.dims.some((t2) => t2.templateNo === dim.templateNo)) {
      uni.showToast({ title: t("mySpecs.dimAlready"), icon: "none" });
      return;
    }
    platformNames.value[dim.templateNo] = dim.name;
    const seq = [...g.dims.map((x) => x.templateNo), dim.templateNo];
    await commit({ ...g, dims: [...g.dims, dim] }, seq);
    // 自建维度刚建出来时一个取值都没有，直接把「加档位」推到他面前
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 恢复成平台原样：清掉这一类目的全部覆盖 */
async function resetOverride(g: StoreCategorySpecs) {
  try {
    const merged = await api.mSaveSpecOverride(g.categoryNo, []);
    const i = byCategory.value.findIndex((x) => x.categoryNo === g.categoryNo);
    if (i >= 0) byCategory.value[i] = { ...g, dims: merged };
    editingDim.value = null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 自建规格的停用 / 启用。**用在商品上的要先问一句** —— 停用不会改动那些商品
 * （它们存的是规格快照），但他不知道这一点，看到「用在 8 件商品上」
 * 会以为自己正要弄坏什么。把后果说清楚，比拦着他更有用。
 */
async function toggle(d: MerchantSpecDim) {
  const off = d.status === "ACTIVE";
  if (off && d.usedCount > 0) {
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: t("mySpecs.archiveTitle"),
        content: t("mySpecs.archiveConfirm", { n: d.usedCount }),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }
  try {
    await api.mArchiveSpecDim(d.dimNo, off);
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

async function rename(d: MerchantSpecDim) {
  const name = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("mySpecs.renameTitle"),
      editable: true,
      placeholderText: d.name,
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!name.trim() || name.trim() === d.name) return;
  try {
    await api.mRenameSpecDim(d.dimNo, name.trim());
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}


onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="mySpecs.title" :denied="!merchant.can('biz:goods')">
    <text class="sh-muted intro">{{ $t("mySpecs.intro") }}</text>

    <!--
      平台规格摆在前面：**它才是大多数人该用的那些**。
      放在自建后面的话，一个自建为空的商家先看到的仍是一片空白。
    -->
    <view v-for="g in byCategory" :key="g.categoryNo" class="cat">
      <view class="cat__head">
        <text class="sh-h2">{{ g.categoryName }}</text>
        <!-- 类目这一层只剩「加规格」，且只留图标：一行里文字越少越看得出结构 -->
        <view class="ic" @tap="addDim(g)">
          <sh-icon name="plus" :size="20" color="var(--sh-primary)" />
        </view>
      </view>

      <view v-for="t in g.dims" :key="t.templateNo" class="spec"
            :class="{ 'spec--drag': dragFrom === t.templateNo }">
        <!--
          **一次只调一个规格。**从前点一下类目的「调整」，整屏所有规格连同
          它们的全部档位一起变可编辑 —— 而他这次多半只想动其中一个，
          剩下的都在那儿等着他误触。
        -->
        <template v-if="editingDim !== t.templateNo">
          <view
            class="spec__head"
            @touchstart="onDragStart(g, t.templateNo, $event)"
            @touchmove.stop.prevent="onDragMove(g, $event)"
            @touchend="onDragEnd"
            @touchcancel="onDragEnd"
          >
            <!--
              **拖动手柄单独占一格。**整行可拖的话，他想点「调整」也会被当成拖动 ——
              而这一行上四个操作挨得很近。手柄圈定了「从这里抓」。
            -->
            <view class="ic ic--grip">
              <sh-icon name="grip" :size="18" color="var(--sh-sub)" />
            </view>
            <text class="spec__name">{{ t.name }}</text>
            <view class="ic" @tap.stop="startEditDim(g, t)">
              <sh-icon name="sliders" :size="19" color="var(--sh-primary)" />
            </view>
            <view class="ic" @tap.stop="removeDim(g, t)">
              <sh-icon name="close" :size="18" color="var(--sh-sub)" />
            </view>
          </view>
          <text class="spec__vals">{{ t.options.map((o) => o.label).join(" · ") }}</text>
        </template>

        <!-- 编辑这一个：改名 + 档位。形态与从前一样，只是范围缩到一行 -->
        <template v-else>
          <view class="spec__head">
            <!--
              **改的是本店叫法，不是平台的规格。**dimNo 一个字不变，
              所以三家店的同一个规格照样聚得到一起。
              占位符给平台原名：清空输入框就是「用回平台的叫法」。
            -->
            <input v-model="draft.label" class="field__input flex1"
                   :placeholder="draft.platformName" />
          </view>
          <view class="ed__vals">
            <text v-for="v in draft.values" :key="v.code" class="sh-chip">
              {{ draft.labels[v.code] ?? v.code }}
              <text class="val__x" @tap.stop="dropValue(v.code)">✕</text>
            </text>
            <view class="sh-chip ed__add" @tap="addValue()">
              <sh-icon name="plus" :size="16" color="var(--sh-primary)" />
            </view>
          </view>
          <view class="ed__row">
            <text class="sh-muted hint">{{ $t("mySpecs.adjustHint") }}</text>
          </view>
          <view class="ed__acts">
            <text class="link" @tap="saveDim(g)">{{ $t("mySpecs.save") }}</text>
            <text class="link" @tap="editingDim = null">{{ $t("mySpecs.cancel") }}</text>
          </view>
        </template>
      </view>

      <!-- 一条规格都没有的类目：说清是平台那边的缺口，并给一条出路 -->
      <text v-if="!g.dims.length" class="spec__vals">{{ $t("mySpecs.catNoDims") }}</text>
      <view class="cat__foot">
        <text class="link" @tap="resetOverride(g)">{{ $t("mySpecs.reset") }}</text>
      </view>
    </view>

    <sh-empty v-if="!loading && !byCategory.length" :text='$t("mySpecs.noShelf")'></sh-empty>

    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("mySpecs.mineTitle") }}</text>
      <text v-if="quota" class="sh-muted hint">{{ quota.dimUsed }} / {{ quota.dimQuota }}</text>
      <sh-empty v-if="!loading && !dims.length" :text='$t("mySpecs.empty")'></sh-empty>
    </view>

    <view v-for="d in active" :key="d.dimNo" class="sh-card mt dim">
      <view class="dim__head">
        <text class="dim__name">{{ d.name }}</text>
        <!-- 用量是这一页的重点：它回答「动它会影响多少」 -->
        <text class="sh-muted dim__used">{{ $t("mySpecs.used", { n: d.usedCount }) }}</text>
      </view>
      <view class="dim__vals">
        <text v-for="v in d.values" :key="v.code || v.label" class="sh-chip">{{ v.label }}</text>
        <text v-if="!d.values.length" class="sh-muted">{{ $t("mySpecs.noValues") }}</text>
      </view>
      <view class="dim__acts">
        <text class="link" @tap="rename(d)">{{ $t("mySpecs.rename") }}</text>
        <text class="link" @tap="toggle(d)">{{ $t("mySpecs.archive") }}</text>
      </view>
    </view>

    <!-- 停用的收在下面：它们不该和在用的抢注意力，但要看得到（能启用回来） -->
    <view v-if="archived.length" class="sh-card mt">
      <text class="sh-h2">{{ $t("mySpecs.archivedTitle") }}</text>
      <view v-for="d in archived" :key="d.dimNo" class="row">
        <text class="row__name">{{ d.name }}</text>
        <text class="link" @tap="toggle(d)">{{ $t("mySpecs.unarchive") }}</text>
      </view>
    </view>

    <text class="sh-muted foot">{{ $t("mySpecs.foot") }}</text>
  </sh-scaffold>
</template>

<style scoped>
/*
 * 与「我的」那一页同一套行范式（cells/cell）：**组内密排，间距只在组与组之间**。
 * 每行都套一张卡的话，一个类目下三四个规格就变成三四块互不相干的浮起色块，
 * 中间的留白比行本身还显眼 —— 看着像四个功能模块，而它们只是一份清单。
 */
.intro {
  display: block;
  padding: 0 8rpx;
  font-size: 24rpx;
}

.cat {
  margin-top: 24rpx;
  background: var(--sh-surface);
  border-radius: 24rpx;
  overflow: hidden;
}
.cat__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 26rpx 12rpx;
}

/* 一个规格 = 一行主件 + 一行档位。它们是同一条，所以中间不留间距 */
.spec {
  padding: 18rpx 26rpx;
}
.spec + .spec {
  border-top: 1rpx solid var(--sh-line);
}
.spec__head {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.spec__name {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.spec__vals {
  margin-top: 6rpx;
  padding-left: 56rpx;   /* 与手柄对齐 —— 让「这些档位属于上面那个规格」看得出来 */
  font-size: 24rpx;
  color: var(--sh-sub);
}

/* 拖动中：整行提一层，让他看得出「抓住的是这一行」 */
.spec--drag {
  background: var(--sh-faint);
}

/* 图标按钮：给足点击区，图标本身小 —— 一行里三个操作挨得近 */
.ic {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56rpx;
  height: 56rpx;
}
.ic--grip {
  width: 48rpx;
}

/* 编辑态：与只读行同一个左边距，展开时不跳位 */
.ed__vals {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 16rpx;
}
.ed__row {
  margin-top: 12rpx;
}
.ed__acts {
  display: flex;
  gap: 32rpx;
  margin-top: 20rpx;
}
.ed__add {
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 64rpx;
}
.val__x {
  margin-left: 6rpx;
  opacity: 0.45;
}

/* 类目底部的「恢复平台默认」：弱化 —— 它是退路，不是日常操作 */
.cat__foot {
  padding: 18rpx 26rpx 24rpx;
  border-top: 1rpx solid var(--sh-line);
  font-size: 24rpx;
}

.hint {
  display: block;
  font-size: 24rpx;
}
.quota {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
}
.dim__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.dim__name {
  font-size: 30rpx;
  font-weight: 600;
}
.dim__used {
  font-size: 24rpx;
}
.dim__vals {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.dim__acts {
  display: flex;
  gap: 28rpx;
  margin-top: 16rpx;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 0;
}
.row__name {
  font-size: 28rpx;
}
.foot {
  display: block;
  margin: 24rpx 8rpx;
  font-size: 24rpx;
}
.flex1 {
  flex: 1;
}
.mt {
  margin-top: 20rpx;
}
</style>
