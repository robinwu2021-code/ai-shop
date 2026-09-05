// 购物车：服务端为准，本地做乐观更新。按履约方式分组结算。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import type { CartItem, FulfillmentType } from "@shared/types";

/** 履约组内的商家段。一段 = 结算后的一笔子订单 */
export interface MerchantSegment {
  merchantNo: string;
  merchantName: string;
  items: CartItem[];
}

export interface CartGroup {
  fulfillment: FulfillmentType;
  /** 结算入口吃的还是它，分段只是视图 */
  items: CartItem[];
  merchants: MerchantSegment[];
}

/**
 * 按商家聚段，**保持首次出现的顺序**。
 *
 * 不排序是刻意的：用户加购的先后是他自己的心智顺序，
 * 按店名或单号重排会让「我刚加的那件」跳到别处。
 */
export function segmentByMerchant(items: CartItem[]): MerchantSegment[] {
  const map = new Map<string, MerchantSegment>();
  for (const it of items) {
    const key = it.merchantNo || "";
    const seg = map.get(key)
      ?? { merchantNo: key, merchantName: it.merchantName || "", items: [] };
    seg.items.push(it);
    map.set(key, seg);
  }
  return [...map.values()];
}

/**
 * 结不掉的：已下架，**或者可售库存已经是 0**。
 *
 * 后端把这两件事分两个字段发（`invalid` 与 `available`），端上是同一个结果 ——
 * 都进不了这一单。少判后一条的话，售罄的商品会留在有效区里、能勾能算钱，
 * 一直到下单那一刻才被库存拒，而那时他已经填完了整页。
 */
export function unsellable(it: CartItem): boolean {
  return !!it.invalid || it.available === 0;
}

export const useCartStore = defineStore("cart", {
  state: () => ({
    items: [] as CartItem[],
    loading: false,
    /**
     * 至少成功拉过一次。**空态要等它** —— 否则冷启动那一瞬间
     * 「购物车是空的」会先闪一下再被商品顶掉，看起来像刚被谁清空了。
     */
    loaded: false,
    /**
     * 结算勾选的 skuNo。**只在端上**，后端没有能写它的端点（见 TDD §3.6.1）。
     *
     * 不变式：**同一时刻只勾一种履约方式**。确认页一页只服务一种履约方式
     *（自提选自提点、快递填地址、上门选时段，三种收货信息塞不进同一页），
     * 允许跨组勾选的话，底栏合计与下一页的应付就是两个数。
     */
    selected: [] as string[],
    /**
     * 编辑态里标记待删的 skuNo。**与 `selected` 是两套**：
     * 那个是「这几件我要买」，这个是「这几件我要删」——
     * 合成一套的话，删完东西回到普通态，勾选会莫名其妙变成刚才为了删而点的那些。
     * 不持久化：编辑态是一次操作，不跨会话。
     */
    marked: [] as string[],
    /**
     * 用户自己动过勾选。**不持久化**，只活在这次会话里。
     *
     * 用来区分「还没挑过」和「挑完了，一件都不要」——
     * 没有它的话，用户把勾全取消、切到别的 tab 再回来，
     * `syncSelection` 会把「空」当成「还没挑过」，替他重新全勾上。
     */
    touched: false,
  }),

  getters: {
    /** 车里有几件。**tabBar 角标与商品页角标用的是它**，含失效件（那也是他加过的） */
    count: (s) => s.items.reduce((n, it) => n + it.qty, 0),
    validItems: (s) => s.items.filter((it) => !unsellable(it)),
    /** 结不掉的那些。**单独成区展示，不混在有效件里**，也一件都不许自动删 */
    invalidItems: (s) => s.items.filter((it) => unsellable(it)),
    totalFen(): number {
      return this.validItems.reduce((n, it) => n + it.price * it.qty, 0);
    },
    /**
     * 按履约方式分组 —— **结算单位**，一组一次确认页。
     *
     * 外层必须是履约方式而不是商家：不同履约方式的收货信息根本不同
     * （自提选自提点、快递填地址、上门选时段），同一家店的自提商品与快递商品
     * 塞不进同一个确认页。
     *
     * 商家是**组内的第二层**（`merchants`）：它决定拆出几笔子订单，
     * 用户要在提交前看见。`items` 保持原样不动，`merchants` 是它的视图投影 ——
     * 结算入口 `go(fulfillment, items)` 因此一行都不用改。
     */
    groups(): CartGroup[] {
      const map = new Map<FulfillmentType, CartItem[]>();
      for (const it of this.validItems) {
        const arr = map.get(it.fulfillment) ?? [];
        arr.push(it);
        map.set(it.fulfillment, arr);
      }
      return [...map.entries()].map(([fulfillment, items]) => ({
        fulfillment,
        items,
        merchants: segmentByMerchant(items),
      }));
    },

    /**
     * 默认该勾哪一组：**件数最多的那一组**，并列取先出现的。
     *
     * 不取「第一组」：组序由加购先后决定，一件很久以前加的快递商品
     * 会让默认勾选只剩那一件，而车里可能还躺着五件自提的。
     */
    defaultGroup(): CartGroup | null {
      let best: CartGroup | null = null;
      let bestN = 0;
      for (const g of this.groups) {
        const n = g.items.reduce((s, it) => s + it.qty, 0);
        if (n > bestN) {
          best = g;
          bestN = n;
        }
      }
      return best;
    },

    /** 当前勾中的是哪种履约方式。没勾任何东西时为 null */
    activeFulfillment(): FulfillmentType | null {
      const set = new Set(this.selected);
      return this.validItems.find((it) => set.has(it.skuNo))?.fulfillment ?? null;
    },

    /** 底栏与「全选」作用的那一组：勾了就是勾中的那组，没勾就是默认组 */
    activeGroup(): CartGroup | null {
      const f = this.activeFulfillment;
      if (!f) return this.defaultGroup;
      return this.groups.find((g) => g.fulfillment === f) ?? null;
    },

    selectedItems(): CartItem[] {
      const set = new Set(this.selected);
      return this.validItems.filter((it) => set.has(it.skuNo));
    },
    selectedCount(): number {
      return this.selectedItems.reduce((n, it) => n + it.qty, 0);
    },
    selectedTotalFen(): number {
      return this.selectedItems.reduce((n, it) => n + it.price * it.qty, 0);
    },
    /** 当前组是不是全勾上了。空组不算「全选」，否则空车时全选框是亮的 */
    allSelected(): boolean {
      const g = this.activeGroup;
      if (!g || !g.items.length) return false;
      const set = new Set(this.selected);
      return g.items.every((it) => set.has(it.skuNo));
    },
    /** 编辑态：全车（含失效件）是不是都标上了 */
    allMarked(): boolean {
      if (!this.items.length) return false;
      const set = new Set(this.marked);
      return this.items.every((it) => set.has(it.skuNo));
    },
  },

  actions: {
    async load() {
      this.loading = true;
      try {
        this.items = await api.cartList();
        this.loaded = true;
      } finally {
        this.loading = false;
        this.syncSelection();
      }
    },

    /**
     * 让勾选与车里的现状重新对齐。**每次 load 之后必须跑**。
     *
     * 三件事，顺序不能换：
     *   ① 剪掉已经不在车里、或者已经失效的 skuNo —— 持久化下来的勾选会带着上一会话的残留，
     *      不剪的话它们会顶着「已经勾过了」，让默认勾选轮不到；
     *   ② 把跨组的勾选收敛回一组（车在别处被改过，比如换了社区之后某件的履约方式变了）；
     *   ③ 剪完什么都不剩、而车里还有货 → 默认勾件数最多的那一组。
     */
    syncSelection() {
      const valid = new Map(this.validItems.map((it) => [it.skuNo, it]));
      let kept = this.selected.filter((no) => valid.has(no));
      const first = kept.length ? valid.get(kept[0]!)!.fulfillment : null;
      if (first) kept = kept.filter((no) => valid.get(no)!.fulfillment === first);
      this.selected = kept;
      this.marked = this.marked.filter((no) => this.items.some((it) => it.skuNo === no));
      if (!this.selected.length && !this.touched) {
        this.selected = this.defaultGroup?.items.map((it) => it.skuNo) ?? [];
      }
      // 车空了就没什么可记的，回到「还没挑过」
      if (!this.items.length) this.touched = false;
    },

    isSelected(skuNo: string): boolean {
      return this.selected.includes(skuNo);
    },

    /**
     * 勾 / 取消勾一件。
     *
     * 勾的是另一种履约方式时，**前一组整组让位**，并把让位的那一组返回去 ——
     * 页面拿它出一句 toast。静默让位是不能接受的：用户会以为刚才勾的那几件还在。
     *
     * @returns 被让位的履约方式；没有发生让位时是 null
     */
    toggle(skuNo: string): FulfillmentType | null {
      const it = this.validItems.find((i) => i.skuNo === skuNo);
      if (!it) return null;
      this.touched = true;
      if (this.isSelected(skuNo)) {
        this.selected = this.selected.filter((no) => no !== skuNo);
        return null;
      }
      const active = this.activeFulfillment;
      if (active && active !== it.fulfillment) {
        this.selected = [skuNo];
        return active;
      }
      this.selected = [...this.selected, skuNo];
      return null;
    },

    /** 整组勾 / 取消。跨组时同样让位 */
    setGroup(fulfillment: FulfillmentType, on: boolean): FulfillmentType | null {
      const g = this.groups.find((x) => x.fulfillment === fulfillment);
      if (!g) return null;
      this.touched = true;
      const nos = g.items.map((it) => it.skuNo);
      if (!on) {
        this.selected = this.selected.filter((no) => !nos.includes(no));
        return null;
      }
      const active = this.activeFulfillment;
      if (active && active !== fulfillment) {
        this.selected = nos;
        return active;
      }
      this.selected = [...new Set([...this.selected, ...nos])];
      return null;
    },

    /** 一家店整段勾 / 取消。商家可能同时出现在两个履约组里，所以要连 fulfillment 一起给 */
    setMerchant(
      fulfillment: FulfillmentType,
      merchantNo: string,
      on: boolean,
    ): FulfillmentType | null {
      const g = this.groups.find((x) => x.fulfillment === fulfillment);
      const seg = g?.merchants.find((m) => m.merchantNo === merchantNo);
      if (!seg) return null;
      this.touched = true;
      const nos = seg.items.map((it) => it.skuNo);
      if (!on) {
        this.selected = this.selected.filter((no) => !nos.includes(no));
        return null;
      }
      const active = this.activeFulfillment;
      if (active && active !== fulfillment) {
        this.selected = nos;
        return active;
      }
      this.selected = [...new Set([...this.selected, ...nos])];
      return null;
    },

    /** 底栏「全选」：作用于当前组（没勾任何东西时是默认组），不是全车 */
    setAllInActive(on: boolean) {
      const g = this.activeGroup;
      if (!g) return;
      this.touched = true;
      this.selected = on ? g.items.map((it) => it.skuNo) : [];
    },

    // ---- 编辑态 ----------------------------------------------------------

    isMarked(skuNo: string): boolean {
      return this.marked.includes(skuNo);
    },
    toggleMark(skuNo: string) {
      this.marked = this.isMarked(skuNo)
        ? this.marked.filter((no) => no !== skuNo)
        : [...this.marked, skuNo];
    },
    /** 编辑态的全选跨组、且含失效件 —— 那一刻的动作是「删」，与结算的分组无关 */
    setAllMarked(on: boolean) {
      this.marked = on ? this.items.map((it) => it.skuNo) : [];
    },
    clearMarks() {
      this.marked = [];
    },
    /** 删掉标记的那些。**这是用户点出来的批量删，不是替他清理** */
    async removeMarked() {
      if (!this.marked.length) return;
      const nos = [...this.marked];
      this.marked = [];
      await this.remove(nos);
    },

    // ---- 服务端 ----------------------------------------------------------

    async add(goodsNo: string, skuNo: string, qty = 1) {
      this.items = await api.cartAdd(goodsNo, skuNo, qty);
      /*
       * 刚加进来的这件默认勾上 —— 加购之后就是去结算，中间不该再点一次。
       *
       * **但绝不因此挤掉别的组**：加购发生在商品页，用户看不见购物车，
       * 在那里把他之前勾的三件静默取消，他要到结算时才发现少了东西。
       * 履约方式对不上就什么都不做，让他自己进购物车里挑。
       */
      const added = this.validItems.find((i) => i.skuNo === skuNo);
      const active = this.activeFulfillment;
      if (added && !this.isSelected(skuNo) && (!active || active === added.fulfillment)) {
        this.selected = [...this.selected, skuNo];
      }
      this.syncSelection();
    },
    async update(skuNo: string, qty: number) {
      this.items = await api.cartUpdate(skuNo, qty);
      this.syncSelection();
    },
    async remove(skuNos: string[]) {
      this.items = await api.cartRemove(skuNos);
      this.selected = this.selected.filter((no) => !skuNos.includes(no));
      this.syncSelection();
    },
    /** 切换社区后：移除在新社区不可售的商品（由后端标 `invalid`） */
    async refreshOnCommunityChange() {
      await this.load();
    },
  },

  persist: {
    key: STORAGE.cart,
    /** `marked` 不持久化：编辑态是一次操作，不该跨会话活着 */
    pick: ["items", "selected"],
  },
});
