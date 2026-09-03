// 商品与购物车 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import type { GoodsQuery } from "../contract";
import { allGoods, db, delay, findGoodsSeed, paginate, persist, toGoods } from "@shared/mock/db";
import { defaultFulfillment } from "@shared/utils/goods";
import { buyNGetM, giftQtyFor } from "@shared/utils/promotion";
import {
  distanceOf,
  reaches,
} from "./_shared";
import type { ShopApi } from "../contract";

export const catalogMock: Pick<ShopApi,
  "goodsList"
  | "goodsDetail"
  | "cartList"
  | "cartAdd"
  | "cartUpdate"
  | "cartRemove"
> = {
  // ---------------------------------------------------------------- 商品
  async goodsList(q: GoodsQuery) {
    let list = allGoods().filter((g) => g.onSale);
    /*
     * 社区过滤是**邻里购物的第一约束**，不是排序偏好：
     * 隔壁区的生鲜店送不到我的自提点，它的商品出现在我的首页就是纯噪音。
     * 所以覆盖范围之外的直接**滤掉**，而不是排到后面。
     * serviceCommunityNos 为空 = 全域可售（平台自营、虚拟商品这类没有履约半径的），永远保留。
     */
    if (q.communityNo) {
      const cno = q.communityNo;
      list = list.filter((g) => reaches(g.merchant.merchantNo, cno));
      // 同在范围内时按距离近的在前 —— 近的能更早拿到货，也更可能是熟脸
      list = list.sort((a, b) => distanceOf(a) - distanceOf(b));
    }
    if (q.merchantNo) list = list.filter((g) => g.merchant.merchantNo === q.merchantNo);
    if (q.type) list = list.filter((g) => g.type === q.type);
    if (q.categoryNo) list = list.filter((g) => g.categoryNo === q.categoryNo);
    if (q.keyword) {
      const k = q.keyword.trim().toLowerCase();
      list = list.filter(
        (g) => g.title.toLowerCase().includes(k) || g.subtitle.toLowerCase().includes(k),
      );
    }
    return delay(paginate(list, q.page, q.size));
  },

  async goodsDetail(goodsNo) {
    return delay(toGoods(findGoodsSeed(goodsNo)));
  },

  // ---------------------------------------------------------------- 购物车
  async cartList() {
    // 购物车里的 title/spec 是加购当时的语言快照，按当前语言重算一遍
    // （真实后端同理：购物车存 goodsNo/skuNo，返回时按 Accept-Language 本地化）
    db.cart = db.cart.map((it) => {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      // 赠品件数由促销规则实时算，不存库 —— 存下来会与规则漂移
      const promo = buyNGetM(g.promotions);
      return {
        ...it,
        title: g.title,
        spec: sku?.spec ?? it.spec,
        giftQty: giftQtyFor(promo, it.qty),
        giftLabel: promo ? `${promo.buyN}+${promo.giftM}` : undefined,
      };
    });
    // 这是读操作，不落盘 —— 只是把标题按当前语言重算了一遍
    return delay([...db.cart]);
  },

  async cartAdd(goodsNo, skuNo, qty) {
    const seed = findGoodsSeed(goodsNo);
    const g = toGoods(seed);
    const sku = g.skus.find((s) => s.skuNo === skuNo);
    if (!sku) throw new Error("规格不存在");
    // 生鲜截单校验：截单后不可加购
    if (g.cutoffAt && Date.now() > g.cutoffAt) throw new Error("已过今日截单时间");
    const exist = db.cart.find((c) => c.skuNo === skuNo);
    if (exist) {
      exist.qty += qty;
    } else {
      db.cart.push({
        goodsNo,
        skuNo,
        title: g.title,
        cover: g.cover,
        spec: sku.spec,
        price: sku.price,
        qty,
        type: g.type,
        fulfillment: defaultFulfillment(g),
        // 商家：购物车与确认页要按它分段（一段 = 一笔子订单）。
        // 不带这两个字段的话，mock 下所有商品会聚成同一段，
        // 而那正是这个缺口此前藏了这么久的样子 —— 看起来「就是一单」
        merchantNo: g.merchant.merchantNo,
        merchantName: g.merchant.name,
      });
    }
    // 限购校验
    if (g.limitPerUser > 0) {
      const item = db.cart.find((c) => c.skuNo === skuNo)!;
      if (item.qty > g.limitPerUser) {
        item.qty = g.limitPerUser;
        throw new Error(`每人限购 ${g.limitPerUser} 件`);
      }
    }
    persist();
    return delay([...db.cart]);
  },

  async cartUpdate(skuNo, qty) {
    const item = db.cart.find((c) => c.skuNo === skuNo);
    if (item) {
      if (qty <= 0) db.cart = db.cart.filter((c) => c.skuNo !== skuNo);
      else item.qty = qty;
    }
    persist();
    return delay([...db.cart]);
  },

  async cartRemove(skuNos) {
    db.cart = db.cart.filter((c) => !skuNos.includes(c.skuNo));
    persist();
    return delay([...db.cart]);
  },
};
