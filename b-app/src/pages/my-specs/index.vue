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
import { moveItem, useChipDrag, useRowDrag } from "./drag-sort";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { buildSpecOverride } from "@/utils/spec-override";
import type { MerchantSpecDim, SpecOption, SpecTemplate, StoreCategorySpecs } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 本店货架类目各自能用的规格。只读，但它让这一页永远不空 */
const byCategory = ref<StoreCategorySpecs[]>([]);

/*
 * 这一页管两件东西：**销售规格**（买家要挑一档，每档单独定价备库存）与
 * **商品参数**（只描述，不分 SKU、不影响价格）。
 *
 * <p>做成页面级切换而不是每张卡里再分两段：一次只管一件事，
 * 两段并排会让每张卡长一倍，而商家进来通常只为改其中一件。
 * 切换之后**所有交互原样复用** —— 拖动、改名、加减档位、加/移除，
 * 作用在哪个列表上由这里决定，不必写两遍（写两遍就迟早不一致）。
 */
const tab = ref<"dims" | "props">("dims");

/** 把新加的那条塞进**当前这一栏**的列表，另一栏原样带着 */
function withAdded(g: StoreCategorySpecs, one: SpecTemplate): StoreCategorySpecs {
  return tab.value === "dims"
    ? { ...g, dims: [...(g.dims ?? []), one] }
    : { ...g, props: [...(g.props ?? []), one] };
}

/** 当前这一栏在管的列表 */
function listOf(g: StoreCategorySpecs) {
  return (tab.value === "dims" ? g.dims : g.props) ?? [];
}
const loading = ref(false);

/**
 * 每个类目「还能加回来的」候选 —— **判据是「平台给这个类目配过、而你现在没有」**。
 *
 * <p>只认带 `categoryNo` 的那些：`mPickableDims` 同时给类目候选、平台通用、自建三段，
 * 把通用那段也摊在卡片下面的话，每张卡底下都会挂一长串与这一类无关的规格。
 * 通用与自建仍然走「添加规格」面板 —— 那里它们是被主动找的，这里是被动看见的。
 *
 * <p>为什么要摆出来：移除一个规格之后它就从卡片上消失了，而回去的路藏在
 * 「添加规格」弹层里。商家看到的是「删掉就没了」，那一栏还写着
 * 「平台尚未为本类目配置规格，可联系运营补充」—— **平台配了，是他自己移除的**。
 * 那句话把原因归给了平台，还让他去找运营。
 */
const addable = ref<Record<string, SpecTemplate[]>>({});

async function loadAddable() {
  const cats = byCategory.value;
  if (!cats.length) { addable.value = {}; return; }
  /*
   * ⚠️ **必须在 api 上调用，不能把方法摘下来存进变量。**
   * `const fetch = api.mPickableDims` 会丢掉 `this` —— mock 的实现里这些方法
   * 互相调用（`return this.mSpecTemplates(...)`），脱离对象就抛。
   * 而外面那个 `.catch(() => [])` 会把它吞成空数组，界面上就是「一个候选都没有」，
   * 与「平台真的没配」一模一样。**兜底把 bug 盖住了**，这是今天第三次。
   */
  const lists = await Promise.all(cats.map((g) => (tab.value === "dims"
    ? api.mPickableDims(g.categoryNo)
    : api.mPickableProps(g.categoryNo)).catch(() => [])));
  const next: Record<string, SpecTemplate[]> = {};
  cats.forEach((g, i) => {
    const have = new Set(listOf(g).map((t) => t.templateNo));
    next[g.categoryNo] = (lists[i] ?? []).filter((x) => x.categoryNo && !have.has(x.templateNo));
  });
  addable.value = next;
}

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
    // 候选跟着列表走。**不 await 在 try 里阻塞列表** —— 它取不到只是少一排 chip
    void loadAddable();
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
/** 正在编辑的那条属于哪个类目 —— 保存要用它，而弹层里没有 v-for 的 g */
const editingCat = ref<StoreCategorySpecs | null>(null);

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
  editingCat.value = g;
  // 从「加规格」那一步进来的：让弹层翻到档位页，而不是关掉再开一个
  picking.value = null;
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
  // 必须在 draft 落定之后 —— 它读的就是 draft.dimNo
  void loadValCands();
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

/** 长按多久算「他要拖」等两个常数、moveItem，与两套拖动实现都在 `./drag-sort.ts` */
const instance = getCurrentInstance();

/*
 * 档位那一排（chip）。**落位之后做什么留在这一页** ——
 * 这一栏只改本地草稿，不往后端跑：档位顺序要等他点「保存」才算数。
 */
const {
  dragFrom: valDragFrom, pending: valPending, dragTo: valDragTo, shift: valShift,
  onStart: onValDragStart, onMove: onValDragMove, onEnd: onValDragEnd,
  cancel: cancelValDrag,
} = useChipDrag(instance, ".vals .val", () => draft.value.values.length, (from, to) => {
  draft.value.values = moveItem(draft.value.values, from, to);
  // 落位后闪一下：不给反馈的话，松手瞬间元素归位，看不出到底有没有生效
  valLanded.value = draft.value.values[to]?.code ?? "";
  setTimeout(() => { valLanded.value = ""; }, 320);
});

/** 刚落位的那一档，用来放一次「落定」动效 */
const valLanded = ref("");

/** 去掉一档 —— 记进 dropped：只是「不提交」等于跟平台走，那一档下次还在 */
function dropValue(code: string) {
  // 取消可能正在计时的那次长按（理由见 drag-sort 里的 cancel）
  cancelValDrag();
  const label = draft.value.labels[code] ?? code;
  draft.value.values = draft.value.values.filter((v) => v.code !== code);
  draft.value.dropped = [...draft.value.dropped, code];
  /*
   * **删掉的那一档回到下面的候选里** —— 与 `pickValue` 的「加进来就从候选摘掉」
   * 互为反向。此前只有单向：加进来会从候选消失，删掉却不回去。
   *
   * <p>候选是**打开这一屏时算一次**（loadValCands），之后不再重算 ——
   * 所以少了这一步，删错一档就没有回头路：他要么关掉弹层重开一次（那会丢掉
   * 这一屏里其它没保存的调整），要么以为这一档被永久删掉了。
   *
   * <p>放在**最前面**：他刚删的那一档就在手指底下，点错了立刻点得回来。
   * 按平台顺序插回去的话，档位一多就要在一排虚线 chip 里找自己刚删的那个，
   * 而那正是最需要「一眼看见」的时刻。
   */
  if (!valCands.value.some((x) => (x.code ?? "") === code)) {
    valCands.value = [{ code, label }, ...valCands.value];
  }
}

/*
 * 加一档：**与「加规格」同一个弹层，不用系统控件。**
 *
 * <p>上一版是 `uni.showActionSheet` 排候选 + `uni.showModal` 手输，两级系统控件：
 * 排版不归我们管（「粗糙、字不齐」就是这么来的），候选一多就是一条长长的滚动条，
 * 而且「自己填」混在候选列表的最后一行 —— 它与前面那些不是一类东西，
 * 长得一样就等于没说清代价（自己填的那一档只有本店认得）。
 *
 * <p>现在与「加规格」一模一样：一排候选 chip 在上，自己填在下面单开一段。
 * 同一件事在两处长同一个样，商家学一次就够。
 */
/*
 * **档位与值是同一件事，两栏只是叫法不同。**销售规格下面那些叫「档位」
 * （10 斤、25 斤，每一档要单独定价备库存），商品参数下面那些叫「可选值」
 * （本地、国产，只是写给买家看）。交互一模一样，措辞跟着当前这一栏走 ——
 * 在参数栏里写「加档位」，他会以为填了就要多定一份价。
 */
const valueWord = computed(() => t(tab.value === "dims" ? "mySpecs.addValue" : "mySpecs.addPropValue"));
const ownValueWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.buildOwnValue" : "mySpecs.buildOwnPropValue"));
/*
 * 例子也得跟着换。参数栏里挂着「比如你这袋是 750g」，说的是重量分档 ——
 * 而他正在给「产地」加一个值。**举错例子比不举例更糟**：他会照着例子理解
 * 这一栏是干什么的，然后把参数当成规格用。
 */
/* 「加规格」那一屏也有两套词：在参数栏里说「平台可选规格」是说错了对象 */
const pickHintWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.pickHint" : "mySpecs.pickHintProps"));
const buildOwnPhWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.buildOwnPh" : "mySpecs.buildOwnPhProps"));
const noMoreValueWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.noMoreValue" : "mySpecs.noMorePropValue"));
const valuePhWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.addValuePh" : "mySpecs.addPropValuePh"));
const valueHintWord = computed(() =>
  t(tab.value === "dims" ? "mySpecs.addValueHint" : "mySpecs.addPropValueHint"));

const valCands = ref<SpecOption[]>([]);
const newVal = ref("");

/**
 * 取这个规格还能加的档位。**在打开档位那一屏时就取**，不再单开一层。
 *
 * <p>上一版「＋ 加档位」是弹层里的又一步，于是「已经有哪几档」和「还能加哪几档」
 * 分在两屏 —— 而他要做的判断（这一档我要不要）需要同时看见两边。
 */
async function loadValCands() {
  valCands.value = [];
  const d = draft.value;
  const all = await api.mDimValues(d.dimNo).catch(() => []);
  const have = new Set(d.values.map((v) => v.code));
  valCands.value = all.filter((o) => !have.has(o.code ?? ""));
}

/** 用上一档：**已经去掉过的要从 dropped 里摘回来**，否则提交时又被显式关掉 */
function useValue(code: string, label: string) {
  const d = draft.value;
  if (!d.values.some((x) => x.code === code)) d.values = [...d.values, { code }];
  d.dropped = d.dropped.filter((c) => c !== code);
  d.labels[code] = label;
}

function pickValue(o: SpecOption) {
  const code = o.code ?? "";
  useValue(code, o.label);
  // 加进来的从候选里摘掉：留着的话同一档在这一屏出现两次，点第二次什么也不会发生
  valCands.value = valCands.value.filter((x) => (x.code ?? "") !== code);
}

/** 平台没有的那一档（750g）：落进平台这个规格下，所以仍在同一根轴上 */
async function confirmNewValue() {
  const name = newVal.value.trim();
  if (!name) return;
  try {
    const added = await api.mAddSpecValue(draft.value.dimNo, name);
    useValue(added.code || added.valueNo, added.label);
    // 撞上平台已有的那一档时后端直接返回它 —— 说一声，否则他以为自己白填了
    if (added.label !== name) {
      uni.showToast({ title: t("mySpecs.valueMerged", { name: added.label }), icon: "none" });
    }
    newVal.value = "";
    valCands.value = valCands.value.filter((x) => x.label !== added.label);
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
  /*
   * 载荷怎么拼见 buildSpecOverride —— 关键一条是**两个列表一起提交**
   * （后端先清后写，只发一半会把另一半的覆盖清掉，而且不报错）。
   * `order` 只给当前这一栏，另一栏由它按原顺序补在后面。
   */
  const dims = buildSpecOverride({
    g,
    order: order ?? listOf(g).map((t) => t.templateNo),
    patch,
    removeDimNo,
  });
  await api.mSaveSpecOverride(g.categoryNo, dims);
  /*
   * **整页重取，不拿返回值就地打补丁。**
   *
   * <p>那个接口回的是**销售规格**那一份，而这一页现在管两栏 ——
   * 就地打补丁的话，在「商品参数」栏里做的增删改界面上纹丝不动
   * （实测：加得进去、移除点了没反应），而后端其实已经改了。
   * 多一次请求换掉一整类「看起来没生效」的故障，值。
   */
  await load();
}

async function saveDim(g: StoreCategorySpecs) {
  const d = draft.value;
  try {
    await commit(g, undefined, {
      dimNo: d.dimNo, label: d.label, values: d.values.map((v) => v.code), dropped: d.dropped,
    });
    closeSheet();
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
/*
 * 规格行那一列。**按住的是哪一栏**由页面记着：一次拖动只在一栏之内，
 * 而 `listOf(g)` 要有 g 才算得出下标。
 */
let dragGroup: StoreCategorySpecs | null = null;
const {
  dragFrom, pending: dragPending, dragTo, shift: dragShift,
  onStart: rowDragStart, onMove: rowDragMove, onEnd: onDragEnd,
} = useRowDrag(
  instance,
  ".spec",
  (key) => (dragGroup ? listOf(dragGroup).findIndex((x) => x.templateNo === key) : -1),
  () => (dragGroup ? listOf(dragGroup).length : 0),
  async (from, to) => {
    const g = byCategory.value.find((x) => listOf(x).some((t) => t.templateNo === from));
    if (!g) return;
    const i = listOf(g).findIndex((t) => t.templateNo === from);
    if (i === to) return;   // 没挪动：不必往后端跑一趟
    const seq = moveItem(listOf(g).map((t) => t.templateNo), i, to);
    dimLanded.value = from;
    setTimeout(() => { dimLanded.value = ""; }, 320);
    try {
      await commit(g, seq);
    } catch (e) {
      uni.showToast({ title: (e as Error).message, icon: "none" });
    }
  },
);

function onDragStart(g: StoreCategorySpecs, dimNo: string, e: TouchEvent) {
  dragGroup = g;
  rowDragStart(dimNo, e);
}

function onDragMove(g: StoreCategorySpecs, e: TouchEvent) {
  dragGroup = g;
  rowDragMove(e);
}

/** 刚落位的那一行，用来放一次「落定」动效 */
const dimLanded = ref("");

/** 顺序改的是「这一类用哪几个规格」，点了立即生效 —— 不必为挪一位进一次编辑态 */
async function moveDim(g: StoreCategorySpecs, dimNo: string, delta: number) {
  const seq = listOf(g).map((t) => t.templateNo);
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
  const ok = await confirm({ title: String(t("mySpecs.removeTitle")), hint: String(t("mySpecs.removeConfirm", { name: dim.name })), danger: true });
  if (!ok) return;
  try {
    await commit(g, undefined, undefined, dim.templateNo);
    if (editingDim.value === editKey(g.categoryNo, dim.templateNo)) closeSheet();
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

/** 弹层要的是那个类目对象（里面有 dims/props），不是一个号 */
const pickingCat = computed(
  () => byCategory.value.find((x) => x.categoryNo === picking.value) ?? null,
);

function closePick() {
  picking.value = null;
  building.value = "";
  buildName.value = "";
}

/**
 * **加、编辑、加档位是同一个弹层的三步，不是三个容器。**
 *
 * <p>此前「加规格」是弹层、而它的下一步（起名 + 配档位）是页内展开的一块 ——
 * 同一件事做到一半换了容器，而且两处各有一个装着同一个名字的输入框：
 * 在弹层里输入「辣度」，页内又冒出一个写着「辣度」的框，他分不清哪个才算数。
 *
 * <p>现在一路都在这一层里：挑 / 建 → 起名配档位 → 加档位 → 回上一步 → 保存。
 * 「一次只做一件事，做完回到原地」这句话，得由容器本身说出来。
 *
 * <p>代价说清楚：**编辑态里拖不了规格行了**（弹层里拖不动它下面那张列表）。
 * 那一下本来是「改着改着发现这一个该排前面」，而列表行上本来就有手柄 ——
 * 关掉弹层再拖，比多一个只在某一种状态下才存在的手柄好解释。
 */
type SheetStep = "pick" | "rename" | "values";
const sheetStep = computed<SheetStep | null>(() => {
  if (renaming.value) return "rename";
  if (editingDim.value) return "values";
  return picking.value ? "pick" : null;
});

function closeSheet() {
  newVal.value = "";
  editingDim.value = null;
  editingCat.value = null;
  renaming.value = null;
  closePick();
}

/** 标题：改哪一条就写哪一条的名字，加规格那一步写「加规格」 */
const sheetTitle = computed(() => {
  if (sheetStep.value === "rename") return t("mySpecs.rename");
  if (sheetStep.value === "values") return draft.value.platformName || draft.value.label;
  return t(tab.value === "dims" ? "mySpecs.addDim" : "mySpecs.addProp");
});

/*
 * **改名与改档位是两件事，各自一屏、各自一个保存。**
 *
 * <p>摞在一起时那一屏要同时回答两个问题：「它该叫什么」和「它有哪几档」——
 * 而商家来这一页十次有九次只为后者。更实的问题是那个「保存」：
 * 它一次提交两样东西，于是「我只想加一档」和「我只想改个叫法」
 * 走的是同一条会把另一半也写一遍的路。
 *
 * <p>入口也分开：点名字 = 改名（虚线下划线，一眼看出这行字可以改），
 * 点值那行或右边的滑杆 = 改档位。
 */
const renaming = ref<string | null>(null);
const renameCat = ref<StoreCategorySpecs | null>(null);
const renameDraft = ref({ dimNo: "", platformName: "", label: "" });

function startRename(g: StoreCategorySpecs, tpl: SpecTemplate) {
  renaming.value = editKey(g.categoryNo, tpl.templateNo);
  renameCat.value = g;
  renameDraft.value = {
    dimNo: tpl.templateNo,
    // 平台原名当占位符：清空 = 用回平台的叫法
    platformName: platformNames.value[tpl.templateNo] ?? tpl.name,
    label: tpl.name,
  };
}

/** 只改名字：**档位原样带回去**（后端先清后写，不带就等于把档位取舍全清了） */
async function saveRename() {
  const g = renameCat.value;
  const d = renameDraft.value;
  if (!g) return;
  const cur = listOf(g).find((x) => x.templateNo === d.dimNo);
  try {
    await commit(g, undefined, {
      dimNo: d.dimNo,
      label: d.label,
      values: (cur?.options ?? []).map((o) => o.code ?? ""),
      dropped: [],
    });
    closeSheet();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}
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
    // 候选也跟着当前这一栏走 —— 在「商品参数」里加出来的必须是参数
    (tab.value === "dims"
      ? api.mPickableDims(g.categoryNo)
      : api.mPickableProps(g.categoryNo)).catch(() => []),
    api.mMySpecDims().catch(() => []),
  ]);
  const have = new Set(listOf(g).map((t) => t.templateNo));
  pickable.value = all.filter((x) => !have.has(x.templateNo));
  ownUsed.value = mine.filter((d) => d.status === "ACTIVE").length;
  ownMax.value = mine[0]?.dimQuota ?? 10;
  /*
   * **这一类没有平台配过的候选时，把通用那段直接摊开。**
   *
   * 收起通用规格是为了不让「口味」并排摆在数码类目下（aaf2a743）—— 那个理由
   * 只在**有主候选**时成立：主候选把注意力占住了，通用的收起来才是降噪。
   * 一条主候选都没有的时候收起来，面板打开就是空的：一个能点的都没有，
   * 只有一行「其它平台规格（5）」和「自己建一个」。他点「加规格」是要加规格，
   * 结果还得再点一次才看得见东西 —— 那一步没有替他挡掉任何噪声。
   */
  if (!pickCat.value.length && pickRest.value.length) showRest.value = true;
}

async function pickDim(g: StoreCategorySpecs, picked: SpecTemplate) {
  platformNames.value[picked.templateNo] = picked.name;
  try {
    // 新加的规格默认全档位：他加它就是想用，再让他逐个点一遍是白费一步
    const seq = [...listOf(g).map((x) => x.templateNo), picked.templateNo];
    await commit(withAdded(g, picked), seq);
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
/** 正在自建的那个类目号；空 = 没在建。页内一行输入，不用系统弹框 */
const building = ref("");
const buildName = ref("");

/**
 * 自己建一个平台没有的规格 / 参数。
 *
 * <p><b>页内一行输入，不用 `uni.showModal`。</b>系统弹框的标题与输入框不是同一套字，
 * 字号、行高、对齐都不归我们管 —— 看起来就是「粗糙、字不齐」，而这一点改不了。
 * 而且它 `editable=true` 时把 content 当预填值（不是说明），能放的说明只有一个
 * placeholder。页内输入则与这一页其余部分同一套排版，说明也能好好放在下面。
 *
 * <p>建出来的是规格还是参数，**跟着当前这一栏走** —— 在「商品参数」里建出一个
 * 销售规格，会让它跑去分 SKU，而他只是想标一个「海拔」。
 */
async function confirmBuild(g: StoreCategorySpecs) {
  const name = buildName.value.trim();
  if (!name) return;
  try {
    const dim = await api.mAddSpecDim(name, [], tab.value === "props" ? "PROP" : "SALE");
    if (listOf(g).some((x) => x.templateNo === dim.templateNo)) {
      uni.showToast({ title: t("goods.dimAlready"), icon: "none" });
      return;
    }
    platformNames.value[dim.templateNo] = dim.name;
    const seq = [...listOf(g).map((x) => x.templateNo), dim.templateNo];
    await commit(withAdded(g, dim), seq);
    building.value = "";
    picking.value = null;
    await load();
    /*
     * **建完直接进这一条的编辑态。**
     *
     * <p>自己建出来的规格一个档位都没有 —— 「辣度」而没有「微辣/中辣」，
     * 在建品页是选不出任何东西的一行。上一版把他放回列表，让他自己找到刚建的那行
     * 再点开：多两步，而且中间那一屏没有任何信息告诉他还没完。
     *
     * <p>要**从 load() 之后的新数据里**取那一条：commit 的返回是合并结果，
     * 这里的 g 是提交前的旧快照，拿它去 startEditDim 会连档位一起是旧的。
     */
    const fresh = byCategory.value.find((x) => x.categoryNo === g.categoryNo);
    const row = fresh && listOf(fresh).find((x) => x.templateNo === dim.templateNo);
    if (fresh && row) startEditDim(fresh, row);
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
    closeSheet();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

onShow(() => void load());
</script>

<template>
  <sh-scaffold title-key="mySpecs.title" :denied="!merchant.can('biz:goods')">
    <!--
      两栏切换。**一次只管一件事** —— 两段并排会让每张卡长一倍，
      而商家进来通常只为改其中一件。切过去之后所有交互原样复用。
    -->
    <!--
      **换回 chip 那套。** 这里原本是等宽方块 tab（选中填主色实底）——
      而 sh-tabs 的类注释里写着：抽它出来时两端有两套实现（chip 横排 / 方块），
      **统一成了 chip 那套**。一个被明确废掉的形态在这一页重新长了出来，
      说明当时只改了代码，没有留下拦住它的东西。
      外层壳只为那条横向留白（本页内容通铺到边）：sh-tabs 是多根组件，class 透不过去。
    -->
    <view class="tabs">
      <sh-tabs
        :items="[
          { key: 'dims', label: String($t('mySpecs.tabDims')) },
          { key: 'props', label: String($t('mySpecs.tabProps')) },
        ]"
        :active="tab"
        @change="(k: string) => { tab = k as 'dims' | 'props'; void loadAddable(); }"
      ></sh-tabs>
    </view>
    <text class="txt-caption sh-muted intro">
      {{ $t(tab === "dims" ? "mySpecs.intro" : "mySpecs.introProps") }}
    </text>

    <view v-for="g in byCategory" :key="g.categoryNo" class="cat">
      <sh-section pad :title="g.categoryName">
        <!--
          **带字的按钮，不是裸图标。**一个 ＋ 摆在标题栏里认不出是加什么 ——
          这一页上「加规格」与「加档位」是两件事，各自的入口离得不远。
          展开后同一个位置变「收起」：同一个按钮管开合，不必再找关掉它的地方。
        -->
        <sh-add
          :text="String($t(tab === 'dims' ? 'mySpecs.addDim' : 'mySpecs.addProp'))"
          :active-text="String($t('mySpecs.collapse'))"
          :active="picking === g.categoryNo"
          @tap="togglePick(g)"
        ></sh-add>
      </sh-section>

      <view v-for="t in listOf(g)" :key="t.templateNo" class="spec"
            :class="{
              'spec--drag': dragFrom === t.templateNo,
              'spec--land': dimLanded === t.templateNo,
            }"
            :style="dragFrom === t.templateNo ? { transform: `translateY(${dragShift}px)` } : ''">
        <!--
          **行永远是这一行的样子。**改这一条走弹层（见 sheetStep）——
          页内展开会把下面的规格整段顶走，而他改的时候正需要看着
          「这一类现在有哪几个」。
        -->
        <view class="spec__head sh-row">
          <!--
            **拖动只认手柄这一格。** 事件此前挂在整行上，与它上面那句注释正好相反 ——
            于是点右边的齿轮或 ✕ 时手指微动几像素就变成一次拖动，
            而他以为自己点的是按钮。这是「很容易误触」的全部原因。
          -->
          <!-- size 的单位是 rpx（见 sh-icon）—— 原型里手柄 14px ≈ 28rpx -->
          <view
            class="ic ic--grip sh-center"
            @touchstart="onDragStart(g, t.templateNo, $event)"
            @touchmove.stop.prevent="onDragMove(g, $event)"
            @touchend="onDragEnd"
            @touchcancel="onDragEnd"
          >
            <sh-icon name="grip" :size="28" color="var(--sh-sub)" />
          </view>
          <!-- 虚线下划线：一眼看出这行字可以改，而不必再摆一个图标 -->
          <text class="txt-bold txt-strong spec__name" @tap.stop="startRename(g, t)">{{ t.name }}</text>
          <!-- 自建的标出来：它不参与跨店比价，而那是看不见的差别 -->
          <text v-if="t.scope === 'MERCHANT'" class="txt-caption spec__own">{{ $t("mySpecs.own") }}</text>
          <view class="spec__spacer"></view>
          <sh-icon-btn
            name="sliders"
            :size="36"
            color="var(--sh-primary-text)"
            @tap="startEditDim(g, t)"
          ></sh-icon-btn>
          <sh-icon-btn
            name="close"
            :size="34"
            color="var(--sh-ink)"
            @tap="removeDim(g, t)"
          ></sh-icon-btn>
        </view>
        <!-- 单行省略：换行撑高的话，一屏就少看两个规格 -->
        <text class="txt-caption spec__vals" @tap="startEditDim(g, t)">{{ t.options.map((o) => o.label).join(" · ") || $t("mySpecs.noValueYet") }}</text>
      </view>

      <!--
        **空态要说清是谁造成的。**「平台没配」与「你自己移除光了」在界面上
        长得一模一样，而后者还建议他去联系运营 —— 那条路走不通，
        因为平台配了，缺的是他自己的一次点击。
      -->
      <text v-if="!listOf(g).length && picking !== g.categoryNo" class="txt-caption cat__empty">
        {{ (addable[g.categoryNo] || []).length
          ? $t(tab === "dims" ? "mySpecs.catAllRemoved" : "mySpecs.catAllRemovedProps")
          : $t(tab === "dims" ? "mySpecs.catNoDims" : "mySpecs.catNoProps") }}
      </text>

      <!--
        **移除掉的摆在这儿，点一下就回来。**回去的路此前只在「添加规格」弹层里，
        而他刚做的动作是「移除」—— 让他为了撤销去开一个叫「添加」的面板，
        等于要求他先想明白这两件事是同一件。虚线 = 候选，与建品页那套一致。
      -->
      <view v-if="(addable[g.categoryNo] || []).length && picking !== g.categoryNo" class="back">
        <text class="sh-muted back__t">{{ $t("mySpecs.addableHere") }}</text>
        <view class="back__list sh-wrap">
          <view
            v-for="p in addable[g.categoryNo]"
            :key="p.templateNo"
            class="sh-chip sh-chip--icon sh-chip--dashed"
            @tap="pickDim(g, p)"
          >
            <sh-icon name="plus" :size="18" color="currentColor"></sh-icon>
            <text>{{ p.name }}</text>
          </view>
        </view>
      </view>

      <!--
        「恢复平台默认」= 撤销这一类目下的全部调整。压到最轻并放在最后：
        它与上面每一行的操作不是一个量级，长得一样重的话，
        手指下滑很容易顺手点掉自己刚调好的一切。
      -->
      <view class="cat__foot">
        <text class="txt-caption" @tap="resetOverride(g)">{{ $t("mySpecs.reset") }}</text>
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
    <text class="txt-caption sh-muted foot">{{ $t("mySpecs.foot") }}</text>

    <!--
      **整条链路一个弹层，三步。**（为什么见 sheetStep 那段注释）

      <p>挑 / 建 → 起名配档位 → 加档位 → 回上一步 → 保存。
      中途不换容器，也不叠第二层 —— 叠起来的话两层遮罩一起压暗，
      而他分不清「✕」关的是哪一层。

      <p>用自己的 sh-sheet 而不是 uni.showModal：后者的标题与输入框不是同一套字，
      字号行高都不归我们管 —— 「粗糙、字不齐」改不掉。
      而 sh-sheet 有 max-height + 滚动，候选从 5 条长到 25 条也不会把上半截顶出视口。
    -->
    <sh-sheet
      :visible="!!sheetStep"
      :title="sheetTitle"
      :hint="sheetStep === 'pick' ? pickHintWord : ''"
      @close="closeSheet"
    >
      <!-- ① 挑一个平台现成的，或自己建 -->
      <template v-if="sheetStep === 'pick' && pickingCat">
        <view v-if="pickCat.length" class="chips sheet-gap sh-wrap">
          <text v-for="p in pickCat" :key="p.templateNo" class="sh-chip sh-chip--dashed"
                @tap="pickDim(pickingCat, p)">＋ {{ p.name }}</text>
        </view>
        <view v-if="showRest && pickRest.length" class="chips sheet-gap sh-wrap">
          <text v-for="p in pickRest" :key="p.templateNo" class="sh-chip sh-chip--dashed"
                @tap="pickDim(pickingCat, p)">＋ {{ p.name }}</text>
        </view>
        <view v-if="pickRest.length && pickCat.length" class="picker__more">
          <text class="link" @tap="showRest = !showRest">
            {{ showRest ? $t("mySpecs.restHide") : $t("mySpecs.restShow", { n: pickRest.length }) }}
          </text>
        </view>
        <text v-if="!pickable.length" class="txt-caption sh-muted picker__empty">
          {{ $t("mySpecs.noMoreDim") }}
        </text>

        <!-- 自己建放最后：顺序即建议，先看平台有没有现成的 -->
        <view class="sheet-own">
          <view class="picker__own-line sh-row sh-row--between sh-row--baseline">
            <text class="txt-strong picker__own-t">
              {{ $t(tab === "dims" ? "mySpecs.buildOwnDim" : "mySpecs.buildOwnProp") }}
            </text>
            <text class="txt-caption sh-muted">
              {{ $t("mySpecs.quotaShort", { used: ownUsed, max: ownMax }) }}
            </text>
          </view>
          <view class="build sh-row">
            <input
              maxlength="64"
              v-model="buildName"
              class="field__input build__input"
              :placeholder="buildOwnPhWord"
              @confirm="confirmBuild(pickingCat)"
            />
            <text class="txt-sub link" @tap="confirmBuild(pickingCat)">
              {{ $t("mySpecs.save") }}
            </text>
          </view>
          <text class="txt-caption sh-muted picker__own-s">{{ $t("mySpecs.buildOwnCost") }}</text>
        </view>
      </template>

      <!--
        ② 改档位。**只管档位** —— 已有的、还能加的、自己填的，全在这一屏，
        因为「这一档我要不要」这个判断需要同时看见两边。
      -->
      <template v-else-if="sheetStep === 'values' && editingCat">
        <text class="txt-caption sh-muted sheet-lead">{{ $t("mySpecs.valsLead") }}</text>
        <!--
          档位可以拖。**落点按 chip 的中心点算**，不像规格行那样按行高除 ——
          chip 是横向换行的二维排列，「位移 ÷ 行高」在这里没有意义：
          同一行里左右挪一格与换到下一行，位移可能完全一样。
        -->
        <view class="vals sh-wrap">
          <text
            v-for="(v, vi) in draft.values"
            :key="v.code"
            class="txt-caption sh-chip val"
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
            <sh-icon class="val__x" name="close" :size="20" color="var(--sh-sub)" @tap.stop="dropValue(v.code)"></sh-icon>
          </text>
        </view>

        <!-- 平台还有的：点一下加进来 -->
        <view v-if="valCands.length" class="sheet-own">
          <text class="txt-body picker__own-t">{{ $t("mySpecs.pickHint") }}</text>
          <view class="chips sheet-gap sh-wrap">
            <text v-for="o in valCands" :key="o.code || o.label" class="sh-chip sh-chip--dashed"
                  @tap="pickValue(o)">＋ {{ o.label }}</text>
          </view>
        </view>

        <view class="sheet-own">
          <view class="picker__own-line sh-row sh-row--between sh-row--baseline">
            <text class="txt-strong picker__own-t">{{ ownValueWord }}</text>
          </view>
          <view class="build sh-row">
            <input
              maxlength="64"
              v-model="newVal"
              class="field__input build__input"
              :placeholder="valuePhWord"
              @confirm="confirmNewValue"
            />
            <text class="txt-sub link" @tap="confirmNewValue">{{ $t("mySpecs.add") }}</text>
          </view>
          <text class="txt-caption sh-muted picker__own-s">{{ valueHintWord }}</text>
        </view>

        <view class="edit__acts">
          <view class="sh-btn sh-btn--soft edit__btn" @tap="closeSheet">
            {{ $t("mySpecs.cancel") }}
          </view>
          <view class="sh-btn edit__btn" @tap="saveDim(editingCat)">{{ $t("mySpecs.save") }}</view>
        </view>
      </template>

      <!--
        ③ 改名。**只管名字** —— 一个输入框一个保存，占位符是平台原名：
        清空就是「用回平台的叫法」。
      -->
      <template v-else-if="sheetStep === 'rename'">
        <input maxlength="64" v-model="renameDraft.label" class="txt-body field__input edit__input sheet-gap"
               :placeholder="renameDraft.platformName" />
        <text class="txt-caption sh-muted edit__tip">{{ $t("mySpecs.renameTip") }}</text>
        <view class="edit__acts">
          <view class="sh-btn sh-btn--soft edit__btn" @tap="closeSheet">
            {{ $t("mySpecs.cancel") }}
          </view>
          <view class="sh-btn edit__btn" @tap="saveRename">{{ $t("mySpecs.save") }}</view>
        </view>
      </template>

    </sh-sheet>

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
/* 只留横向留白：本页内容通铺到边，分栏要与卡片对齐 */
.tabs {
  margin: 0 26rpx;
}

.intro {
  display: block;
  padding: 0 8rpx;
}

.cat {
  background: var(--sh-surface);
  border-radius: 24rpx;
  overflow: hidden;
}
.cat__empty,
/* 「可添加」区：压在卡片内容与「恢复平台默认」之间 —— 它比每一行的操作轻，
   但比「恢复全部」重，位置就该在两者中间 */
.back {
  padding: 16rpx 26rpx 0;
}
.back__t {
  display: block;
  margin-bottom: 12rpx;
}
.cat__foot {
  display: block;
  padding: 18rpx 26rpx;
  border-top: var(--sh-hairline);
}

/* 弹层里各段之间留口气 */
.sheet-gap {
  margin-top: 20rpx;
}

/* 自己建一个：与候选之间用一条线隔开 —— 顺序即建议，先看平台有没有现成的 */
.sheet-own {
  margin-top: 24rpx;
  padding-top: 20rpx;
  border-top: var(--sh-hairline);
}

/* 自建那一行：输入框 + 一个动作，与这一页其余部分同一套排版 */
.build {
  padding-top: 12rpx;
}

.build__input {
  flex: 1;
}

.picker__empty {
  display: block;
}
.chips {
  gap: 16rpx;
}

.picker__own-t {
  display: block;
  color: var(--sh-primary-text);
}
.picker__own-s {
  display: block;
  margin-top: 4rpx;
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
  border-top: var(--sh-hairline);
}
/*
  拿起来的样子：抬一层 + 底色 + 轻微放大。
  **拖动中不再降透明度** —— 上一版把被拖的元素调到 0.5，
  在浅色底上看起来就是「字没了」，而那正是商家报的现象之一。
  要表达「这个被拿起来了」，抬阴影比抹掉它自己更准。
*/
.spec--drag {
  background: var(--sh-faint);
  box-shadow: 0 8rpx 24rpx var(--sh-scrim);
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
  gap: 8rpx;
}
.spec__name {
  /*
    可改的字：虚线下划线是最省地方的「这里能点」。
    **必须 inline-block** —— uni 里 <text> 在 flex 行里是 block，
    下划线会从名字一路拉到行尾，看着是一条分隔线而不是一处可点的字。
  */
  display: inline-block;
  border-bottom: 1rpx dashed var(--sh-line);
  /* 一行里它是主角，用 400 会被下面那行档位拉成同一层。
     字阶只给 400/600/700 三档（守卫测住），所以取 600 而不是原型里的 500 */
}
/* 自建的标出来 —— 它不参与跨店比价，而那是看不见的差别 */
.spec__own {
  margin-inline-start: 8rpx;
  padding: 2rpx 10rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  /* 20 不在字阶上；24 是最小的一档 */
}
.spec__spacer {
  flex: 1;
}
/* 单行省略：换行撑高的话，一屏就少看两个规格 */
.spec__vals {
  display: block;
  margin-top: 4rpx;
  padding-inline-start: 52rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 图标按钮：命中区 52rpx，图标 20rpx —— 手指够得着，眼睛不觉得挤 */
.ic {
  width: 52rpx;
  height: 52rpx;
  flex: none;
}
.ic--grip {
  width: 40rpx;
  margin-inline-start: -10rpx;
}

/* 这一屏在管什么，一句话 */
.sheet-lead {
  display: block;
  margin-top: 8rpx;
}

/* 「平台还有这些」压得比「自己填」轻：前者是挑，后者要他动脑子起名 */

.edit__input {
  width: 100%;
  flex: 1;
  padding: 14rpx 20rpx;
  border-radius: 16rpx;
  border: 1rpx solid var(--sh-line);
  background: var(--sh-surface);
}
.vals {
  gap: 16rpx;
  margin-top: 20rpx;
}
.val {
  transition: transform 0.18s ease, box-shadow 0.18s ease, background-color 0.18s ease;
}

/* 同上：抬起来，而不是变透明 */
.val--drag {
  box-shadow: 0 6rpx 18rpx var(--sh-scrim);
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

/* 颜色与尺寸由 sh-icon 给 */
.val__x {
  margin-inline-start: 8rpx;
}
.edit__tip {
  display: block;
  margin-top: 16rpx;
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
  margin: 0 8rpx;
}
</style>
