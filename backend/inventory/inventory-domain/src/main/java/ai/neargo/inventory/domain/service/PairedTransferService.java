package ai.neargo.inventory.domain.service;

import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvTargetRef;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨账本成对单据（TDD §2.19）：货发给关联了账本的出库对象（兄弟门店、加盟店）时，一出一入。
 *
 * <p>发货方出库过账的同一事务里，在收货方账本生成待收入库单（草稿）并记下成对记录；收货方按实收确认后入账，
 * 差异回传发货方。两本账都必须同系统、写入方是独立服务；本版只支持数量物料。
 */
public interface PairedTransferService {

    /**
     * 出库建单时的结构检查：对象必须关联了账本，单上不能有 SN。
     *
     * @throws ai.neargo.inventory.api.InventoryException {@code BAD_REQUEST target.not_linked} /
     *         {@code paired.serial_unsupported}
     */
    void checkShippable(InvTargetRef target, List<String> serialNos);

    /**
     * 出库过账时调用（同一事务）：在收货方账本建待收入库单、记成对记录、给收货方发事件。
     *
     * @param receiptPurpose 收货方入库单的用途（档案 {@code receiptPurpose}）
     * @throws ai.neargo.inventory.api.InventoryException {@code NOT_WRITER} 收货方写入方不是独立服务；
     *         {@code BAD_REQUEST paired.item_ref} 发货方物料没有宿主编号，两边对不上
     */
    void ship(Caller caller, InvOutboundOrder order, List<InvOutboundLine> lines, InvTargetRef target,
              String receiptPurpose);

    /**
     * 收货方按实收确认：没列出的行算 0，可多可少；全为 0 时入库单作废、成对记录照样记为已收。
     *
     * @return 成对单号
     * @throws ai.neargo.inventory.api.InventoryException {@code NOT_FOUND paired} 不是成对入库单；
     *         {@code CONFLICT status} 已经收过
     */
    String receive(Caller caller, String inboundNo, List<Received> lines);

    /** 这张入库单是不是成对入库单：是就不许直接过账或作废，只能确认收货。 */
    boolean pairedInbound(String ownerId, String inboundNo);

    /** @param direction {@code OUT} 本账本发出的 / {@code IN} 本账本收的；@param status 空 = 全部 */
    List<View> list(String ownerId, String direction, String status);

    /** 只返回本账本作为发货方或收货方参与的那一条 */
    View get(String ownerId, String pairNo);

    record Received(String itemId, int qty) {
    }

    record Side(String ownerId, String ownerType, String ownerRef, String docNo, String locationId) {
    }

    record Line(String itemRef, String fromItemId, String toItemId, int shipped, Integer received) {
    }

    record View(String pairNo, String status, Side from, Side to, LocalDateTime shippedAt,
                LocalDateTime receivedAt, List<Line> lines) {
    }
}
