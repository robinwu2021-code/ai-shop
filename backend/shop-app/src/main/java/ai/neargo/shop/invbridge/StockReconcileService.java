package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import org.springframework.jdbc.core.RowMapper;

/** Durable, idempotent entry point for merchant initiated stock reconciliation. */
@Service
@ConditionalOnInventory
public class StockReconcileService {
    public record OperationResult(String operationNo, String status, StockSyncService.Result result,String documentNo) {
        public OperationResult(String operationNo,String status,StockSyncService.Result result){this(operationNo,status,result,null);}
    }
    public record OperationRecord(String operationNo, String skuNo, String reason, String status,
                                  StockSyncService.Result result, LocalDateTime updatedAt,String documentNo) {
        public OperationRecord(String operationNo,String skuNo,String reason,String status,StockSyncService.Result result,LocalDateTime updatedAt){this(operationNo,skuNo,reason,status,result,updatedAt,null);}
    }

    private final JdbcTemplate jdbc;
    private final DocumentNumberCoordinator numbers;
    private final SyncAction action;
    private final StockSyncService stockSync;
    private final InvManagedService invManaged;
    private final InventoryAclService acl;
    private final MerchantQueryPort merchants;
    private final InventoryDeliveryGuard deliveryGuard;
    private final tools.jackson.databind.ObjectMapper json;

    @FunctionalInterface
    interface SyncAction {
        StockSyncService.Result sync(String entityNo, String storeNo, String skuNo, String sourceRef);
    }

    @Autowired
    public StockReconcileService(DataSource dataSource, StockSyncService stockSync, InvManagedService invManaged,
                                 InventoryAclService acl, MerchantQueryPort merchants,
                                 InventoryDeliveryGuard deliveryGuard,
                                 tools.jackson.databind.ObjectMapper json,
                                 @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.numbers = numbers;
        this.stockSync = stockSync;
        this.invManaged = invManaged;
        this.acl = acl;
        this.merchants = merchants;
        this.deliveryGuard = deliveryGuard;
        this.json = json;
        this.action = this::syncRaw;
    }

    StockReconcileService(JdbcTemplate jdbc, InventoryWritebackService writeback,
                          tools.jackson.databind.ObjectMapper json,
                                 @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.action = (entity, store, sku, sourceRef) -> writeback.syncOne(entity, store, sku, sourceRef);
        this.stockSync = null;
        this.invManaged = null;
        this.acl = null;
        this.merchants = null;
        this.deliveryGuard = null;
        this.json = json;
    }

    public OperationResult reconcile(String entityNo, String storeNo, String skuNo, String reason, String operationNo) {
        requireText(entityNo, "entityNo");
        requireText(storeNo, "storeNo");
        requireText(skuNo, "skuNo");
        requireText(reason, "reason");
        requireText(operationNo, "operationNo");
        if (operationNo.length() > 96 || reason.length() > 32) throw new IllegalArgumentException("invalid operationNo/reason");
        String hash = hash(String.join("\n", entityNo, storeNo, skuNo, reason));
        boolean inserted = claim(operationNo, entityNo, storeNo, skuNo, reason, hash);
        Stored stored = stored(operationNo);
        if (!Objects.equals(hash, stored.requestHash())) throw new IllegalArgumentException("operationNo 已用于其他库存请求");
        if (!inserted && "COMPLETED".equals(stored.status())) {
            return new OperationResult(operationNo, stored.status(), readResult(stored.resultJson()),documentNo(operationNo));
        }
        if (!inserted) jdbc.update("UPDATE prd_stock_sync_operation SET status='PROCESSING', updated_at=? WHERE operation_no=?",
                LocalDateTime.now(), operationNo);

        try {
            StockSyncService.Result result = action.sync(entityNo, storeNo, skuNo, operationNo);
            String status = result == null || (result.skipped() != null && !"DONE_BEFORE".equals(result.skipped()))
                    ? "BLOCKED" : "COMPLETED";
            String resultJson = result == null ? null : writeResult(result);
            jdbc.update("UPDATE prd_stock_sync_operation SET status=?, result_json=?, updated_at=? WHERE operation_no=?",
                    status, resultJson, LocalDateTime.now(), operationNo);
            return new OperationResult(operationNo, status, result,documentNo(operationNo));
        } catch (RuntimeException failure) {
            String status = failure instanceof InventoryWritebackService.MirrorNotCaughtUp ? "WAITING" : "FAILED";
            jdbc.update("UPDATE prd_stock_sync_operation SET status=?, updated_at=? WHERE operation_no=?",
                    status, LocalDateTime.now(), operationNo);
            throw failure;
        }
    }

    /** Shared operation path for existing automatic triggers; sourceRef remains the legacy log idempotency key. */
    public StockSyncService.Result sync(String entityNo, String storeNo, String skuNo, String sourceRef,
                                        boolean requireEnabled, String reason) {
        String operationNo = "AUTO-" + hash(String.join("\n", entityNo, storeNo, skuNo, sourceRef)).substring(0, 60);
        String requestHash = hash(String.join("\n", entityNo, storeNo, skuNo, reason, sourceRef,
                Boolean.toString(requireEnabled)));
        boolean inserted = claim(operationNo, entityNo, storeNo, skuNo, reason, requestHash);
        Stored stored = stored(operationNo);
        if (!Objects.equals(requestHash, stored.requestHash())) throw new IllegalArgumentException("operationNo request mismatch");
        if (!inserted && "COMPLETED".equals(stored.status())) return readResult(stored.resultJson());
        if (!inserted) jdbc.update("UPDATE prd_stock_sync_operation SET status='PROCESSING', updated_at=? WHERE operation_no=?",
                LocalDateTime.now(), operationNo);
        try {
            StockSyncService.Result result = syncRaw(entityNo, storeNo, skuNo, sourceRef, requireEnabled);
            String status = result == null || (result.skipped() != null && !"DONE_BEFORE".equals(result.skipped()))
                    ? "BLOCKED" : "COMPLETED";
            jdbc.update("UPDATE prd_stock_sync_operation SET status=?,result_json=?,updated_at=? WHERE operation_no=?",
                    status, result == null ? null : writeResult(result), LocalDateTime.now(), operationNo);
            return result;
        } catch (RuntimeException failure) {
            String status = failure instanceof InventoryWritebackService.MirrorNotCaughtUp ? "WAITING" : "FAILED";
            jdbc.update("UPDATE prd_stock_sync_operation SET status=?,updated_at=? WHERE operation_no=?",
                    status, LocalDateTime.now(), operationNo);
            throw failure;
        }
    }

    private StockSyncService.Result syncRaw(String entityNo, String storeNo, String skuNo, String sourceRef) {
        return syncRaw(entityNo, storeNo, skuNo, sourceRef, true);
    }

    private StockSyncService.Result syncRaw(String entityNo, String storeNo, String skuNo, String sourceRef,
                                           boolean requireEnabled) {
        if (requireEnabled && !stockSync.enabled(storeNo)) return null;
        if (invManaged.managedSkus(List.of(skuNo)).isEmpty()) return null;
        requireExclusiveSource(entityNo, storeNo);
        if (deliveryGuard.blocked(skuNo)) throw new InventoryWritebackService.MirrorNotCaughtUp(skuNo);
        InventoryAclService.StockAt st = acl.stockAt(entityNo, storeNo, skuNo);
        if (st == null) return null;
        return stockSync.apply(entityNo, storeNo, skuNo, st.available(), sourceRef, "STOCK_SYNC");
    }

    private void requireExclusiveSource(String entityNo, String storeNo) {
        String source = acl.stockLocationOf(entityNo, storeNo);
        if (source == null) return;
        for (String other : merchants.storeNos(entityNo)) {
            if (!Objects.equals(storeNo, other) && Objects.equals(source, acl.stockLocationOf(entityNo, other))) {
                throw BizException.of(ErrorCode.STOCK_SHARED_SOURCE,
                        storeNo, other);
            }
        }
    }

    public OperationResult result(String entityNo, String storeNo, String operationNo) {
        var rows = jdbc.query("SELECT status,result_json FROM prd_stock_sync_operation WHERE operation_no=? AND entity_no=? AND store_no=?",
                (rs, row) -> new OperationResult(operationNo, rs.getString(1), readResult(rs.getString(2))),
                operationNo, entityNo, storeNo);
        if (rows.isEmpty()) throw new IllegalArgumentException("库存操作不存在");
        var row=rows.get(0);
        return new OperationResult(row.operationNo(),row.status(),row.result(),documentNo(operationNo));
    }

    /** Merchant-safe, newest-first operation history; raw outbox payloads are never exposed. */
    public List<OperationRecord> records(String entityNo, String storeNo, String skuNo, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String sql = "SELECT operation_no,sku_no,reason,status,result_json,updated_at FROM prd_stock_sync_operation " +
                "WHERE entity_no=? AND store_no=?" + (skuNo == null || skuNo.isBlank() ? "" : " AND sku_no=?") +
                " ORDER BY updated_at DESC, operation_no DESC LIMIT ? OFFSET ?";
        RowMapper<OperationRecord> mapper = (rs, row) -> new OperationRecord(rs.getString("operation_no"),
                rs.getString("sku_no"), rs.getString("reason"), rs.getString("status"),
                readResult(rs.getString("result_json")), rs.getTimestamp("updated_at").toLocalDateTime());
        var rows = skuNo == null || skuNo.isBlank() ? jdbc.query(sql, mapper, entityNo, storeNo, safeSize, safePage * safeSize)
                : jdbc.query(sql, mapper, entityNo, storeNo, skuNo, safeSize, safePage * safeSize);
        var documents=numbers.findAll(DocumentType.INVENTORY_SYNC,rows.stream().map(r->source(r.operationNo())).toList());
        return rows.stream().map(r->new OperationRecord(r.operationNo(),r.skuNo(),r.reason(),r.status(),r.result(),r.updatedAt(),documents.get(source(r.operationNo())))).toList();
    }

    private boolean claim(String operationNo, String entityNo, String storeNo, String skuNo,
                          String reason, String hash) {
        return numbers.transaction(Map.of(DocumentType.INVENTORY_SYNC,1),()-> {
            try {
                jdbc.update("INSERT INTO prd_stock_sync_operation (operation_no,entity_no,store_no,sku_no,reason,request_hash,status,updated_at) VALUES (?,?,?,?,?,?,'PROCESSING',?)",
                        operationNo, entityNo, storeNo, skuNo, reason, hash, LocalDateTime.now());
                numbers.bind(DocumentType.INVENTORY_SYNC,source(operationNo));
                return true;
            } catch (DuplicateKeyException exists) { return false; }
        });
    }
    private static DocumentSource source(String operationNo) {return new DocumentSource("MAIN","CORE","INVENTORY_SYNC",operationNo);}
    private String documentNo(String operationNo) {return numbers.find(DocumentType.INVENTORY_SYNC,source(operationNo)).orElse(null);}

    private Stored stored(String operationNo) {
        return jdbc.queryForObject("SELECT request_hash,status,result_json FROM prd_stock_sync_operation WHERE operation_no=?",
                (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)), operationNo);
    }

    private String writeResult(StockSyncService.Result result) {
        try { return json.writeValueAsString(result); }
        catch (RuntimeException e) { throw new IllegalStateException("cannot persist stock operation result", e); }
    }

    private StockSyncService.Result readResult(String result) {
        if (result == null) return null;
        try { return json.readValue(result, StockSyncService.Result.class); }
        catch (RuntimeException e) { throw new IllegalStateException("cannot read stock operation result", e); }
    }

    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
    private record Stored(String requestHash, String status, String resultJson) { }
}
