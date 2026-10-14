package ai.neargo.shop.inventory.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过账 —— <b>全域唯一改变 {@code on_hand} 的地方</b>。入库单与出库单都从这里过。
 *
 * <p><b>为什么收口在一个方法</b>：不变式 I1（{@code available >= 0}）与
 * I2（余额每一次变动必有且仅有一行流水）**各只需要在这里证明一次**。
 * 分散到六个 Service 各写各的，就要证明六次，
 * 而第七个人加第七条路径时没有任何东西会拦他。
 *
 * <p>预留不走这里 —— 它只动 {@code reserved}，不动实存（不变式 I5）。
 */
public interface StockPostingService {

    /**
     * 过账一张单。
     *
     * @throws ai.neargo.shop.common.BizException {@code STOCK_NOT_ENOUGH} —— 出库会把实存扣成负数时。
     *         <b>错误里带上是哪几件、各差多少</b>：只回一句「库存不足」，用户要逐个试才知道差在哪
     * @return 本次产生的流水 id，顺序与 {@code doc.lines()} 一致
     */
    List<Long> post(PostingDoc doc);

    /**
     * 作废：**写反向流水**，不删原行、不改原单。
     *
     * <p>作废本身也是一次过账（方向相反），所以它同样从这里过 ——
     * 给作废开第二条改余额的路，等于把「唯一入口」这条约定作废掉。
     */
    List<Long> reverse(String ownerId, String docNo, String docKind, String operator);

    /**
     * 要过账的一张单。**它不是实体** —— 单据头/行由各自的 Service 负责落库，
     * 这里只要「往哪个库位、哪个物料、加减多少」。
     *
     * @param docKind    {@code IN} / {@code OUT}
     * @param reasonCode 单据 sourceType / purpose 的快照，落到流水上供报表分组
     * @param occurredAt <b>业务发生时间</b>，可回填。报表按它归期，不按落库时间
     */
    record PostingDoc(String ownerId, String docKind, String docNo, String reasonCode,
                      LocalDateTime occurredAt, String operator, List<Line> lines) {
    }

    /** @param unitCostMinor 过账那一刻的成本快照；出库时由计价规则算出，入库时来自进货单价 */
    record Line(int lineNo, String itemId, String locationId, int qty, Long unitCostMinor) {
    }
}
