"use client";

// 平台类目（P-3.1）—— **已接真后端** `/ops/categories/**`。
//
// 这一页此前是「左树 + 右详情」：一屏只看得到十几行，改一个类目要点三下
// （选中 → 读详情 → 找按钮），而最高频的动作其实只有一个 —— **这一类这期做不做**。
//
// 现在是**一张带层级的表**：一级行 + 缩进的二级行，列对齐（形态 / 门槛 / 商品数 /
// 顺序 / 状态）。此前拆成一级一张卡，同一列的值在卡与卡之间对不齐，
// 想回答「哪些类目还没设门槛」得挨张卡去扫；表格一列扫到底就够了。
// 开关是立即生效的状态切换（Switch 的语义），不是待提交的表单勾选。
//
// 「停用」用的就是归档那套（`status=ARCHIVED`）：**不物理删** ——
// 已经归到这个类目下的商品还在，C 端历史链接也还指着它，删掉之后那些入口进来是 404，
// 而它本来只需要「这一类我们这期不做」。
import { useMemo, useState } from "react";
import { GripVertical, Settings2 } from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { notify } from "@/lib/notify";
import { fill } from "@/lib/use-copy";
import type { Category } from "@/lib/types";
import { canDrop as sameGroup, reorderWithin, type DragItem } from "@/lib/reorder";
import { ShowArchivedToggle } from "@/components/archive";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type Column } from "@/components/ui/data-table";
import { CategorySpecDrawer } from "./category-spec-drawer";
import { Drawer, DrawerSection, Field } from "@/components/ui/drawer";
import { FilterSelect } from "@/components/ui/filter-select";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Toolbar } from "@/components/ui/toolbar";
import { useConfirm } from "@/components/ui/confirm-dialog";
import type { ProductsCopy } from "./copy";

type Form = {
  categoryNo?: string;
  name: string;
  i18nEn: string;
  parentNo: string;
  template: string;
  requiredCode: string;
};

const EMPTY: Form = { name: "", i18nEn: "", parentNo: "", template: "STANDARD", requiredCode: "" };

const TEMPLATES = ["STANDARD", "FRESH", "SERVICE", "VOUCHER", "VIRTUAL"] as const;

export function CategoriesTab({ c, canEdit }: { c: ProductsCopy; canEdit: boolean }) {
  /*
   * **就地开抽屉，不跳 tab。**从前点这一格会跳到「类目 × 规格」那个 tab ——
   * 而配规格时最需要的参照恰恰是类目树上的东西：父子关系、同级类目配了什么。
   * 跳走等于把那份上下文丢掉，回来还要重新找到自己刚才在看哪一行。
   */
  const [specFor, setSpecFor] = useState<string | null>(null);
  /*
   * 与「类目 × 规格」tab **共用同一个 queryKey** —— 那边已经拉过就直接命中缓存，
   * 不为了一列数字多打一次接口。
   */
  const specs = useQuery({ queryKey: ["category-specs"], queryFn: () => api.listCategorySpecs() });
  const specCount = useMemo(
    () => new Map((specs.data ?? []).map((r) => [r.categoryNo, r.dimCount])),
    [specs.data],
  );
  const qc = useQueryClient();
  const { confirm, dialog } = useConfirm();
  const [keyword, setKeyword] = useState("");
  const [template, setTemplate] = useState("");
  /*
   * 默认**不含停用**：本地与线上都会堆积历史归档（E2E 残留、下线的旧类目），
   * 默认带上的话首屏是一片灰卡片，真正在用的那几类反而要往下翻。
   * 要开哪一类时勾一下「含已停用」—— 那是一次明确的动作。
   */
  const [showArchived, setShowArchived] = useState(false);
  const [form, setForm] = useState<Form | null>(null);
  /**
   * 买家侧预览。**这一页的产出全是「要到别处才看得见效果」的东西** ——
   * 顺序改了、开关关了，运营在这张管理表上看到的仍是管理表，
   * 只能去 C 端反复刷新才知道对不对。
   *
   * <p>它渲染的是**同一份数据**（这一页已经拉到的类目），不是去抓 C 端页面 ——
   * 所以它证明的是「配置长这样」，不是「C 端此刻长这样」。这条差别写在面板里，
   * 免得有人拿它当线上验收。
   */
  /**
   * 这一轮里**被自己关掉**的类目编号。
   *
   * <p>不加它的话，默认视图（不含已停用）下关掉一条 = 那一行**当场消失** ——
   * 运营看不到自己刚做的事，只看到列表少了一行，会以为点错了或者删掉了。
   * 「含已停用」这个筛选是给**进来时**用的，不该把你刚动过的那一行也吞掉。
   *
   * <p>只在本次停留期间有效：刷新后它就该按筛选条件老实隐藏，
   * 否则这一页会慢慢变成「哪一条都不肯走」的杂物间。
   */
  const [touched, setTouched] = useState<string[]>([]);
  const remember = (...nos: string[]) =>
    setTouched((p) => [...new Set([...p, ...nos])]);

  const [preview, setPreview] = useState(false);
  /** 预览语言：切到 EN 才看得出缺译回落成中文 */
  const [previewLang, setPreviewLang] = useState<"zh" | "en">("zh");
  const [previewTop, setPreviewTop] = useState("");

  /*
   * **关键词不进请求**，在前端过滤。
   *
   * 后端按关键词过滤会把父级一起筛掉：搜「茶叶」命中的是二级 CAT160，
   * 而它的父级「食品生鲜」名字不含「茶」→ 不在结果里 → 分组渲染时
   * 那一条命中项**根本无处可挂，整个页面看着像没搜到**。
   * 类目总量只有几十条，一次全量在前端过滤更简单也更准。
   */
  /*
   * **停用的也一起拉，在前端按开关滤** —— 与关键词同一个理由，外加一条：
   * 只拉未停用的话，「N 个已停用」这个数**永远是 0**，读起来像「没有停用的类目」，
   * 而实际上刚刚才关掉两个。要么如实数出来，要么别显示这个数。
   */
  const q = { template };
  const cats = useQuery({
    queryKey: ["categories", q],
    queryFn: () => api.listCategories({ ...q, showArchived: true }),
  });
  // 门槛码只列启用中的：挂一个停用码，那个类目就永远拒绝所有人
  const authCodes = useQuery({ queryKey: ["auth-code-dict"], queryFn: () => api.listAuthCodeDict() });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["categories"] });

  /**
   * 开关。**一级会连带问一句要不要带上它的二级** ——
   * 「开了一级、底下全关」是个空壳：商家在选择器里点进去空空如也，
   * 而运营以为自己把这一类放出去了。反过来关一级也一样：
   * 二级还开着的话，它们在别处（搜索、专题）仍会露出来。
   *
   * <p>后端对「还挂着子类目或在售商品」的类目**拒绝归档**（C 端类目树会断枝）。
   * 那条拒绝要原样说给运营听 —— 通用的「操作失败」会让他反复点同一个开关。
   */
  const toggle = useMutation({
    mutationFn: async (v: { row: Category; on: boolean; withChildren?: Category[] }) => {
      const one = (no: string) => (v.on ? api.unarchiveCategory(no) : api.archiveCategory(no));
      // 顺序有讲究：开时先开父、再开子；关时先关子、再关父 ——
      // 反过来会撞上后端「有子类目不能归档」那道校验
      if (v.on) {
        await one(v.row.categoryNo);
        for (const x of v.withChildren ?? []) await one(x.categoryNo);
      } else {
        for (const x of v.withChildren ?? []) await one(x.categoryNo);
        await one(v.row.categoryNo);
      }
    },
    onSuccess: (_, v) => {
      invalidate();
      remember(v.row.categoryNo, ...(v.withChildren ?? []).map((x) => x.categoryNo));
      notify.success(v.on ? c.catEnabled : c.catDisabled);
    },
  });

  /**
   * 能不能停用 —— **与后端 `archive` 现在的唯一一道判据一字不差**：下面还有开着的子类目。
   *
   * <p>「还有商品」<b>不再是拦截</b>（2026-08-23）：运营停一个类目多半是政策要求
   * （这一类这期不做、资质链路没接上），拦住他并不能让那批商品消失，只会让他去别处
   * 想办法。改成停用前把后果摆出来（见 {@link confirmDisable}），由他决定。
   *
   * <p>子类目那条留着：它拦的是「渲染不出来的孤儿节点」，而这一条端上有现成的
   * 「连子级一起关」的流程，不是死路。
   *
   * @return 不能停用的原因（给 tooltip 用）；能停用时为 `null`
   */
  function blockedReason(x: Category): string | null {
    const kids = childrenOf(x.categoryNo).filter((k) => !off(k));
    // 一级的子级由 toggleTop 连带关掉，所以这里只在「不打算连带」时才算拦
    if (x.level === 1 && kids.length) return null;
    return kids.length ? fill(c.catOffBlockedChild, { name: kids[0]!.name, n: kids.length }) : null;
  }

  /**
   * 停用前把后果摆出来。**只在这一类下面真的还有商品时才问** ——
   * 空类目上再弹一个确认框，是在为一件没有后果的事索要一次确认。
   *
   * <p>问的是影响面（还有几件、其中几件在售），不是「你确定吗」：
   * 后者除了多一次点击什么都没给。
   */
  async function confirmDisable(x: Category): Promise<boolean> {
    const impact = await api.categoryArchiveImpact(x.categoryNo).catch(() => null);
    if (!impact || impact.goodsCount === 0) return true;
    return confirm({
      title: fill(c.catDisableImpactTitle, { name: x.name }),
      desc: fill(c.catDisableImpactDesc, { n: impact.goodsCount, on: impact.onSaleCount }),
      confirmText: c.catDisableImpactOk,
      danger: true,
    });
  }

  /** 一级的开关：先问清楚要不要连带子级，再发请求 */
  async function toggleTop(top: Category, on: boolean) {
    const kids = childrenOf(top.categoryNo);
    const affected = kids.filter((k) => off(k) === on);   // 开时挑关着的，关时挑开着的
    if (!affected.length) {
      toggle.mutate({ row: top, on });
      return;
    }
    const ok = await confirm({
      title: fill(on ? c.catEnableTopTitle : c.catDisableTopTitle, { name: top.name }),
      desc: fill(on ? c.catEnableTopDesc : c.catDisableTopDesc, { n: affected.length }),
      confirmText: on ? c.catEnableTopOk : c.catDisableTopOk,
      danger: !on,
    });
    if (ok) toggle.mutate({ row: top, on, withChildren: affected });
  }

  /**
   * 二级的开关。**上级关着时不能直接开这一条** —— 后端会拒（「上级类目已归档」），
   * 而运营看到的是「开关拨过去又弹回来」，像是这一行坏了。
   *
   * <p>批量启用那条路早就会**顺手把上级开出来**，单个开关却直接发请求 ——
   * 同一件事两种行为，这是那个「切不动」的来源。这里对齐成一种：先问，再一起开。
   */
  async function toggleChild(r: Category, on: boolean) {
    if (!on && !(await confirmDisable(r))) return;
    const parent = on ? allWithOff.find((x) => x.categoryNo === r.parentNo) : undefined;
    if (!parent || !off(parent)) {
      toggle.mutate({ row: r, on });
      return;
    }
    const ok = await confirm({
      title: fill(c.catEnableParentTitle, { name: parent.name }),
      desc: c.catEnableParentDesc,
      confirmText: c.catEnableParentOk,
    });
    // 顺序交给 toggle：开时先父后子，正好是后端要的那个顺序
    if (ok) toggle.mutate({ row: parent, on: true, withChildren: [r] });
  }

  /**
   * 上下移：**交换相邻两个的 `sort`**，不是给自己 ±1。
   *
   * <p>±1 会撞上已有的值（种子是 10/20/30，但运营手填过的可能是 1、2），
   * 撞了之后同序的两条谁在前取决于数据库返回顺序 —— 那是「点了没反应」
   * 与「点一下跳两格」这类怪象的来源。交换是唯一稳定的做法。
   */
  const putSort = (x: Category, sort: number) =>
    api.saveCategory({
      categoryNo: x.categoryNo, name: x.name, i18nEn: x.i18n.en,
      parentNo: x.parentNo, template: x.template,
      qualifications: x.qualifications, requiredCode: x.requiredCode, sort,
    } as Parameters<typeof api.saveCategory>[0]);

  const move = useMutation({
    mutationFn: async (v: { a: Category; b: Category }) => {
      await putSort(v.a, v.b.sort ?? 0);
      await putSort(v.b, v.a.sort ?? 0);
    },
    onSuccess: () => invalidate(),
  });

  /**
   * 拖到位：把这一组**重排成 10、20、30……**，只写值真的变了的那几条。
   *
   * <p>与 ↑↓ 的交换不是同一件事，所以不能复用它：拖是「插到第 k 位」，
   * 沿途每一条的位次都要挪一格。用交换去模拟，等于在中间来回换 k 次，
   * 每次都是一个请求，中途失败会停在一个谁都没想要的顺序上。
   *
   * <p>重排成整十而不是保留原值，是**顺手把历史脏值抹平**：种子是 10/20/30，
   * 但手填过的可能是 1、2、甚至并列的 0 —— 并列时谁在前取决于数据库返回顺序。
   */
  const reorder = useMutation({
    mutationFn: async (list: Category[]) => {
      for (const [i, x] of list.entries()) {
        const sort = (i + 1) * 10;
        if ((x.sort ?? 0) !== sort) await putSort(x, sort);
      }
    },
    onSuccess: () => invalidate(),
  });

  /**
   * 批量启停。**只作用于二级** —— 一级要连带下面一整组，那件事有确认对话框，
   * 塞进批量里就成了「勾了十个、弹十个框」。选中的一级会被跳过并计入回执。
   *
   * <p>逐条发而不是一个批量接口：后端的拒绝是**一条一条**的（这条还挂着商品），
   * 一个大接口要么整批回滚（一条挡住九条）、要么部分成功而说不清是哪几条。
   * 挡得住的在端上就先滤掉了（`blockedReason` 与后端一字不差），剩下的才发。
   */
  const bulk = useMutation({
    mutationFn: async (on: boolean) => {
      const picked = flat.filter((x) => sel.includes(x.categoryNo));
      const doable = picked.filter(
        (x) => x.level === 2 && off(x) === on && (on || !blockedReason(x)),
      );
      if (on) {
        // 父级关着的话先把父级开出来，否则这一条开了也没人看得见
        const parents = new Set(doable.map((x) => x.parentNo).filter(Boolean) as string[]);
        for (const no of parents) {
          const p = all.find((x) => x.categoryNo === no);
          if (p && off(p)) await api.unarchiveCategory(no);
        }
        for (const x of doable) await api.unarchiveCategory(x.categoryNo);
      } else {
        for (const x of doable) await api.archiveCategory(x.categoryNo);
      }
      return { n: doable.length, m: picked.length - doable.length, nos: doable.map((x) => x.categoryNo) };
    },
    onSuccess: (r) => {
      invalidate();
      remember(...r.nos);
      setSel([]);
      if (r.n) notify.success(fill(c.catBulkDone, { n: r.n, m: r.m }));
      else notify.error(c.catBulkNone);
    },
  });

  async function bulkOff() {
    const n = flat.filter(
      (x) => sel.includes(x.categoryNo) && x.level === 2 && !off(x) && !blockedReason(x),
    ).length;
    const ok = await confirm({
      title: fill(c.catBulkOffTitle, { n }),
      desc: c.catBulkOffDesc,
      confirmText: c.catBulkOffOk,
      danger: true,
    });
    if (ok) bulk.mutate(false);
  }

  /** 同级里的邻居；停用的不参与换位 —— 它们本来就沉在底部，换过去看不出变化 */
  function neighbour(list: Category[], i: number, dir: -1 | 1) {
    // **跨过停用的接着找**，不是碰到就停：刚关掉的那条会停在组中间（见 `sunk`），
    // 碰到就停的话它上下两条的 ↑↓ 会双双变灰 —— 关掉一条类目，顺带把邻居的排序也锁了
    for (let j = i + dir; j >= 0 && j < list.length; j += dir) {
      if (!off(list[j]!)) return list[j];
    }
    return undefined;
  }

  const save = useMutation({
    mutationFn: () =>
      api.saveCategory({
        categoryNo: form!.categoryNo,
        name: form!.name.trim(),
        i18nEn: form!.i18nEn.trim() || undefined,
        parentNo: form!.parentNo || undefined,
        template: form!.template,
        qualifications: [],
        requiredCode: form!.requiredCode || undefined,
      } as Parameters<typeof api.saveCategory>[0]),
    onSuccess: () => { invalidate(); setForm(null); notify.success(c.catSaved); },
  });

  const allWithOff = cats.data ?? [];
  // 停用的按筛选隐藏，**但刚被自己关掉的那几条留在原地**（见 `touched`）
  const all = showArchived
    ? allWithOff
    : allWithOff.filter((x) => !x.archivedAt || touched.includes(x.categoryNo));
  /** 命中：自己名字含关键词，或它的父级命中（父级命中时整组保留） */
  const rows = useMemo(() => {
    const kw = keyword.trim().toLowerCase();
    if (!kw) return all;
    const hit = (x: Category) =>
      x.name.toLowerCase().includes(kw) || (x.i18n.en ?? "").toLowerCase().includes(kw)
      || x.categoryNo.toLowerCase().includes(kw);
    const hitTops = new Set(all.filter((x) => x.level === 1 && hit(x)).map((x) => x.categoryNo));
    const keep = all.filter((x) => hit(x) || (x.parentNo && hitTops.has(x.parentNo)));
    // 命中的二级要把父级带回来，否则它没有可挂的分组
    const parents = new Set(keep.map((x) => x.parentNo).filter(Boolean) as string[]);
    return all.filter((x) => keep.includes(x) || parents.has(x.categoryNo));
  }, [all, keyword]);
  const off = (x: Category) => !!x.archivedAt;
  /**
   * 停用的沉底、其余按 `sort` —— **顺序是这一页的产出之一**：
   * C 端类目栏就按它排，所以看到的顺序必须与买家看到的一致，
   * 否则运营调完顺序在这里看不出变化，只能去 C 端反复刷新验证。
   */
  /**
   * 沉底的只有「进来时就停用」的那些。**刚被自己关掉的留在原地** ——
   * 行虽然还在（见 `touched`），可它要是同时窜到分组末尾，
   * 看着仍然像「那一条不见了」，只不过换了个地方不见。
   * 刷新之后 `touched` 清空，它们才按老规矩沉底。
   */
  const sunk = (x: Category) => off(x) && !touched.includes(x.categoryNo);
  const byLive = (a: Category, b: Category) =>
    Number(sunk(a)) - Number(sunk(b)) || (a.sort ?? 0) - (b.sort ?? 0);
  const tops = useMemo(() => rows.filter((x) => x.level === 1).sort(byLive), [rows]);
  const childrenOf = (no: string) => rows.filter((x) => x.parentNo === no).sort(byLive);

  /** 门槛码 → 展示名。手输的码不该出现在这里，所以查不到就原样显示，一眼看得出不对 */
  const codeName = (code?: string) =>
    code ? (authCodes.data ?? []).find((a) => a.code === code)?.name ?? code : "";
  /** 这个码发不出来（停用或不存在）—— 挂着它的类目谁都上不了架 */
  const codeBroken = (code?: string) =>
    !!code && !(authCodes.data ?? []).some((a) => a.code === code && a.enabled);

  const stat = {
    tops: tops.length,
    subs: rows.filter((x) => x.level === 2).length,
    off: allWithOff.filter(off).length,
  };

  /**
   * 拍平成「一级 + 它的二级」一条条行 —— 层级靠行样式与缩进表达，不再拆成卡片。
   * 一级下面没有二级时仍然出现：那正是需要被看到的状态（买家点进去是空的）。
   */
  const flat = useMemo(() => {
    const out: Category[] = [];
    for (const t of tops) {
      out.push(t);
      out.push(...childrenOf(t.categoryNo));
    }
    return out;
  }, [tops, rows]);

  /**
   * 同级的一整组（排序用）—— **取的是全量，不是当前筛出来的那几行**。
   *
   * <p>按筛选结果排会错得很隐蔽：搜「茶」只剩两条，把第二条拖到第一条，
   * 重排写的是 10、20，而没显示出来的七条还占着 10~90 —— 清掉搜索一看，
   * 这两条插进了谁都没想到的位置。
   *
   * <p>反过来，只要在筛选态就**不给排序入口**（下面 `sortable`）：
   * 与看不见的行换位，界面上是「点了没反应」。
   */
  const groupOf = (r: Category) =>
    allWithOff
      .filter((x) => (r.level === 1 ? x.level === 1 : x.parentNo === r.parentNo))
      .sort(byLive);

  /** 排序只在「看得见的就是全部」时开放 */
  const sortable = !keyword.trim() && !template;

  /** 批量选中的编号。批量与开关是两条路：开关管一条，这条管「这一批这期都不做」 */
  const [sel, setSel] = useState<string[]>([]);

  /** 正在拖的那条 / 当前悬停的落点。两个都要：只有前者的话，人看不出会落到哪 */
  const [dragNo, setDragNo] = useState<string | null>(null);
  const [overNo, setOverNo] = useState<string | null>(null);
  const dragging = () => flat.find((x) => x.categoryNo === dragNo);

  /**
   * 能不能落在这一行上 —— 同父同级的判断复用 `lib/reorder`（菜单顺序页那套），
   * 那边的边界（落在自己身上、跨父）已经有单测，重写一遍只会多一处会走样的实现。
   *
   * <p>这里额外加一条：**停用的不做落点**。它们本来就沉在底部，
   * 排它们等于排一个看不见的顺序。
   *
   * <p>一级用一个固定的 parentKey，不能用 undefined —— 两个 undefined 会相等，
   * 于是「一级」和「没有父级的二级」会被判成同一组。
   */
  const asDragItem = (x: Category): DragItem =>
    ({ key: x.categoryNo, parentKey: x.level === 1 ? "__root__" : x.parentNo ?? "" });

  function canDropOn(target: Category) {
    const src = dragging();
    return !!src && sameGroup(asDragItem(src), asDragItem(target)) && !off(src) && !off(target);
  }

  /**
   * 落点线画在目标行的哪一边。**往下拖画下边、往上拖画上边** ——
   * 因为插入语义是「先摘掉自己、再插到目标的下标」，往下拖时天然落在目标**之后**。
   * 一律画上边的话，往下拖每次都会比线显示的位置多一格；
   * 而强行把往下拖也改成「插到目标之前」，最后一位就永远排不进去了。
   */
  function dropEdge(r: Category): "top" | "bottom" | null {
    const src = dragging();
    if (!src || overNo !== r.categoryNo || !canDropOn(r)) return null;
    const list = groupOf(src);
    return list.indexOf(src) < list.indexOf(r) ? "bottom" : "top";
  }

  function drop(target: Category) {
    const src = dragging();
    setDragNo(null);
    setOverNo(null);
    if (!src || !canDropOn(target)) return;
    // 连**停用的**一起重排：它们排在末尾（byLive 已经把它们沉到底），
    // 于是重新启用时落在这一组的最后 —— 而不是拿着一个没人动过的旧 sort
    // 插回中间某处，看着像「开了一下顺序就乱了」
    const list = groupOf(src);
    const next = reorderWithin(list, list.indexOf(src), list.indexOf(target));
    // 没动就别发请求 —— reorderWithin 原样返回同一个数组，用 === 判得出来
    if (next !== list) reorder.mutate([...next]);
  }

  const columns: Column<Category>[] = [
    {
      header: c.catColName,
      cell: (r) => (
        <span className={r.level === 2 ? "pl-5" : ""}>
          <button
            type="button"
            className="focus-ring text-left hover:underline"
            onClick={() => canEdit && setForm({
              categoryNo: r.categoryNo, name: r.name, i18nEn: r.i18n.en ?? "",
              parentNo: r.parentNo ?? "", template: r.template,
              requiredCode: r.requiredCode ?? "",
            })}
          >
            {r.name}
          </button>
          {/* 缺英文名会在 C 端英文界面静默回落中文 —— 这一页看不见就永远没人补 */}
          {!r.i18n.en && (
            <span title={c.catI18nMissingHint} className="ml-2">
              <Badge tone="muted">{c.catI18nMissing}</Badge>
            </span>
          )}
        </span>
      ),
      className: "whitespace-normal",
      width: "16rem",
    },
    { header: c.catColTemplate, cell: (r) => codeLabel(c, r.template) },
    {
      header: c.fieldRequiredCode,
      cell: (r) => {
        if (!r.requiredCode)
          return <span className="text-muted-foreground">{c.catNoGateShort}</span>;
        const label = codeName(r.requiredCode);
        const title = `${label}（${r.requiredCode}）${(r.qualifications ?? []).join("、")}`;
        // 发不出来的码要红，**而且从不缩写**：那种类目谁都上不了架，报错又说不清原因
        if (codeBroken(r.requiredCode))
          return (
            <span title={title}><Badge tone="danger">{c.catGateBroken}</Badge></span>
          );
        // 码名与类目同名（蔬菜→蔬菜、酒类→酒类）时只留一个点：整列把类目名再读一遍，
        // 真正要在这一列找的「哪几个没门槛、哪个坏了」反而被淹掉。全名留在 title 里。
        // 用实心点而不是「·」：无门槛那格是「—」，两个细长灰符号并排根本分不出，
        // 而它们的意思正好相反
        if (label === r.name)
          return <span title={title} className="text-info" aria-label={title}>●</span>;
        return (
          <span title={title}><Badge tone="info">{label}</Badge></span>
        );
      },
    },
    // 0 不显示：一列的「0」会把真正有货的那几行淹掉
    { header: c.catColGoods, cell: (r) => r.skuCount || "", numeric: true },
    {
      /*
       * **规格在这里露个头。**从前它只活在另一个 tab 里：运营在类目树上
       * 看不出这一类配没配规格，得先知道「类目 × 规格」那个 tab 存在、
       * 再去那边找同一行 —— 于是「新建了类目却没配规格」成了最常见的缺口，
       * 而那一类的商家只能手输规格，手输的选项没有编码、聚合不了。
       *
       * 只对二级类目有意义：规格绑在叶子上，一级类目不承载商品。
       */
      header: c.catColSpecs,
      cell: (r) => {
        if (r.level === 1) return null;
        const n = specCount.get(r.categoryNo) ?? 0;
        /*
         * **看得出这里能点。**从前已配的显示一个裸数字、没配的显示一枚
         * 只读样式的角标 —— 两者都不像按钮，于是「规格在哪配」这个问题
         * 界面自己回答不了。现在两种状态都是按钮：
         * 没配 = 一个动作（「配规格」），已配 = 数字 + 齿轮。
         */
        return n > 0 ? (
          <Button size="sm" variant="outline" className="gap-1 tabular-nums"
            onClick={() => setSpecFor(r.categoryNo)}>
            {n}<Settings2 className="size-3.5 opacity-60" />
          </Button>
        ) : (
          <Button size="sm" variant="destructive" onClick={() => setSpecFor(r.categoryNo)}>
            {c.catSpecsNone}
          </Button>
        );
      },
      numeric: true,
    },
    {
      header: c.catColOrder,
      cell: (r) => {
        if (!canEdit || off(r) || !sortable) return null;
        const list = groupOf(r);
        const i = list.findIndex((x) => x.categoryNo === r.categoryNo);
        return (
          <span className="flex items-center">
            {/*
              把手只管「抓」，落点是整行（见 rowProps）。
              ↑↓ 一并留着 —— 原生拖放**键盘上完全够不着**，
              去掉它等于把排序这件事从键盘用户手里拿走。
            */}
            <span
              draggable
              title={c.catDragHint}
              onDragStart={() => setDragNo(r.categoryNo)}
              onDragEnd={() => { setDragNo(null); setOverNo(null); }}
              className="cursor-grab px-1 text-muted-foreground active:cursor-grabbing"
            >
              <GripVertical className="size-4" />
            </span>
            <Button size="sm" variant="ghost"
              disabled={!neighbour(list, i, -1) || move.isPending}
              onClick={() => { const b = neighbour(list, i, -1); if (b) move.mutate({ a: r, b }); }}
            >{c.catMoveUp}</Button>
            <Button size="sm" variant="ghost"
              disabled={!neighbour(list, i, 1) || move.isPending}
              onClick={() => { const b = neighbour(list, i, 1); if (b) move.mutate({ a: r, b }); }}
            >{c.catMoveDown}</Button>
          </span>
        );
      },
    },
    {
      header: c.catColStatus,
      // 停用被后端拒的两种情况在这里就置灰，别让人点一个注定失败的开关
      cell: (r) => (
        <span title={off(r) ? undefined : blockedReason(r) ?? undefined}>
          <Switch
            checked={!off(r)}
            disabled={!canEdit || (!off(r) && !!blockedReason(r))}
            onChange={(on) => void (r.level === 1 ? toggleTop(r, on) : toggleChild(r, on))}
          />
        </span>
      ),
    },
    {
      header: c.colActions,
      cell: (r) =>
        canEdit && r.level === 1 ? (
          <Button size="sm" variant="ghost" onClick={() => openNew(r.categoryNo, r.template)}>
            {c.catAddChild}
          </Button>
        ) : null,
    },
  ];

  /** 预览只看启用中的 —— 停用的不出现，这正是运营要确认的那件事 */
  const liveTops = tops.filter((x) => !off(x));
  const liveChildren = childrenOf(previewTop || liveTops[0]?.categoryNo || "").filter((x) => !off(x));

  function openNew(parentNo: string, tpl: string) {
    setForm({ ...EMPTY, parentNo, template: tpl });
  }

  return (
    <>

      <Toolbar search={keyword} onSearch={setKeyword} searchPlaceholder={c.catSearchPh}>
        <FilterSelect
          value={template}
          onChange={setTemplate}
          options={[
            { value: "", label: c.catAllTemplates },
            ...TEMPLATES.map((t) => ({ value: t, label: c[`tpl${t[0]}${t.slice(1).toLowerCase()}` as keyof ProductsCopy] as string })),
          ]}
        />
        <ShowArchivedToggle checked={showArchived} onChange={setShowArchived} label={c.catShowOff} />
        <Button variant="outline" onClick={() => setPreview(true)}>{c.catPreview}</Button>
        {canEdit && <Button onClick={() => setForm({ ...EMPTY })}>{c.catNew}</Button>}
      </Toolbar>

      <p className="mb-3 txt-caption text-muted-foreground">
        {fill(c.catStat, { a: stat.tops, b: stat.subs, c: stat.off })}
      </p>

      {/*
        选中之后才出现的一条。常驻一排禁用的批量按钮，等于让人先猜「选了才能点」；
        而它一出现，本身就是「你现在选着东西」的提示 —— 表格滚下去之后尤其需要。
      */}
      {canEdit && sel.length > 0 && (
        <div className="mb-3 flex items-center gap-2 rounded-card bg-secondary/50 px-3 py-2">
          <span className="txt-caption font-medium">{fill(c.catBulkSelected, { n: sel.length })}</span>
          <Button size="sm" variant="outline" disabled={bulk.isPending} onClick={() => bulk.mutate(true)}>
            {c.catBulkOn}
          </Button>
          <Button size="sm" variant="outline" disabled={bulk.isPending} onClick={bulkOff}>
            {c.catBulkOff}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setSel([])}>{c.catBulkClear}</Button>
          <span className="txt-caption text-muted-foreground">{c.catBulkTip}</span>
        </div>
      )}

      {/*
        这一页故意比别的表紧一档：它是**配置表**，一屏五十来行、要来回比对同一列，
        而不是「读几条记录」。走既有的 [data-density] 令牌而不是写死行高 ——
        写死的话密度切换在这一页会失效，而且与 --ctl-h 错开、行里的按钮会顶出格。
      */}
      <div data-density="dense">
      <DataTable
        rows={flat}
        columns={columns}
        rowKey={(r) => r.categoryNo}
        loading={cats.isLoading}
        error={cats.error}
        onRetry={() => cats.refetch()}
        empty={c.emptyTree}
        // 行底色在这张表里是**语义**（一级分组、停用灰显），不能再叠隔行底色 ——
        // zebra 的特异度更高，会把一级底色按奇偶吃掉一半，同一种状态两个颜色
        striped={false}
        selectable={canEdit}
        selectedKeys={sel}
        onSelectedChange={setSel}
        // 一级行加底色、二级行降一档字重：层级要靠**行本身**表达，
        // 只靠缩进的话滚到中间就分不清自己在哪一组
        rowClassName={(r) =>
          [
            r.level === 1 ? "bg-secondary/40 font-medium" : "",
            off(r) ? "opacity-55" : "",
            // 用一条线而不是整行高亮：高亮会读成「跟这一行换位」，那是另一回事。
            // 画哪一边见 dropEdge —— 方向不同，落的位置真的不同
            dropEdge(r) === "top" ? "[&>td]:border-t-2 [&>td]:border-primary" : "",
            dropEdge(r) === "bottom" ? "[&>td]:border-b-2 [&>td]:border-primary" : "",
            dragNo === r.categoryNo ? "opacity-40" : "",
          ].filter(Boolean).join(" ")
        }
        rowProps={(r) => ({
          onDragOver: (e) => {
            if (!canDropOn(r)) return;
            e.preventDefault();          // 不 preventDefault 的话浏览器根本不让放
            setOverNo(r.categoryNo);
          },
          onDragLeave: () => setOverNo((n) => (n === r.categoryNo ? null : n)),
          onDrop: (e) => { e.preventDefault(); drop(r); },
        })}
      />
      </div>

      {/*
        编辑抽屉。「形态」与「门槛码」是这里最要紧的两个字段：
        形态决定商品长什么样（生鲜要截单、服务不发货），门槛码决定谁能卖。
        二级的形态继承父级，所以是只读的。
      */}
      <Drawer
        open={!!form}
        onOpenChange={(o) => !o && setForm(null)}
        title={form?.categoryNo ? c.catEdit : c.catNew}
      >
        {form && (
          <DrawerSection first title={c.catFormBasic}>
            <Field label={c.fieldCatName}>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </Field>
            <Field label={c.fieldCatNameEn}>
              <Input value={form.i18nEn} onChange={(e) => setForm({ ...form, i18nEn: e.target.value })} />
              {/*
                **留空的后果要写在他正在填的地方。**不填不会报错，C 端英文界面
                就静默回落成中文 —— 英文用户看到的类目列表里夹着几个汉字词，
                而没有任何一处提示这件事出了岔。表格上那枚「缺译」角标是事后
                才看得见的，这一行是事前。
                不设成必填：挡住「先把类目建起来再说」会更糟，缺译至少还有角标追。
              */}
              <p className="mt-1 txt-caption text-muted-foreground">{c.fieldCatNameEnHint}</p>
            </Field>
            <Field label={c.fieldParent}>
              <FilterSelect
                value={form.parentNo}
                onChange={(v) => {
                  const parent = rows.find((x) => x.categoryNo === v);
                  setForm({ ...form, parentNo: v, template: parent?.template ?? form.template });
                }}
                options={[
                  { value: "", label: c.catParentNone },
                  ...tops.map((x) => ({ value: x.categoryNo, label: x.name })),
                ]}
              />
            </Field>
            <Field label={c.fieldTemplate}>
              {form.parentNo ? (
                <div className="txt-body text-muted-foreground">
                  {codeLabel(c, form.template)}
                  <span className="ml-2 txt-caption">{c.catTemplateInherited}</span>
                </div>
              ) : (
                <FilterSelect
                  value={form.template}
                  onChange={(v) => setForm({ ...form, template: v })}
                  options={TEMPLATES.map((t) => ({ value: t, label: codeLabel(c, t) }))}
                />
              )}
            </Field>
            {/* 门槛码只能从字典里挑、不能手输 —— 输错一个字母就是一个永不命中的门槛，且不报错 */}
            <Field label={c.fieldRequiredCode}>
              <FilterSelect
                value={form.requiredCode}
                onChange={(v) => setForm({ ...form, requiredCode: v })}
                options={[
                  { value: "", label: c.catNoGate },
                  ...(authCodes.data ?? [])
                    .filter((a) => a.enabled)
                    .map((a) => ({ value: a.code, label: `${a.name}（${a.code}）` })),
                ]}
              />
            </Field>
            <p className="mt-1 txt-caption text-muted-foreground">{c.catGateHint}</p>
            <Button
              className="mt-4"
              disabled={!form.name.trim() || save.isPending}
              onClick={() => save.mutate()}
            >
              {c.catSave}
            </Button>
          </DrawerSection>
        )}
      </Drawer>
      {/*
        预览：只渲染**启用中的**、按 sort 排 —— 与买家看到的规则一致。
        停用的不出现，这正是运营要确认的那件事。
      */}
      <Drawer open={preview} onOpenChange={setPreview} title={c.catPreview} desc={c.catPreviewDesc}>
        <DrawerSection first title={c.catPreviewLang}>
          <div className="flex gap-2">
            {(["zh", "en"] as const).map((lg) => (
              <Button
                key={lg}
                size="sm"
                variant={previewLang === lg ? "default" : "outline"}
                onClick={() => setPreviewLang(lg)}
              >
                {lg === "zh" ? c.catPreviewZh : c.catPreviewEn}
              </Button>
            ))}
          </div>
        </DrawerSection>
        <DrawerSection title={c.catPreviewPane}>
          {/* 窄容器：类目栏在手机上就是这个宽度，按桌面宽度预览会看不出换行与截断 */}
          <div className="mx-auto w-[320px] rounded-card border border-[var(--border)] bg-card p-3">
            <div className="flex flex-wrap gap-2">
              {liveTops.map((t) => (
                <button
                  key={t.categoryNo}
                  type="button"
                  onClick={() => setPreviewTop(t.categoryNo)}
                  className={`focus-ring rounded-field px-2.5 py-1 txt-caption ${
                    (previewTop || liveTops[0]?.categoryNo) === t.categoryNo
                      ? "bg-primary text-primary-foreground"
                      : "bg-secondary"
                  }`}
                >
                  {shown(t, previewLang)}
                </button>
              ))}
            </div>
            <ul className="mt-3 space-y-1">
              {liveChildren.map((x) => (
                <li key={x.categoryNo} className="txt-body">
                  {shown(x, previewLang)}
                  {previewLang === "en" && !x.i18n.en && (
                    <span className="ml-2 txt-caption text-[var(--warning)]">{c.catPreviewFallback}</span>
                  )}
                </li>
              ))}
              {!liveChildren.length && (
                <li className="txt-caption text-muted-foreground">{c.catPreviewEmptyGroup}</li>
              )}
            </ul>
          </div>
        </DrawerSection>
      </Drawer>

      {dialog}
      <CategorySpecDrawer c={c} canEdit={canEdit} categoryNo={specFor}
        onClose={() => setSpecFor(null)} />
    </>
  );
}

/**
 * 按预览语言取名字。**缺译回落中文**（R9）—— 预览要如实展示这个回落，
 * 否则运营会以为英文界面上是空的或是英文。
 */
function shown(x: Category, lang: "zh" | "en") {
  return lang === "en" ? x.i18n.en || x.i18n.zh : x.name;
}

/** 形态 → 文案。集中一处，避免每个用到的地方各拼一次 */
function codeLabel(c: ProductsCopy, template: string) {
  const map: Record<string, string> = {
    STANDARD: c.tplStandard, FRESH: c.tplFresh, SERVICE: c.tplService,
    VOUCHER: c.tplVoucher, VIRTUAL: c.tplVirtual,
  };
  return map[template] ?? template;
}
