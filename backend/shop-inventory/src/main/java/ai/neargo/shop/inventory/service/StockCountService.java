package ai.neargo.shop.inventory.service;

import java.util.List;

/**
 * 盘点。**盘点自己不改余额** —— 盈生成入库单、亏生成出库单，走统一的过账口。
 * 少一条改余额的路径，就少一处要证明的正确性。
 */
public interface StockCountService {

    /**
     * 开单：**把账面数锁下来**。
     *
     * <p>从开盘到过账之间店里还在卖。不快照的话，差异 = 实盘 − 过账时的账面，
     * 把这中间正常卖掉的量算成了盘亏 —— 而商家会去追一个根本不存在的损失。
     */
    String open(String ownerId, String locationId, List<String> itemIds, String operator);

    /** 录实盘。全量覆盖：传进来的行即是全部。 */
    void fill(String ownerId, String countNo, List<Filled> lines);

    /**
     * 过账。{@code diff > 0} 生成盘盈入库单、{@code diff < 0} 生成盘亏出库单，
     * {@code diff = 0} 的行**不生成任何东西** —— 否则一次全店盘点会产生几百行「变动 0」的流水。
     */
    void post(String ownerId, String countNo, String operator);

    /**
     * 单件「改数」：开单 + 录一行 + 过账，一次调用做完。
     *
     * <p>界面上的按钮叫「改数」，但底下**仍然是一张盘点单** ——
     * 所有余额变动都必须有单据，这条不因为「只改一件」而放宽。
     * 给它一个便捷方法只是省掉三次往返，不是开第二条路径。
     */
    void adjustOne(String ownerId, String locationId, String itemId, int countedQty,
                   String reasonCode, String operator);

    /**
     * 读回一张盘点单（含<b>开单那一刻的账面数</b>）。
     *
     * <p>没有这个口的时候，端上只能拿当前余额当账面数显示 ——
     * 而盘的过程中照常卖，那样算出来的差异会把中间卖掉的量记成盘亏。
     * 这一条正是 {@code countUsesSnapshotBookQty} 守的那件事，
     * 界面上也得守住，否则不变式只在服务层成立。
     */
    ai.neargo.shop.inventory.dto.InventoryVOs.CountVO detail(String ownerId, String countNo);

    record Filled(String itemId, int countedQty, String reasonCode) {
    }
}
