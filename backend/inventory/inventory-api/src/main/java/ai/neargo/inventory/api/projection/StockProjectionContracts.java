package ai.neargo.inventory.api.projection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Source mirror wire contracts; source identity and snapshot order preserve historical hashes. */
public final class StockProjectionContracts {
    private StockProjectionContracts() {}
    public record Scope(String entityNo, String storeNo) {}
    public record Snapshot(String itemNo, String baseUnit, String trackingMode, String status,
                    BigDecimal onHand, BigDecimal reservedQty, long sourceVersion,
                    LocalDateTime sourceUpdatedAt, String targetOwnerRef,
                    String targetLocationRef, String targetItemRef) {}
    public record Receipt(String receiptNo, String status, long generation, Long appliedVersion) {}
    public record Mapping(String itemNo,String baseUnit,String targetOwnerRef,String targetLocationRef,String targetItemRef) {}
    public record MappingResult(String itemNo,boolean valid,String issueCode) {}

    public interface Request { Scope scope(); }
    public record MappingQuery(Scope scope,List<Mapping> mappings) implements Request {}
    public record SessionCommand(Scope scope,long generation,String operationKey) implements Request {}
    public record Apply(Scope scope,long generation,String eventNo,String payloadHash,Snapshot snapshot) implements Request {}
    public record Query(Scope scope,String eventNo,String payloadHash) implements Request {}
    public record Items(Scope scope,List<String> itemNos) implements Request {}
    public record ReceiptLookup(Receipt receipt) {}
    public record ProjectionView(String sourceSystem,String sourceEntityNo,String sourceStoreNo,
                                 long generation,String syncStatus,LocalDateTime receivedAt,Snapshot stock) {}
    public record ProjectionPage(List<ProjectionView> rows,long total,int page,int size) {}
}
