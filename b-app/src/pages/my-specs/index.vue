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
import { computed, getCurrentInstance, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { MerchantSpecDim, SpecOverride, SpecTemplate, StoreCategorySpecs } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 本店货架类目各自能用的规格。只读，但它让这一页永远不空 */
const byCategory = ref<StoreCategorySpecs[]>([]);
const loading = ref(false);


async function load() {
  loading.value = true;
  try {
    // 两段一起拉。平台那段取不到不该让整页空着，所以各自兜底
    /*
     * **只拉按类目分组的那一份。**自建规格现在也在这份里（它加进哪个类目
     * 就出现在哪张卡），所以不必再单独拉「我建的」—— 那一段已经删掉，
     * 它按规格组织，与这一页按类目组织的模型对不上。
     */
    byCategory.value = await api.mStoreSpecDims(merchant.storeNo || undefined);
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

/**
 * 编辑态的键要**连类目一起**：同一个规格（SD_PACK「包装」）会出现在好几个类目下，
 * 只记 dimNo 的话，点开「好菜 · 包装」时「肉禽蛋 · 包装」也跟着展开 ——
 * 而他只想改一个。
 */
const editKey = (categoryNo: string, dimNo: string) => `${categoryNo}\u0000${dimNo}`;

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
  editingDim.value = editKey(g.categoryNo, t.templateNo);
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

/*
 * 拖动排序（规格行与档位共用同一套规矩）。
 *
 * **长按才进入拖动，松手才提交。** 上一版是「按下即拖 + 边拖边重排数组」，
 * 两个后果都被商家撞到了：
 *
 *   1. 误触 —— 点那一行的 ✕ 或齿轮时手指总会微动几像素，于是变成一次拖动，
 *      而他以为自己点的是按钮；
 *   2. **整排档位消失** —— 先点掉一档、手指还没抬起又动了一下，
 *      数组变短而拖动下标没跟着变，`splice(越界)` 返回空数组，
 *      `[0]` 是 undefined 被插进 values，整个 v-for 连「＋ 加档位」一起渲染不出来。
 *      不报错，只是空白一片。
 *
 * 现在：按住 ~180ms 才认，期间手指移动超过阈值就当滚动放弃；拖动中只有被拖的那个
 * 元素在动（translate），数组一个字不改，松手才提交一次。于是越界那条路不存在了，
 * 也才有地方放动效 —— 边拖边重排的话，元素每帧都在换位置，没有可动画的稳定态。
 */

/** 长按多久算「他要拖」。太短会把点击吞掉，太长会让人以为没反应 */
const HOLD_MS = 180;
/** 认定之前手指移动超过这么多 px 就当他在滚页面，放弃这次拖动 */
const SLOP = 10;

/**
 * 把 from 挪到 to，**越界就原样返回**。
 *
 * 上一版少的就是这个判断：`arr.splice(越界, 1)` 返回 `[]`，
 * 取 `[0]` 得到 undefined，再插回数组 —— 于是渲染整个塌掉。
 */
function moveItem<T>(arr: T[], from: number, to: number): T[] {
  if (from < 0 || from >= arr.length) return arr;
  const next = [...arr];
  const item = next.splice(from, 1)[0];
  if (item === undefined) return arr;
  next.splice(Math.max(0, Math.min(next.length, to)), 0, item);
  return next;
}

const instance = getCurrentInstance();

/** 正在拖的档位下标；-1 = 没在拖 */
const valDragFrom = ref(-1);
/** 手指按着但还没到 HOLD_MS —— 这个阶段什么都不做，抬手就是一次普通点击 */
const valPending = ref(-1);
/** 落点：拖动中只用来画插入位，不动数组 */
const valDragTo = ref(-1);
/** 被拖的 chip 相对起点的位移，直接喂给 transform */
const valShift = ref({ x: 0, y: 0 });
const valOrigin = ref({ x: 0, y: 0 });
let valTimer: ReturnType<typeof setTimeout> | null = null;
/** 每个 chip 的中心点，按下时量一次 */
const valBoxes = ref<{ x: number; y: number }[]>([]);

function clearValTimer() {
  if (valTimer) { clearTimeout(valTimer); valTimer = null; }
}

function onValDragStart(i: number, e: TouchEvent) {
  const t = e.touches?.[0];
  if (!t) return;
  valPending.value = i;
  valOrigin.value = { x: t.clientX, y: t.clientY };
  valShift.value = { x: 0, y: 0 };
  valBoxes.value = [];
  /*
   * **用 uni 的 createSelectorQuery 量位置，不从事件对象拿 DOM。**
   * uni 把事件包装过：`currentTarget` 在 H5 上不是 HTMLElement，
   * 在小程序上更没有 getBoundingClientRect —— 照 DOM 那样写，
   * 表现是「按下去什么都不发生」，而不会报错，很难看出原因。
   */
  uni.createSelectorQuery()
    .in(instance)
    .selectAll(".vals .val")
    .boundingClientRect((res) => {
      const rects = (Array.isArray(res) ? res : [res]) as UniApp.NodeInfo[];
      valBoxes.value = rects
        .slice(0, draft.value.values.length)
        .map((r) => ({
          x: (r.left ?? 0) + (r.width ?? 0) / 2,
          y: (r.top ?? 0) + (r.height ?? 0) / 2,
        }));
    })
    .exec();
  clearValTimer();
  valTimer = setTimeout(() => {
    // 手指还在原地按着 → 这是一次拖动。震一下告诉他「拿起来了」
    if (valPending.value !== i) return;
    valDragFrom.value = i;
    valDragTo.value = i;
    uni.vibrateShort?.({ success: () => {}, fail: () => {} });
  }, HOLD_MS);
}

function onValDragMove(e: TouchEvent) {
  const t = e.touches?.[0];
  if (!t) return;
  if (valDragFrom.value < 0) {
    // 还没认定：动得太多就是在滚页面，放弃（否则点 ✕ 时手抖也会变成拖动）
    const dx = t.clientX - valOrigin.value.x;
    const dy = t.clientY - valOrigin.value.y;
    if (dx * dx + dy * dy > SLOP * SLOP) { clearValTimer(); valPending.value = -1; }
    return;
  }
  valShift.value = { x: t.clientX - valOrigin.value.x, y: t.clientY - valOrigin.value.y };
  if (!valBoxes.value.length) return;
  // 离手指最近的那个 chip 就是落点。**只记下来，不动数组**
  let best = valDragFrom.value;
  let bestD = Infinity;
  valBoxes.value.forEach((b, i) => {
    const d = (b.x - t.clientX) ** 2 + (b.y - t.clientY) ** 2;
    if (d < bestD) { bestD = d; best = i; }
  });
  valDragTo.value = best;
}

function onValDragEnd() {
  clearValTimer();
  const from = valDragFrom.value;
  const to = valDragTo.value;
  valPending.value = -1;
  valDragFrom.value = -1;
  valDragTo.value = -1;
  valShift.value = { x: 0, y: 0 };
  valBoxes.value = [];
  if (from < 0 || to < 0 || from === to) return;
  draft.value.values = moveItem(draft.value.values, from, to);
  // 落位后闪一下：不给反馈的话，松手瞬间元素归位，看不出到底有没有生效
  valLanded.value = draft.value.values[to]?.code ?? "";
  setTimeout(() => { valLanded.value = ""; }, 320);
}

/** 刚落位的那一档，用来放一次「落定」动效 */
const valLanded = ref("");

/** 去掉一档 —— 记进 dropped：只是「不提交」等于跟平台走，那一档下次还在 */
function dropValue(code: string) {
  // 取消可能正在计时的那次长按：不取消的话，删完手指还没抬起，
  // 计时器照样把「拖动」点着，而它记的下标已经指不到东西了
  clearValTimer();
  valPending.value = -1;
  valDragFrom.value = -1;
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
/** 手指按着但还没到 HOLD_MS。这个阶段抬手 = 一次普通点击，不会重排 */
const dragPending = ref<string | null>(null);
const dragOriginY = ref(0);
const dragShift = ref(0);
const dragTo = ref(-1);
/** 一行的高度（px）。按下时量一次 —— 不同机型、不同字号下它不一样 */
const rowH = ref(64);
let rowTimer: ReturnType<typeof setTimeout> | null = null;

function clearRowTimer() {
  if (rowTimer) { clearTimeout(rowTimer); rowTimer = null; }
}

function onDragStart(g: StoreCategorySpecs, dimNo: string, e: TouchEvent) {
  const t = e.touches?.[0];
  if (!t) return;
  dragPending.value = dimNo;
  dragOriginY.value = t.clientY;
  dragShift.value = 0;
  dragTo.value = g.dims.findIndex((x) => x.templateNo === dimNo);
  // 行高按下时量一次：档位多的行更高，写死的话拖两行就错位
  uni.createSelectorQuery().in(instance).select(".spec")
    .boundingClientRect((r) => {
      const h = (r as UniApp.NodeInfo | null)?.height;
      if (h) rowH.value = h;
    })
    .exec();
  clearRowTimer();
  rowTimer = setTimeout(() => {
    if (dragPending.value !== dimNo) return;
    dragFrom.value = dimNo;
    uni.vibrateShort?.({ success: () => {}, fail: () => {} });
  }, HOLD_MS);
}

function onDragMove(g: StoreCategorySpecs, e: TouchEvent) {
  const t = e.touches?.[0];
  if (!t) return;
  if (!dragFrom.value) {
    // 还没认定就动了这么多 —— 他在滚页面，不是在拖这一行
    if (Math.abs(t.clientY - dragOriginY.value) > SLOP) { clearRowTimer(); dragPending.value = null; }
    return;
  }
  dragShift.value = t.clientY - dragOriginY.value;
  const from = g.dims.findIndex((x) => x.templateNo === dragFrom.value);
  const delta = Math.round(dragShift.value / (rowH.value || 64));
  dragTo.value = Math.max(0, Math.min(g.dims.length - 1, from + delta));
}

async function onDragEnd() {
  clearRowTimer();
  const from = dragFrom.value;
  const to = dragTo.value;
  dragPending.value = null;
  dragFrom.value = null;
  dragShift.value = 0;
  dragTo.value = -1;
  if (!from || to < 0) return;
  const g = byCategory.value.find((x) => x.dims.some((t) => t.templateNo === from));
  if (!g) return;
  const i = g.dims.findIndex((t) => t.templateNo === from);
  if (i === to) return;   // 没挪动：不必往后端跑一趟
  const seq = moveItem(g.dims.map((t) => t.templateNo), i, to);
  dimLanded.value = from;
  setTimeout(() => { dimLanded.value = ""; }, 320);
  try {
    await commit(g, seq);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 刚落位的那一行，用来放一次「落定」动效 */
const dimLanded = ref("");

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
    if (editingDim.value === editKey(g.categoryNo, dim.templateNo)) editingDim.value = null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 加规格：**页内展开一段候选，不弹层**。
 *
 * <p>候选有多少条取决于平台配了多少规格（现在 12 个，运营再加就更多），
 * 而弹层的高度由屏幕决定、不由内容决定 —— 它迟早会截断，
 * 且截断在小屏上没有任何提示（实测「颜色」与「自己建一个」都看不见）。
 */
const picking = ref<string | null>(null);
/** 这一类还能加的规格（已在用的不再列 —— 再列一遍他点了不知道发生了什么） */
const pickable = ref<SpecTemplate[]>([]);

/**
 * 候选**分两段**：这一类平台配过的、和平台池里其余的通用规格。
 *
 * <p>此前是一个平铺列表，于是「手机数码」下面并排摆着口味、等级、尺码 ——
 * 商家看到的是一堆与这一类毫无关系的东西。根因不是数据填错：
 * 那批维度标着 `universal`，而 `universal` 的判据是**「值的含义是否跨类目一致」**
 * （给跨店聚合用：锅的黑和手机的黑是同一个黑），它**没有**回答
 * 「哪些类目该用它」。拿前者当后者用，「数码 → 口味」是必然结果。
 *
 * <p>**不拦着他选** —— 商家确实可能有平台没想到的用法（他那家店的耳机按口味分？
 * 也许真有）—— 但不把二十来个无关维度摆在眼前。要用，展开一下就是。
 */
const pickCat = computed(() => pickable.value.filter((x) => x.categoryNo));
const pickRest = computed(() => pickable.value.filter((x) => !x.categoryNo));
/** 「其它平台规格」默认收起。展开一次就记住，别每换一个类目又收回去 */
const showRest = ref(false);
/** 自建配额。只在「加规格」这一刻有意义，所以放在面板里而不是页面顶部 */
const ownUsed = ref(0);
const ownMax = ref(10);

async function togglePick(g: StoreCategorySpecs) {
  if (picking.value === g.categoryNo) {
    picking.value = null;
    return;
  }
  picking.value = g.categoryNo;
  pickable.value = [];
  const [all, mine] = await Promise.all([
    api.mPickableDims(g.categoryNo).catch(() => []),
    api.mMySpecDims().catch(() => []),
  ]);
  const have = new Set(g.dims.map((t) => t.templateNo));
  pickable.value = all.filter((x) => !have.has(x.templateNo));
  ownUsed.value = mine.filter((d) => d.status === "ACTIVE").length;
  ownMax.value = mine[0]?.dimQuota ?? 10;
}

async function pickDim(g: StoreCategorySpecs, picked: SpecTemplate) {
  platformNames.value[picked.templateNo] = picked.name;
  try {
    // 新加的规格默认全档位：他加它就是想用，再让他逐个点一遍是白费一步
    const seq = [...g.dims.map((x) => x.templateNo), picked.templateNo];
    await commit({ ...g, dims: [...g.dims, picked] }, seq);
    picking.value = null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 自己建一个平台没有的规格（「辣度」「打磨程度」）。
 *
 * <p>它落进规格库（scope=MERCHANT），所以**下次在别的类目也挑得到**；
 * 加进哪个类目就在哪张卡里显示，带一个「本店」标记 —— 它不参与跨店比价，
 * 而那是看不见的差别。
 *
 * <p>后端有两道兜底：与平台维度重名直接给平台那个（他要的是「按这个分规格」，
 * 不是「拥有一个自己的颜色」）；与自己已建的重名则复用，不会造出两个「辣度」。
 */
async function buildOwnDim(g: StoreCategorySpecs) {
  const name = await new Promise<string>((resolve) => {
    /*
     * **editable 的弹框不能带 content。**uni 在 editable=true 时把 content
     * 当成输入框的**预填值**，不是说明文字 —— 于是那段解释被塞进输入框，
     * 他打开就看到一框字，还得先全删掉才能打自己的名字。
     * 该说的话在面板上（「只本店可用，不参与跨店比价」），那里不会挡着他输入。
     */
    uni.showModal({
      title: t("mySpecs.buildOwnDim"),
      editable: true,
      placeholderText: t("mySpecs.buildOwnPh"),
      success: (r) => resolve(r.confirm ? (r.content ?? "") : ""),
      fail: () => resolve(""),
    });
  });
  if (!name.trim()) return;
  try {
    const dim = await api.mAddSpecDim(name.trim(), []);
    if (g.dims.some((x) => x.templateNo === dim.templateNo)) {
      uni.showToast({ title: t("mySpecs.dimAlready"), icon: "none" });
      return;
    }
    platformNames.value[dim.templateNo] = dim.name;
    const seq = [...g.dims.map((x) => x.templateNo), dim.templateNo];
    await commit({ ...g, dims: [...g.dims, dim] }, seq);
    picking.value = null;
    // 刚建出来时一个档位都没有，那一行会显示「还没加档位」，把下一步推到他面前
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 恢复成平台原样：清掉这一类目的全部覆盖 *//** 恢复成平台原样：清掉这一类目的全部覆盖 */
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



onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="mySpecs.title" :denied="!merchant.can('biz:goods')">
    <text class="sh-muted intro">{{ $t("mySpecs.intro") }}</text>

    <view v-for="g in byCategory" :key="g.categoryNo" class="cat">
      <view class="cat__head">
        <text class="cat__name">{{ g.categoryName }}</text>
        <!--
          **带字的按钮，不是裸图标。**一个 ＋ 摆在标题栏里认不出是加什么 ——
          这一页上「加规格」与「加档位」是两件事，各自的入口离得不远。
          展开后同一个位置变「收起」：同一个按钮管开合，不必再找关掉它的地方。
        -->
        <view class="btn-add" :class="{ 'btn-add--on': picking === g.categoryNo }"
              @tap="togglePick(g)">
          <sh-icon :name="picking === g.categoryNo ? 'close' : 'plus'" :size="24"
                   :color="picking === g.categoryNo ? 'var(--sh-on-primary)' : 'var(--sh-primary)'" />
          <text class="btn-add__t">{{ picking === g.categoryNo ? $t("mySpecs.collapse") : $t("mySpecs.addDim") }}</text>
        </view>
      </view>

      <!--
        加规格：**页内展开一段，不弹层**。
        候选有多少条取决于平台配了多少规格（现在 12 个，运营再加就更多），
        而弹层的高度由屏幕决定、不由内容决定 —— 它迟早会截断，
        且截断在小屏上没有任何提示（实测「颜色」与「自己建一个」都看不见）。
      -->
      <view v-if="picking === g.categoryNo" class="picker">
        <view class="picker__head">
          <text class="sh-muted">{{ $t("mySpecs.pickHint") }}</text>
          <text class="sh-muted picker__quota">{{ $t("mySpecs.quotaShort", { used: ownUsed, max: ownMax }) }}</text>
        </view>
        <!-- 这一类平台配过的：默认摆出来，它们是平台针对这一类的判断 -->
        <view v-if="pickCat.length" class="chips">
          <text v-for="p in pickCat" :key="p.templateNo" class="sh-chip chip"
                @tap="pickDim(g, p)">{{ p.name }}</text>
        </view>
        <!-- 其余通用规格：收起。展开才出现，理由见 pickCat 上面那段 -->
        <view v-if="pickRest.length" class="picker__more">
          <text class="link" @tap="showRest = !showRest">
            {{ showRest ? $t("mySpecs.restHide") : $t("mySpecs.restShow", { n: pickRest.length }) }}
          </text>
        </view>
        <view v-if="showRest && pickRest.length" class="chips">
          <text v-for="p in pickRest" :key="p.templateNo" class="sh-chip chip"
                @tap="pickDim(g, p)">{{ p.name }}</text>
        </view>
        <text v-if="!pickable.length" class="sh-muted picker__empty">
          {{ $t("mySpecs.noMoreDim") }}
        </text>
        <!-- 自己建放最后：顺序即建议，先看平台有没有现成的 -->
        <view class="picker__own" @tap="buildOwnDim(g)">
          <text class="picker__own-t">＋ {{ $t("mySpecs.buildOwnDim") }}</text>
          <text class="sh-muted picker__own-s">{{ $t("mySpecs.buildOwnCost") }}</text>
        </view>
      </view>

      <view v-for="t in g.dims" :key="t.templateNo" class="spec"
            :class="{
              'spec--drag': dragFrom === t.templateNo,
              'spec--land': dimLanded === t.templateNo,
            }"
            :style="dragFrom === t.templateNo ? { transform: `translateY(${dragShift}px)` } : ''">
        <!-- 只读：一次只调一个规格，其余保持这一行的样子 -->
        <template v-if="editingDim !== editKey(g.categoryNo, t.templateNo)">
          <view class="spec__head">
            <!--
              **拖动只认手柄这一格。** 事件此前挂在整行上，与它上面那句注释正好相反 ——
              于是点右边的齿轮或 ✕ 时手指微动几像素就变成一次拖动，
              而他以为自己点的是按钮。这是「很容易误触」的全部原因。
            -->
            <!-- size 的单位是 rpx（见 sh-icon）—— 原型里手柄 14px ≈ 28rpx -->
            <view
              class="ic ic--grip"
              @touchstart="onDragStart(g, t.templateNo, $event)"
              @touchmove.stop.prevent="onDragMove(g, $event)"
              @touchend="onDragEnd"
              @touchcancel="onDragEnd"
            >
              <sh-icon name="grip" :size="28" color="var(--sh-sub)" />
            </view>
            <text class="spec__name">{{ t.name }}</text>
            <!-- 自建的标出来：它不参与跨店比价，而那是看不见的差别 -->
            <text v-if="t.scope === 'MERCHANT'" class="spec__own">{{ $t("mySpecs.own") }}</text>
            <view class="spec__spacer"></view>
            <view class="ic ic--act" @tap.stop="startEditDim(g, t)">
              <sh-icon name="sliders" :size="36" color="var(--sh-primary)" />
            </view>
            <view class="ic" @tap.stop="removeDim(g, t)">
              <sh-icon name="close" :size="34" color="var(--sh-ink)" />
            </view>
          </view>
          <!-- 单行省略：换行撑高的话，一屏就少看两个规格 -->
          <text class="spec__vals">{{ t.options.map((o) => o.label).join(" · ") || $t("mySpecs.noValueYet") }}</text>
        </template>

        <!-- 编辑这一个：整块浅色底，滚动时看得出「我在改哪一行」 -->
        <template v-else>
          <view class="edit">
            <!--
              **编辑态里也能拖。**他常常是「改着改着发现这一个该排前面」——
              为挪一位而先保存、再拖、再点回来，是三步做一件事。
            -->
            <!-- 同样只认手柄：挂在整行上的话，他点改名输入框就会被当成拖动 -->
            <view class="edit__row">
              <view
                class="ic ic--grip"
                @touchstart="onDragStart(g, t.templateNo, $event)"
                @touchmove.stop.prevent="onDragMove(g, $event)"
                @touchend="onDragEnd"
                @touchcancel="onDragEnd"
              >
                <sh-icon name="grip" :size="28" color="var(--sh-sub)" />
              </view>
              <input v-model="draft.label" class="edit__input" :placeholder="draft.platformName" />
            </view>
            <!--
              档位也能拖。**落点按 chip 的中心点算**，不像规格行那样按行高除 ——
              chip 是横向换行的二维排列，"位移 ÷ 行高" 在这里没有意义：
              同一行里左右挪一格与换到下一行，位移可能完全一样。
            -->
            <view class="vals">
              <text
                v-for="(v, vi) in draft.values"
                :key="v.code"
                class="sh-chip val"
                :class="{
                  'val--drag': valDragFrom === vi,
                  'val--slot': valDragFrom >= 0 && valDragTo === vi && valDragFrom !== vi,
                  'val--land': valLanded === v.code,
                }"
                :style="valDragFrom === vi
                  ? { transform: `translate(${valShift.x}px, ${valShift.y}px)` }
                  : ''"
                @touchstart="onValDragStart(vi, $event)"
                @touchmove.stop.prevent="onValDragMove($event)"
                @touchend="onValDragEnd"
                @touchcancel="onValDragEnd"
              >
                {{ draft.labels[v.code] ?? v.code }}
                <text class="val__x" @tap.stop="dropValue(v.code)">✕</text>
              </text>
              <text class="sh-chip val val--add" @tap="addValue()">＋ {{ $t("mySpecs.addValue") }}</text>
            </view>
            <text class="sh-muted edit__tip">{{ $t("mySpecs.renameTip") }}</text>
            <view class="edit__acts">
              <view class="sh-btn sh-btn--soft edit__btn" @tap="editingDim = null">
                {{ $t("mySpecs.cancel") }}
              </view>
              <view class="sh-btn edit__btn" @tap="saveDim(g)">{{ $t("mySpecs.save") }}</view>
            </view>
          </view>
        </template>
      </view>

      <text v-if="!g.dims.length && picking !== g.categoryNo" class="cat__empty">
        {{ $t("mySpecs.catNoDims") }}
      </text>

      <!--
        「恢复平台默认」= 撤销这一类目下的全部调整。压到最轻并放在最后：
        它与上面每一行的操作不是一个量级，长得一样重的话，
        手指下滑很容易顺手点掉自己刚调好的一切。
      -->
      <view class="cat__foot">
        <text class="cat__reset" @tap="resetOverride(g)">{{ $t("mySpecs.reset") }}</text>
      </view>
    </view>

    <sh-empty v-if="!loading && !byCategory.length" :text='$t("mySpecs.noShelf")'></sh-empty>

    <!--
      【已移除】页面底部原来那一段「我建的」。
      它按**规格**组织，而上面的卡按**类目**组织 —— 两个模型摞在一起，
      于是它落在整页第 662 字符（全页 728），7 张卡之后：不是没入口，是没人滚得到，
      滚到了也不知道它与上面什么关系。
      现在自建规格回到它所属的类目卡里，带「本店」标记，改名/停用就是那一行的两个图标；
      配额挪进了「加规格」面板 —— 那是唯一需要知道它的时刻。
    -->
    <text class="sh-muted foot">{{ $t("mySpecs.foot") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.picker__more {
  margin-top: 12rpx;
}

/*
 * 与「我的」那一页同一套行范式：**组内密排，间距只在组与组之间**。
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
  padding: 24rpx 26rpx 16rpx;
}
.cat__name {
  /* 字阶是 24/26/28/30/34/40/48 —— 32 不在上面，取 34 */
  font-size: 34rpx;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--sh-ink);
}
.cat__empty,
.cat__foot {
  display: block;
  padding: 18rpx 26rpx;
  border-top: 1rpx solid var(--sh-line);
}
.cat__empty {
  font-size: 24rpx;
  color: var(--sh-sub);
}
.cat__reset {
  font-size: 24rpx;
  color: var(--sh-sub);
}

/* 加规格按钮：带字，展开后同一位置变「收起」 */
.btn-add {
  display: flex;
  align-items: center;
  gap: 8rpx;
  padding: 10rpx 22rpx;
  border-radius: 9999px;
  background: var(--sh-primary-tint);
}
.btn-add--on {
  background: var(--sh-primary);
}
.btn-add__t {
  font-size: 24rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.btn-add--on .btn-add__t {
  color: var(--sh-on-primary);
}

/* 加规格面板：页内一段，不是弹层 */
.picker {
  margin: 0 26rpx 20rpx;
  padding: 20rpx 22rpx;
  /* 圆角五档：16/24/32/44/full —— 20 不在其中 */
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.picker__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 24rpx;
  margin-bottom: 16rpx;
}
.picker__quota {
  font-size: 24rpx;
}
.picker__empty {
  display: block;
  font-size: 24rpx;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
}
.chip {
  font-size: 24rpx;
}
.picker__own {
  margin-top: 18rpx;
  padding-top: 16rpx;
  border-top: 1rpx solid var(--sh-line);
}
.picker__own-t {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.picker__own-s {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
}

/* 一个规格 = 一行主件 + 一行档位。它们是同一条，所以中间不留间距 */
.spec {
  padding: 14rpx 26rpx;
  /*
    拖动中被拖的那一行走 transform，其余行位置不变（数组松手才改）——
    所以这里的过渡只负责「拿起来/放下去」两个瞬间，不会与手指位移打架。
  */
  transition: transform 0.18s ease, background-color 0.18s ease, box-shadow 0.18s ease;
}
.spec + .spec {
  border-top: 1rpx solid var(--sh-line);
}
/*
  拿起来的样子：抬一层 + 底色 + 轻微放大。
  **拖动中不再降透明度** —— 上一版把被拖的元素调到 0.5，
  在浅色底上看起来就是「字没了」，而那正是商家报的现象之一。
  要表达「这个被拿起来了」，抬阴影比抹掉它自己更准。
*/
.spec--drag {
  background: var(--sh-faint);
  box-shadow: 0 8rpx 24rpx rgba(0, 0, 0, 0.14);
  border-radius: 16rpx;
  /* 拖动中不要过渡 transform：否则元素追不上手指，像在拖一根皮筋 */
  transition: background-color 0.18s ease, box-shadow 0.18s ease;
  position: relative;
  z-index: 2;
}

/* 落定：松手后轻轻弹一下，否则看不出这次拖动到底有没有生效 */
.spec--land {
  animation: sh-land 0.32s ease;
}

@keyframes sh-land {
  0% { background-color: var(--sh-faint); }
  100% { background-color: transparent; }
}
.spec__head {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.spec__name {
  font-size: 28rpx;
  /* 一行里它是主角，用 400 会被下面那行档位拉成同一层。
     字阶只给 400/600/700 三档（守卫测住），所以取 600 而不是原型里的 500 */
  font-weight: 600;
  color: var(--sh-ink);
}
/* 自建的标出来 —— 它不参与跨店比价，而那是看不见的差别 */
.spec__own {
  margin-left: 8rpx;
  padding: 2rpx 10rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  /* 20 不在字阶上；24 是最小的一档 */
  font-size: 24rpx;
  color: var(--sh-sub);
}
.spec__spacer {
  flex: 1;
}
/* 单行省略：换行撑高的话，一屏就少看两个规格 */
.spec__vals {
  display: block;
  margin-top: 4rpx;
  line-height: 1.5;
  padding-left: 52rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 图标按钮：命中区 52rpx，图标 20rpx —— 手指够得着，眼睛不觉得挤 */
.ic {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52rpx;
  height: 52rpx;
  flex: none;
}
.ic--grip {
  width: 40rpx;
  margin-left: -10rpx;
}

/* 编辑态：整块浅色底 —— 滚动时看得出「我正在改哪一行」 */
.edit {
  margin: -14rpx -26rpx;
  padding: 20rpx 26rpx 24rpx;
  background: var(--sh-primary-tint);
}
.edit__row {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.edit__input {
  flex: 1;
  padding: 14rpx 20rpx;
  border-radius: 16rpx;
  border: 1rpx solid var(--sh-line);
  background: var(--sh-surface);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.vals {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 20rpx;
}
.val {
  font-size: 24rpx;
  transition: transform 0.18s ease, box-shadow 0.18s ease, background-color 0.18s ease;
}

/* 同上：抬起来，而不是变透明 */
.val--drag {
  box-shadow: 0 6rpx 18rpx rgba(0, 0, 0, 0.18);
  /* 跟手期间关掉 transform 过渡 */
  transition: box-shadow 0.18s ease, background-color 0.18s ease;
  position: relative;
  z-index: 2;
}

/* 落点提示：松手会插到这一档的位置上 —— 拖动中数组不变，只能靠它告诉他要落哪 */
.val--slot {
  background: var(--sh-faint);
  transform: translateX(8rpx);
}

.val--land {
  animation: sh-land-chip 0.32s ease;
}

@keyframes sh-land-chip {
  0% { transform: scale(1.12); }
  60% { transform: scale(0.97); }
  100% { transform: scale(1); }
}
.val--add {
  color: var(--sh-primary-text);
  border: 1rpx dashed var(--sh-primary);
}
.val__x {
  margin-left: 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.edit__tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
}
.edit__acts {
  display: flex;
  gap: 20rpx;
  margin-top: 24rpx;
}
.edit__btn {
  flex: 1;
}

.foot {
  display: block;
  margin: 28rpx 8rpx;
  font-size: 24rpx;
}
</style>
