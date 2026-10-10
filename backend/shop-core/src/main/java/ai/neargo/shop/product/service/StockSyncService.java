package ai.neargo.shop.product.service;

import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.entity.PrdStoreStockSync;

import java.util.List;

/**
 * 门店库存第二期：进销存 → 商城写回的商品域那一半（TDD-商品纳入进销存开关 §4 / §6 / §7 / §18）。
 *
 * <p>这里只管「规则、开关、按目标值改商城、记明细」。可用数（实存 − 占用 − 安全库存）
 * 与「镜像追平了没有」要看进销存，商品域看不到，由 shop-app 的写回编排算好了传进来。
 */
public interface StockSyncService {

    // ------------------------------------------------------------------ 规则

    /** 生效的一条规则。{@code scopeType} 说明它是从哪一级取到的；没有任何行时是 STORE 级的 ALL */
    record Rule(String scopeType, String scopeRef, String ruleType, int param) {
    }

    /** 这件商品在这家店的生效规则：单品 › 品类 › 本店默认 › 全部可售 */
    Rule ruleOf(String storeNo, String goodsNo, String categoryNo);

    /** 本店设过的所有规则（设置页用） */
    List<PrdSellRule> rules(String storeNo);

    /**
     * 存一条规则。只改不删；{@code ruleType=ALL} 且不是 STORE 级时等于「回到上一级」—— 仍存一行，
     * 取值时 ALL 行照常生效（显式的「全部可售」可以盖住品类的「保留 5 件」）。
     *
     * @return 存下的那一行
     */
    PrdSellRule saveRule(String storeNo, String scopeType, String scopeRef, String ruleType, int param,
                         String operator);

    /**
     * 按规则从可用数算目标值 T（§4 表）。MANUAL 返回 param 与可用取小 —— 手动的写法见 {@link #apply}。
     * 纯函数，单测直接打它。
     */
    static int target(Rule rule, int available) {
        int u = Math.max(0, available);
        return switch (rule.ruleType()) {
            case PrdSellRule.RESERVE -> Math.max(0, u - Math.max(0, rule.param()));
            case PrdSellRule.RATIO -> (int) Math.floor(u * Math.min(100, Math.max(0, rule.param())) / 100.0);
            case PrdSellRule.CAP, PrdSellRule.MANUAL -> Math.min(u, Math.max(0, rule.param()));
            default -> u;
        };
    }

    // ------------------------------------------------------------------ 开关与对齐

    /** 本店的同步状态；从没设过返回 {@code null} */
    PrdStoreStockSync syncOf(String storeNo);

    boolean enabled(String storeNo);

    /** 记下期初对齐。{@code mode} 为 MALL / COUNT */
    PrdStoreStockSync markAligned(String entityNo, String storeNo, String mode, String operator);

    /** 打开 / 关闭同步。打开要求已对齐，否则抛 {@code STOCK_SYNC_NOT_ALIGNED} */
    PrdStoreStockSync setEnabled(String entityNo, String storeNo, boolean enabled, String operator);

    // ------------------------------------------------------------------ 写回

    /**
     * 商城这一侧现在的线上可卖。
     *
     * @param mode STORE 按店库存行 / ENTITY 主体级（没有任何按店行）/ NONE 查不到这个 SKU
     */
    record Sellable(String mode, int sellable, int locked) {
    }

    Sellable sellableOf(String storeNo, String skuNo);

    /**
     * @param applied  改了商城（或者本来就等于目标，也算已处理）
     * @param skipped  为什么没改：DONE_BEFORE 这个来源已处理过 / NO_SKU / ENTITY_MULTI_STORE 主体级库存且多店
     */
    record Result(boolean applied, String skipped, String ruleType, int available, int before, int after) {
    }

    /**
     * 按规则把这家店这个 SKU 的线上可卖对齐到目标值，并记一行明细（同一事务）。
     *
     * <p><b>幂等</b>：{@code (sourceRef, storeNo, skuNo)} 已有明细 → 什么都不做，返回 DONE_BEFORE。
     * <b>主体级库存且主体不止一家店</b>：不改（改主体级会把几家店的数混在一起；建按店行会让其它店变 0）。
     *
     * @param available 可用 = 实存 − 占用 − 安全库存，由调用方从进销存算好
     */
    Result apply(String entityNo, String storeNo, String skuNo, int available, String sourceRef, String operator);

    /**
     * 店主当场把线上放多少定死（§8 的「改库存」）：线上可卖 = min(额度, 可用)，实存不动。
     *
     * <p><b>与 {@link #apply} 分开是有意的</b>：写回对手动规则只做「压到可用」，从不往上抬 ——
     * 那是为了不让进货悄悄把店主设的数改大。而这一次是店主自己按的，要按他说的数写，
     * 包括把线上从 4 调回 9。两者用同一张明细表，来源不同。
     */
    Result applyQuota(String entityNo, String storeNo, String skuNo, int qty, int available, String sourceRef,
                      String operator);
}
