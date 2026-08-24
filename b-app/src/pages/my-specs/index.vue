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
import type { MerchantSpecDim, SpecOverride, StoreCategorySpecs } from "@shared/types";

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
 * 正在编辑的那个类目。**一次只编辑一个** —— 同时打开几个，
 * 「保存」按钮该保存哪一个就说不清了。
 */
const editing = ref<string | null>(null);
/** 编辑态的本地副本：dimNo → {enabled,label,values} */
const draft = ref<Record<string, SpecOverride>>({});
/** 名字只用来显示（不提交）：dimNo → {维度名, code → 档位名} */
const labels = ref<Record<string, { name: string; values: Record<string, string> }>>({});

/** 被移除的维度：收在下面能加回来，而不是消失 */
const removedDims = computed(() =>
  Object.keys(draft.value).filter((k) => !draft.value[k]!.enabled));

function dimLabel(g: StoreCategorySpecs, dimNo: string) {
  return labels.value[dimNo]?.name
    ?? g.dims.find((t) => t.templateNo === dimNo)?.name
    ?? dimNo;
}

/**
 * 加一个平台维度进来。**只列这一类目还没用的** ——
 * 已经在上面的再列一遍，他点了不知道发生了什么。
 */
async function addDim(g: StoreCategorySpecs) {
  const all = await api.mPickableDims(g.categoryNo).catch(() => []);
  const rest = all.filter((t) => !draft.value[t.templateNo]);
  if (!rest.length) {
    uni.showToast({ title: t("mySpecs.noMoreDim"), icon: "none" });
    return;
  }
  const i = await new Promise<number>((resolve) => {
    uni.showActionSheet({
      itemList: rest.map((t) => t.name),
      success: (r) => resolve(r.tapIndex),
      fail: () => resolve(-1),
    });
  });
  const pickedDim = rest[i];
  if (!pickedDim) return;
  draft.value = {
    ...draft.value,
    [pickedDim.templateNo]: {
      dimNo: pickedDim.templateNo,
      enabled: true,
      // 新加的维度默认全选：他加它就是想用，再让他逐个点一遍是白费一步
      values: pickedDim.options.map((o) => ({ code: o.code ?? "", enabled: true })),
    },
  };
  labels.value = {
    ...labels.value,
    [pickedDim.templateNo]: {
      name: pickedDim.name,
      values: Object.fromEntries(pickedDim.options.map((o) => [o.code ?? "", o.label])),
    },
  };
}

/**
 * 自己输入一个档位（「我这袋 750g」）。
 *
 * <p><b>落进规格库，不是落进覆盖表。</b>后端会把它挂在同一个平台维度下
 * 并抽出归一量（750g → 750 + g），于是它与平台的 500g 在同一根轴上 ——
 * 跨店比价照样成立。存进覆盖表的话它只是一个字符串，谁也比不了。
 *
 * <p>撞上平台已有的那一档时后端不新建、直接把那一档返回来，所以这里
 * 按返回的 code 去重 —— 否则他输「500g」会在界面上看到两个。
 */
async function addValue(g: StoreCategorySpecs, dimNo: string) {
  const text = await new Promise<string>((resolve) => {
    uni.showModal({
      title: t("mySpecs.addValueTitle"),
      content: t("mySpecs.addValueHint"),
      editable: true,
      placeholderText: t("mySpecs.addValuePh"),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!text.trim()) return;
  try {
    const v = await api.mAddSpecValue(dimNo, text.trim());
    const code = v.code || v.valueNo;
    const d = draft.value[dimNo];
    if (!d) return;
    const hit = (d.values ?? []).find((x) => x.code === code);
    if (hit) {
      hit.enabled = true;   // 撞上平台已有的那一档：勾上它，不新增
      uni.showToast({ title: t("mySpecs.valueMerged", { name: v.label }), icon: "none" });
    } else {
      d.values = [...(d.values ?? []), { code, enabled: true }];
      labels.value[dimNo]!.values[code] = v.label;
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

function startEdit(g: StoreCategorySpecs) {
  editing.value = g.categoryNo;
  /*
   * 从**当前看到的样子**建草稿，而不是从平台原样 —— 他现在看到的就是
   * 上次改完的结果，草稿与它对不上的话，一进编辑态界面就变了，
   * 而他没做任何事。
   */
  draft.value = Object.fromEntries(g.dims.map((t) => [t.templateNo, {
    dimNo: t.templateNo,
    enabled: true,
    values: t.options.map((o) => ({ code: o.code ?? "", enabled: true })),
  }]));
  /*
   * 维度名与档位名只用来显示 —— **不提交**。名字是跨店可比的锚，
   * 改它的后果（三家店把「重量」各叫一个名字）比省下的方便大得多；
   * 要别的说法就去建自定义规格，那条路本来就在。
   */
  labels.value = Object.fromEntries(g.dims.map((t) => [t.templateNo, {
    name: t.name,
    values: Object.fromEntries(t.options.map((o) => [o.code ?? "", o.label])),
  }]));
}

/** 维度顺序：草稿里的键序就是提交顺序，上移即交换 */
function moveDim(g: StoreCategorySpecs, dimNo: string, delta: number) {
  const keys = Object.keys(draft.value);
  const i = keys.indexOf(dimNo);
  const to = i + delta;
  if (i < 0 || to < 0 || to >= keys.length) return;
  keys.splice(to, 0, keys.splice(i, 1)[0]!);
  draft.value = Object.fromEntries(keys.map((k) => [k, draft.value[k]!]));
}

async function saveOverride(g: StoreCategorySpecs) {
  try {
    /*
     * **不提交任何 label。**名字不给改（见 startEdit 的说明），
     * 所以这里只有三件事：用哪几个（enabled）、什么顺序（数组次序）、
     * 每个维度下用哪几档（values[].enabled）。
     */
    const dims = Object.values(draft.value);
    const merged = await api.mSaveSpecOverride(g.categoryNo, dims);
    // 用后端合并后的结果就地替换，两边不各算一遍
    const i = byCategory.value.findIndex((x) => x.categoryNo === g.categoryNo);
    if (i >= 0) byCategory.value[i] = { ...g, dims: merged };
    editing.value = null;
    uni.showToast({ title: t("mySpecs.saved"), icon: "none" });
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
    editing.value = null;
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

/**
 * 停用 / 启用。**用在商品上的要先问一句** —— 停用不会改动那些商品
 * （它们存的是规格快照），但商家不知道这一点，看到「用在 8 件商品上」
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

onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="mySpecs.title" :denied="!merchant.can('biz:goods')">
    <text class="sh-muted intro">{{ $t("mySpecs.intro") }}</text>

    <!--
      平台规格摆在前面：**它才是大多数人该用的那些**。
      放在自建后面的话，一个自建为空的商家先看到的仍是一片空白。
    -->
    <view v-for="g in byCategory" :key="g.categoryNo" class="sh-card mt">
      <view class="cat__head">
        <text class="sh-h2">{{ g.categoryName }}</text>
        <text v-if="g.dims.length && editing !== g.categoryNo" class="link"
              @tap="startEdit(g)">{{ $t("mySpecs.adjust") }}</text>
      </view>

      <!-- 只读态 -->
      <template v-if="editing !== g.categoryNo">
        <view v-for="t in g.dims" :key="t.templateNo" class="pf">
          <text class="pf__name">{{ t.name }}</text>
          <text class="sh-muted pf__vals">{{ t.options.map((o) => o.label).join(" · ") }}</text>
        </view>
      </template>

      <!--
        编辑态。三件事在同一屏：用不用（开关）、什么顺序（↑↓）、叫什么（输入框）。
        分成三步走的话，他改完顺序还要再进两次才能改名 —— 而这三件本来就是
        「把这一类调成我习惯的样子」这一件事。
      -->
      <template v-else>
        <view v-for="(d, dimNo) in draft" :key="dimNo" v-show="d.enabled" class="ed">
          <view class="ed__head">
            <!--
              **规格名不给改。**它是跨店可比的锚 —— 三家店把「重量」各叫一个名字，
              界面上看着是三种东西，聚合时才发现是同一个，那种错更难查。
              要的是别的说法就该去建自定义规格（那条路本来就在）。
            -->
            <text class="ed__name flex1">{{ dimLabel(g, String(dimNo)) }}</text>
            <text class="del" @tap="moveDim(g, String(dimNo), -1)">↑</text>
            <text class="del" @tap="moveDim(g, String(dimNo), 1)">↓</text>
            <text class="del" @tap="d.enabled = false">{{ $t("mySpecs.remove") }}</text>
          </view>
          <view class="ed__vals">
            <!-- 平台档位：点一下决定本店用不用 -->
            <text
              v-for="v in d.values ?? []"
              :key="v.code"
              class="sh-chip"
              :class="{ 'sh-chip--primary': v.enabled }"
              @tap="v.enabled = !v.enabled"
            >{{ labels[String(dimNo)]?.values[v.code] ?? v.code }}</text>
            <!--
              **自己输入的值落进规格库，不是落进这张覆盖表。**
              后端会把它挂在同一个平台维度下并抽出归一量（「我这袋 750g」→ 750+g），
              于是它与平台的 500g 在同一根轴上 —— 跨店比价照样成立。
              存进覆盖表的话它就只是一个字符串，谁也比不了。
            -->
            <text class="sh-chip ed__add" @tap="addValue(g, String(dimNo))">
              ＋ {{ $t("mySpecs.addValue") }}
            </text>
          </view>
        </view>
        <!--
          移除掉的收在这里，**而不是消失** —— 他要加回来时，
          「刚才那个叫什么」是唯一还记得的线索；从平台全量维度里重找是另一回事。
        -->
        <view v-if="removedDims.length" class="ed__back">
          <text class="sh-muted pf__vals">{{ $t("mySpecs.removed") }}</text>
          <view class="ed__vals">
            <text v-for="dimNo in removedDims" :key="dimNo" class="sh-chip"
                  @tap="draft[dimNo]!.enabled = true">＋ {{ dimLabel(g, dimNo) }}</text>
          </view>
        </view>
        <text class="link" @tap="addDim(g)">＋ {{ $t("mySpecs.addDim") }}</text>
        <text class="sh-muted pf__vals">{{ $t("mySpecs.adjustHint") }}</text>
        <view class="ed__acts">
          <text class="link" @tap="saveOverride(g)">{{ $t("mySpecs.save") }}</text>
          <text class="link" @tap="editing = null">{{ $t("mySpecs.cancel") }}</text>
          <text class="link" @tap="resetOverride(g)">{{ $t("mySpecs.reset") }}</text>
        </view>
      </template>
      <!--
        没配规格的类目**也留着**，并说清这是平台那边的缺口 ——
        不显示的话商家只会觉得「这一类怎么没有规格」，而问不出来问谁。
      -->
      <text v-if="!g.dims.length" class="sh-muted pf__vals">{{ $t("mySpecs.catNoDims") }}</text>
    </view>

    <sh-empty v-if="!loading && !byCategory.length" :text='$t("mySpecs.noShelf")'></sh-empty>

    <view class="sh-card mt">
      <text class="sh-h2">{{ $t("mySpecs.mineTitle") }}</text>
      <text v-if="quota" class="sh-muted pf__vals">
        {{ quota.dimUsed }} / {{ quota.dimQuota }}
      </text>
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
.intro {
  display: block;
  font-size: 24rpx;
}
.mt {
  margin-top: 20rpx;
}
.cat__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.ed {
  padding: 12rpx 0;
  border-bottom: 1rpx solid var(--sh-line, transparent);
}
.ed__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.ed__on {
  min-width: 44rpx;
  text-align: center;
}
.ed__vals {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
  padding-left: 60rpx;
}
.ed__acts {
  display: flex;
  gap: 28rpx;
  margin-top: 20rpx;
}
.flex1 {
  flex: 1;
}

.pf {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
  padding: 10rpx 0;
}
.pf__name {
  min-width: 120rpx;
  font-size: 28rpx;
}
.pf__vals {
  font-size: 24rpx;
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
  margin: 24rpx 0;
  font-size: 24rpx;
}
</style>
