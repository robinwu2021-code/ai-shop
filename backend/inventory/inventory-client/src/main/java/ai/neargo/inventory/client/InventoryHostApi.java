package ai.neargo.inventory.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存服务宿主接口的声明式描述（TDD §2.23）：主数据、单据、调拨、成对单据、出库池、查询、事件、命令。
 * 电商（系统凭证 + 账本头）与 ShareHub（账本凭证，账本头留空）用同一份；直接用 {@link InventoryClient}。
 *
 * <p>每个方法的前四个头：账本编号、账本类型（系统凭证时用，账本凭证留空）、幂等键（写操作）、操作人。
 * 单据详情等结构多变的响应用 {@link JsonNode}，其余是与服务端同名字段的记录。
 */
@HttpExchange("/api/inventory/v1")
public interface InventoryHostApi {

    String OWNER_REF = "X-Inventory-Owner-Ref";
    String OWNER_TYPE = "X-Inventory-Owner-Type";
    String IDEMPOTENCY_KEY = "Idempotency-Key";
    String ACTOR = "X-Inventory-Actor";

    // ── 主数据 ────────────────────────────────────────────────────────────

    @PutExchange("/items/by-ref/{ref}")
    Upserted upsertItem(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                        @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                        @RequestHeader(value = ACTOR, required = false) String actor,
                        @PathVariable("ref") String ref, @RequestBody ItemSpec spec);

    @PutExchange("/external-targets/{type}/{ref}")
    JsonNode syncTarget(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                        @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                        @RequestHeader(value = ACTOR, required = false) String actor,
                        @PathVariable("type") String type, @PathVariable("ref") String ref,
                        @RequestBody TargetSync sync);

    @GetExchange("/external-targets")
    List<JsonNode> targets(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                           @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                           @RequestParam(value = "type", required = false) String type,
                           @RequestParam(value = "parentRef", required = false) String parentRef);

    @GetExchange("/warehouses")
    List<Warehouse> warehouses(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                               @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                               @RequestParam(value = "holderRef", required = false) String holderRef);

    @PostExchange("/warehouses")
    Created createWarehouse(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                            @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                            @RequestHeader(IDEMPOTENCY_KEY) String key,
                            @RequestHeader(value = ACTOR, required = false) String actor,
                            @RequestBody WarehouseCreate body);

    // ── 入库 ──────────────────────────────────────────────────────────────

    @PostExchange("/inbounds")
    DocNo createInbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                        @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                        @RequestHeader(value = ACTOR, required = false) String actor,
                        @RequestBody InboundCreate body);

    @PostExchange("/inbounds/{no}/post")
    DocNo postInbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                      @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                      @RequestHeader(IDEMPOTENCY_KEY) String key,
                      @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no);

    @PostExchange("/inbounds/{no}/cancel")
    DocNo cancelInbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                        @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                        @RequestHeader(IDEMPOTENCY_KEY) String key,
                        @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no);

    @PostExchange("/inbounds/{no}/receive")
    Receipt receive(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                    @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                    @RequestHeader(IDEMPOTENCY_KEY) String key,
                    @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no,
                    @RequestBody Receive body);

    @GetExchange("/inbounds/{no}")
    JsonNode inbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                     @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                     @PathVariable("no") String no);

    // ── 出库 ──────────────────────────────────────────────────────────────

    @PostExchange("/outbounds")
    DocNo createOutbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestHeader(IDEMPOTENCY_KEY) String key,
                         @RequestHeader(value = ACTOR, required = false) String actor,
                         @RequestBody OutboundCreate body);

    @PatchExchange("/outbounds/{no}")
    DocNo appendOutbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestHeader(IDEMPOTENCY_KEY) String key,
                         @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no,
                         @RequestBody OutboundAppend body);

    @PostExchange("/outbounds/{no}/post")
    DocNo postOutbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                       @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                       @RequestHeader(IDEMPOTENCY_KEY) String key,
                       @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no);

    @PostExchange("/outbounds/{no}/cancel")
    DocNo cancelOutbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestHeader(IDEMPOTENCY_KEY) String key,
                         @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no);

    @GetExchange("/outbounds/{no}")
    JsonNode outbound(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                      @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                      @PathVariable("no") String no);

    // ── 账内调拨 ──────────────────────────────────────────────────────────

    @PostExchange("/transfers")
    DocNo createTransfer(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestHeader(IDEMPOTENCY_KEY) String key,
                         @RequestHeader(value = ACTOR, required = false) String actor,
                         @RequestBody TransferCreate body);

    @PostExchange("/transfers/{no}/{action}")
    DocNo transferAction(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestHeader(IDEMPOTENCY_KEY) String key,
                         @RequestHeader(value = ACTOR, required = false) String actor, @PathVariable("no") String no,
                         @PathVariable("action") String action);

    @GetExchange("/transfers/{no}")
    JsonNode transfer(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                      @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                      @PathVariable("no") String no);

    // ── 成对单据 ──────────────────────────────────────────────────────────

    @GetExchange("/paired-transfers")
    List<JsonNode> pairedTransfers(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                                   @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                                   @RequestParam("direction") String direction,
                                   @RequestParam(value = "status", required = false) String status);

    // ── 出库池 ────────────────────────────────────────────────────────────

    @GetExchange("/dispatch-pool")
    List<JsonNode> dispatchPool(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                                @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                                @RequestParam(value = "status", required = false) List<String> status,
                                @RequestParam(value = "rootTargetRef", required = false) List<String> rootTargetRef,
                                @RequestParam(value = "serialNo", required = false) String serialNo);

    @PostExchange("/dispatch-pool/claims")
    Claim claim(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                @RequestHeader(IDEMPOTENCY_KEY) String key,
                @RequestHeader(value = ACTOR, required = false) String actor, @RequestBody ClaimRequest body);

    @PostExchange("/dispatch-pool/{dispatchId}/revoke")
    DocNo revoke(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                 @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                 @RequestHeader(IDEMPOTENCY_KEY) String key,
                 @RequestHeader(value = ACTOR, required = false) String actor,
                 @PathVariable("dispatchId") String dispatchId, @RequestBody Reason body);

    // ── 查询 ──────────────────────────────────────────────────────────────

    @GetExchange("/balances")
    List<Balance> balances(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                           @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                           @RequestParam(value = "itemRef", required = false) String itemRef,
                           @RequestParam(value = "locationRef", required = false) String locationRef);

    @GetExchange("/serials")
    List<Serial> serials(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                         @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                         @RequestParam(value = "itemRef", required = false) String itemRef,
                         @RequestParam(value = "locationRef", required = false) String locationRef);

    @GetExchange("/in-transit")
    List<InTransit> inTransit(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                              @RequestHeader(value = OWNER_TYPE, required = false) String ownerType);

    @GetExchange("/events")
    JsonNode events(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                    @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                    @RequestParam(value = "afterSeq", required = false) Long afterSeq,
                    @RequestParam(value = "limit", required = false) Integer limit);

    @PostExchange("/event-acks")
    JsonNode ackEvents(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                       @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                       @RequestBody Ack body);

    /** 结果未知时按原幂等键查命令结果 */
    @GetExchange("/commands/{requestId}")
    JsonNode command(@RequestHeader(value = OWNER_REF, required = false) String ownerRef,
                     @RequestHeader(value = OWNER_TYPE, required = false) String ownerType,
                     @PathVariable("requestId") String requestId);

    /** 系统级：多账本批量余额（只认系统凭证） */
    @PostExchange("/system/balances/query")
    List<OwnerBalances> balancesAcross(@RequestBody BalancesQuery body);

    // ── 请求 / 响应 ───────────────────────────────────────────────────────

    record ItemSpec(String name, String baseUom, String trackingMode, String gatePolicy, String gateRefType,
                    String attributes) {
    }

    record Upserted(String itemId) {
    }

    record OwnerRef(String type, String ref) {
    }

    /** @param linkedOwner 对象在同系统里也有账本（兄弟门店、加盟店）时填，发给它的出库一出一入 */
    record TargetSync(String name, String parentRef, String status, Long sourceVersion, OwnerRef linkedOwner,
                      String linkedLocationRef) {
    }

    record WarehouseCreate(String name, String externalRef, String holderRef) {
    }

    record Warehouse(String locationId, String name, String externalRef, String regionRef, String holderRef,
                     String status) {
    }

    record Created(String locationId) {
    }

    record SerialIn(String serialNo, String gateRef, String parentSerialNo) {
    }

    record InLine(String itemId, String itemRef, Integer qty, String gateRef, List<SerialIn> serials) {
    }

    record InboundCreate(String locationId, String locationRef, String purpose, String sourceRef, String basis,
                         List<String> attachmentRefs, String remark, List<InLine> lines) {
    }

    record OutLine(String itemId, String itemRef, Integer qty, String parentSerialNo) {
    }

    record OutboundCreate(String locationId, String locationRef, String purpose, String reasonCode,
                          Map<String, String> targets, String remark, List<OutLine> lines, List<String> serialNos) {
    }

    record OutboundAppend(List<OutLine> lines, List<String> serialNos) {
    }

    record DocNo(String docNo) {
    }

    record Received(String itemId, String itemRef, int qty) {
    }

    record Receive(List<Received> lines) {
    }

    record Receipt(String docNo, String pairNo) {
    }

    record QtyLine(String itemId, String itemRef, int qty) {
    }

    record TransferCreate(String fromLocationId, String fromLocationRef, String toLocationId, String toLocationRef,
                          List<QtyLine> lines, List<String> serialNos, String remark) {
    }

    record ClaimRequest(List<String> dispatchIds, String businessRef) {
    }

    record Claim(String claimNo, List<String> dispatchIds) {
    }

    record Reason(String reason) {
    }

    record Balance(String itemId, String itemRef, String locationId, String locationRef, BigDecimal onHand,
                   BigDecimal reserved, BigDecimal unavailable, BigDecimal available) {
    }

    record Serial(String serialNo, String itemId, String itemRef, String locationId, String locationRef,
                  String parentSerialNo, String episodeId, String gateRef, List<String> holdReasons) {
    }

    record InTransit(String kind, String rootTargetType, String rootTargetRef, String itemId, BigDecimal qty,
                     String docNo) {
    }

    record Ack(long seq) {
    }

    record OwnerKey(String type, String ref) {
    }

    record BalancesQuery(List<OwnerKey> owners, Set<String> itemRefs) {
    }

    record OwnerBalances(String ownerType, String ownerRef, String error, List<Balance> balances) {
    }
}
