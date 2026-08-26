package ai.neargo.shop.invbridge;

import java.util.List;

/**
 * 平台级库存健康度：<b>不知道该看谁</b>时的那一屏。
 *
 * <h2>为什么它在 shop-app，而不在 shop-inventory 里</h2>
 * 三类里有一类（零库存仍在架）**必须同时读两边**：「有多少」在进销存库，
 * 「还在不在卖」在平台的 {@code prd_sku}。而进销存域不认识平台的表 ——
 * 这条边界正是它能独立交付的原因。所以它和
 * {@link InventoryBackfillService} 一样落在装配层。
 *
 * <p>进销存那侧同名的 {@code /ops/inventory/health} 曾经存在，其实是
 * <b>单个商家的余额列表</b>（{@code entityNo} 必填），已改名 {@code /ops/inventory/balances}。
 * 名字撞车的代价是运营端照着名字接了过来，拿到的却是 400。
 *
 * <h2>为什么这一页空着是好事</h2>
 * 这三类商品**正在给买家制造失败的下单** —— 点进去、加购、然后发现买不了。
 * 而那次点击是花钱买来的：一个下不了单的商品比没有这个商品更贵。
 */
public interface InventoryHealthService {

    /**
     * @param kind  null / ALL = 三类都要；否则只要这一类
     * @param limit 一次最多扫多少个 SKU。<b>不是返回多少行</b> —— 扫描是分批的，
     *              上限落在扫描量上才防得住「平台有十万个 SKU」那天
     */
    List<HealthRow> scan(String kind, int limit);

    /**
     * @param kind     NEGATIVE 负库存 · ZERO_ON_SALE 零库存仍在架 · STALE 长期未动销
     * @param idleDays 仅 STALE 有值：多少天没动过
     */
    record HealthRow(String kind, String entityNo, String merchantName, String storeNo,
                     String itemId, String itemName, String specText,
                     int onHand, int reserved, int available, Integer idleDays) {
    }
}
