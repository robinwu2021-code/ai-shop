import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import type { CartItem } from "@shared/types";

const server = vi.hoisted(() => ({ items: [] as CartItem[] }));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ token: "same-account" }) }));
vi.mock("@shared/ports/share", () => ({ inTimelineSinglePage: () => false }));
vi.mock("@/api", () => ({ api: {
  cartList: async () => server.items.map(i => ({ ...i })),
  cartAdd: async (goodsNo: string, skuNo: string, qty: number) => {
    const row = server.items.find(i => i.skuNo === skuNo);
    if (row) row.qty += qty;
    else server.items.push({ goodsNo, skuNo, qty, fulfillment: "PICKUP" } as CartItem);
    return server.items.map(i => ({ ...i }));
  },
  cartUpdate: async (skuNo: string, qty: number) => {
    server.items.find(i => i.skuNo === skuNo)!.qty = qty;
    return server.items.map(i => ({ ...i }));
  },
  cartRemove: async (skuNos: string[]) => {
    server.items = server.items.filter(i => !skuNos.includes(i.skuNo));
    return server.items.map(i => ({ ...i }));
  },
} }));

import { useCartStore } from "@/stores/cart";

describe("repurchase after abandoning checkout", () => {
  beforeEach(() => { setActivePinia(createPinia()); server.items = []; });

  it("sets the chosen quantity again even when local cart state was cleared", async () => {
    const cart = useCartStore();
    for (let attempt = 0; attempt < 3; attempt++) {
      cart.items = [];
      await cart.setForCheckout("G1", "S1", 1);
      expect(server.items).toHaveLength(1);
      expect(server.items[0]!.qty).toBe(1);
      expect(cart.items[0]!.qty).toBe(1);
    }
    await cart.setForCheckout("G1", "S1", 3);
    expect(server.items[0]!.qty).toBe(3);
  });

  it("removes the checkout item and allows buying it again without restoring the old quantity", async () => {
    const cart = useCartStore();
    await cart.setForCheckout("G1", "S1", 2);
    await cart.remove(["S1"]);
    expect(server.items).toEqual([]);
    expect(cart.items).toEqual([]);
    expect(cart.selected).toEqual([]);
    await cart.setForCheckout("G1", "S1", 1);
    expect(server.items[0]!.qty).toBe(1);
  });
});
