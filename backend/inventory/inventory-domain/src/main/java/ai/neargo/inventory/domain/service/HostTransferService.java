package ai.neargo.inventory.domain.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 宿主接口的账内调拨（TDD §2.21）：同一本账里的货换库位，SN 物料随件走、组装关系与放行凭证跟着走。
 *
 * <p>建单 → 发运（调出仓出、进本账本的在途库位）→ 收货（在途出、进调入仓）；草稿可作废。
 * 跨账本不走这里，走出库对象的一出一入（{@link PairedTransferService}）。
 */
public interface HostTransferService {

    /**
     * @param serialNos 扫到的 SN；扫上级件带出它挂着的下级件（柜 → 宝）
     * @throws ai.neargo.inventory.api.InventoryException {@code BAD_REQUEST} 调出调入同一库位、或调入在途库位
     */
    String create(Caller caller, Draft draft);

    /** @throws ai.neargo.inventory.api.InventoryException {@code CONFLICT status} 不是草稿 */
    void ship(Caller caller, String transferNo);

    /** @throws ai.neargo.inventory.api.InventoryException {@code CONFLICT status} 还没发运或已收 */
    void receive(Caller caller, String transferNo);

    /** 只有草稿能作废；已发运的货在路上，要收回走一张反向调拨 */
    void cancel(Caller caller, String transferNo);

    View get(String ownerId, String transferNo);

    record Draft(String fromLocationId, String toLocationId, List<HostOutboundService.Line> lines,
                 List<String> serialNos, String remark) {
    }

    record Line(String itemId, int qty, List<String> serialNos) {
    }

    record View(String transferNo, String status, String fromLocationId, String toLocationId,
                String shippedOutboundNo, String receivedInboundNo, LocalDateTime shippedAt,
                LocalDateTime receivedAt, List<Line> lines) {
    }
}
