package ai.neargo.shop.spi.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Incoming restaurant mirrors grant no native posting or reservation authority. */
public interface InventoryFoodStockProjectionPort {
    record Scope(String entityNo, String storeNo) {}
    record Snapshot(String itemNo, String baseUnit, String trackingMode, String status,
                    BigDecimal onHand, BigDecimal reservedQty, long sourceVersion,
                    LocalDateTime sourceUpdatedAt, String targetOwnerRef,
                    String targetLocationRef, String targetItemRef) {}
    record Receipt(String receiptNo, String status, long generation, Long appliedVersion) {}
    record Mapping(String itemNo,String baseUnit,String targetOwnerRef,String targetLocationRef,String targetItemRef) {}
    record MappingResult(String itemNo,boolean valid,String issueCode) {}
    /** Checks existing target identities without activating sessions or writing any projection. */
    List<MappingResult> validateMappings(Scope scope,List<Mapping> mappings);
    Receipt activate(Scope scope, long generation, String operationKey);
    Receipt apply(Scope scope, long generation, String eventNo, String payloadHash, Snapshot snapshot);
    Receipt queryReceipt(Scope scope, String eventNo, String payloadHash);
    Receipt deactivate(Scope scope, long generation, String operationKey);
    List<Snapshot> queryProjection(Scope scope, List<String> itemNos);
}
