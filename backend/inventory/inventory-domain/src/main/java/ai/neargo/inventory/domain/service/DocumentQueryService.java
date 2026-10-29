package ai.neargo.inventory.domain.service;

import java.math.BigDecimal;
import java.util.List;

/** 单据详情：头、行、逐件 SN、目标快照（TDD §2.13 {@code GET /inbounds/{no}}、{@code GET /outbounds/{no}}）。 */
public interface DocumentQueryService {

    /** @throws ai.neargo.inventory.api.InventoryException {@code NOT_FOUND} */
    Document inbound(String ownerId, String inboundNo);

    /** @throws ai.neargo.inventory.api.InventoryException {@code NOT_FOUND} */
    Document outbound(String ownerId, String outboundNo);

    record Document(String docNo, String kind, String status, String purpose, String locationId,
                    BigDecimal totalQty, Integer profileVersion, List<Line> lines, List<Serial> serials,
                    List<Target> targets) {
    }

    record Line(int lineNo, String itemId, BigDecimal qty, String gateRef) {
    }

    record Serial(int lineNo, String itemId, String serialNo, String parentSerialNo, String gateRef) {
    }

    record Target(String type, String ref, String nameSnapshot) {
    }
}
