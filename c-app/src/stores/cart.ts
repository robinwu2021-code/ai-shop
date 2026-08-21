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

export const useCartStore = defineStore("cart", {
  state: () => ({
    items: [] as CartItem[],
    loading: false,
  }),

  getters: {
    count: (s) => s.items.reduce((n, it) => n + it.qty, 0),
    validItems: (s) => s.items.filter((it) => !it.invalidReason),
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
  },

  actions: {
    async load() {
      this.loading = true;
      try {
        this.items = await api.cartList();
      } finally {
        this.loading = false;
      }
    },
    async add(goodsNo: string, skuNo: string, qty = 1) {
      this.items = await api.cartAdd(goodsNo, skuNo, qty);
    },
    async update(skuNo: string, qty: number) {
      this.items = await api.cartUpdate(skuNo, qty);
    },
    async remove(skuNos: string[]) {
      this.items = await api.cartRemove(skuNos);
    },
    /** 切换社区后：移除在新社区不可售的商品（由后端标 invalidReason） */
    async refreshOnCommunityChange() {
      await this.load();
    },
  },

  persist: {
    key: STORAGE.cart,
    pick: ["items"],
  },
});
