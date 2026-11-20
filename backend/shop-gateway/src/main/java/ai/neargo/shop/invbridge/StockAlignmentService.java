package ai.neargo.shop.invbridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.numbering.DocumentSource;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.Map;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.StockCountService;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.product.entity.PrdSellRule;
import ai.neargo.shop.product.entity.PrdStoreStockSync;
import ai.neargo.shop.product.service.StockSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable preview/confirm workflow for initial inventory alignment. */
@Service
@ConditionalOnInventory
public class StockAlignmentService {
    private static final int PREVIEW_MINUTES = 15;
    private final JdbcTemplate jdbc;
    private final DocumentNumberCoordinator numbers;
    private final StockSyncAppService syncApp;
    private final StockSyncService stockSync;
    private final InventoryAclService acl;
    private final LocationService locations;
    private final StockCountService counts;
    private final StockReconcileService reconcile;
    private final tools.jackson.databind.ObjectMapper json;

    public StockAlignmentService(DataSource dataSource, StockSyncAppService syncApp, StockSyncService stockSync,
                                 InventoryAclService acl, LocationService locations, StockCountService counts,
                                 StockReconcileService reconcile, tools.jackson.databind.ObjectMapper json,
                                 @Qualifier("coreDocumentNumberCoordinator") DocumentNumberCoordinator numbers) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.numbers = numbers;
        this.syncApp = syncApp;
        this.stockSync = stockSync;
        this.acl = acl;
        this.locations = locations;
        this.counts = counts;
        this.reconcile = reconcile;
        this.json = json;
    }

    public record LineView(String skuNo, String title, String spec, Integer beforeQty, Integer reserved,
                           int mallStock, int mallLocked, Integer afterQty, String status,
                           String operationNo, String errorCode) { }

    public record BatchView(String batchNo, String storeNo, String mode, String snapshotVersion,
                            Long expiresAt, String status, int completed, int blocked, int waiting,
                            List<LineView> rows,String documentNo) {
        public BatchView(String batchNo,String storeNo,String mode,String snapshotVersion,Long expiresAt,String status,
                int completed,int blocked,int waiting,List<LineView> rows){this(batchNo,storeNo,mode,snapshotVersion,expiresAt,status,completed,blocked,waiting,rows,null);}
    }

    private record SnapshotLine(StockSyncAppService.AlignRow row, long inventoryVersion, int safety,
                                long mallVersion) { }
    private record RuleSnapshot(String scope, String ref, String type, int param, long version) { }
    private record ContextSnapshot(String sourceLocation, List<RuleSnapshot> rules) { }
    private record Batch(String batchNo, String entityNo, String storeNo, String mode, String snapshot,
                         String context, LocalDateTime expires, String status, String operationNo, String operator) { }
    private record StoredLine(long id, int lineNo, String goodsNo, String skuNo, String itemId,
                              String title, String spec, Integer beforeQty, Integer reserved, Integer safety,
                              long inventoryVersion, int mallStock, int mallLocked, long mallVersion,
                              Integer afterQty, String note, String status, String lineOperationNo,
                              String syncOperationNo, String errorCode) { }

    public BatchView preview(String entityNo, String storeNo, String mode, String operator) {
        if (!PrdStoreStockSync.ALIGN_MALL.equals(mode) && !PrdStoreStockSync.ALIGN_COUNT.equals(mode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (PrdStoreStockSync.ALIGN_MALL.equals(mode)
                && !"NOT_ALIGNED".equals(syncApp.state(storeNo).state())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        List<StockSyncAppService.AlignRow> rows = syncApp.alignment(entityNo, storeNo).stream()
                .sorted(Comparator.comparing(StockSyncAppService.AlignRow::skuNo)).toList();
        String snapshot = snapshot(entityNo, storeNo, rows);
        String context = context(entityNo, storeNo);
        String batchNo = "AL" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusMinutes(PREVIEW_MINUTES);
        List<Object[]> preparedLines = new ArrayList<>();
        int lineNo = 0;
        for (var row : rows) {
            InventoryAclService.StockAt physical = acl.stockAt(entityNo, storeNo, row.skuNo());
            StockSyncService.Sellable mall = stockSync.sellableOf(storeNo, row.skuNo());
            int after = PrdStoreStockSync.ALIGN_MALL.equals(mode) ? row.mallStock() : (row.onHand() == null ? 0 : row.onHand());
            String lineStatus = row.note() == null && physical != null ? "PENDING" : "BLOCKED";
            String opNo = lineOperation(batchNo, row.skuNo());
            preparedLines.add(new Object[]{batchNo, ++lineNo, row.goodsNo(), row.skuNo(), acl.itemIdOfSku(row.skuNo()), row.title(), row.spec(),
                    row.onHand(), row.reserved(), physical == null ? null : physical.safety(), physical == null ? 0 : physical.version(),
                    row.mallStock(), row.mallLocked(), mall.version(), after, row.note(), lineStatus, opNo,
                    "SYNC-" + hash(batchNo + "\n" + row.skuNo()).substring(0, 60), now, now});
        }
        return numbers.transaction(Map.of(DocumentType.INVENTORY_ALIGNMENT,1),()-> {
            jdbc.update("INSERT INTO prd_stock_alignment_batch(batch_no,entity_no,store_no,mode,snapshot_version,context_version,expires_at,status,operator,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PREVIEW',?,?,?)",
                batchNo, entityNo, storeNo, mode, snapshot, context, expires, operator, now, now);
            for(Object[] values:preparedLines)jdbc.update("INSERT INTO prd_stock_alignment_line(batch_no,line_no,goods_no,sku_no,item_id,title,spec,before_qty,reserved_qty,safety_qty,inventory_version,mall_stock,mall_locked,mall_version,after_qty,note,status,line_operation_no,sync_operation_no,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",values);
            numbers.bind(DocumentType.INVENTORY_ALIGNMENT,new DocumentSource("MAIN","CORE","INVENTORY_ALIGNMENT",batchNo));
            return view(batch(batchNo,entityNo,storeNo));
        });
    }

    public BatchView confirm(String entityNo, String storeNo, String batchNo, String operationNo,
                             String snapshotVersion, String operator) {
        Batch batch = batch(batchNo, entityNo, storeNo);
        if (batch == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if ("COMPLETED".equals(batch.status()) || "STALE".equals(batch.status()) || "EXPIRED".equals(batch.status())) {
            return view(batch);
        }
        if (operationNo == null || operationNo.isBlank() || operationNo.length() > 96
                || !Objects.equals(batch.snapshot(), snapshotVersion)) throw BizException.of(ErrorCode.CONFLICT);
        if (batch.operationNo() != null && !batch.operationNo().equals(operationNo)) throw BizException.of(ErrorCode.CONFLICT);
        if (batch.operationNo() == null) {
            List<String> used = jdbc.query("SELECT batch_no FROM prd_stock_alignment_batch WHERE operation_no=?",
                    (rs, row) -> rs.getString(1), operationNo);
            if (used.stream().anyMatch(otherBatch -> !batchNo.equals(otherBatch))) throw BizException.of(ErrorCode.CONFLICT);
        }
        if ("PREVIEW".equals(batch.status())) {
            if (batch.expires().isBefore(LocalDateTime.now())) {
                updateBatch(batchNo, "EXPIRED", operationNo);
                return view(batch(batchNo, entityNo, storeNo));
            }
            if (!Objects.equals(batch.context(), context(entityNo, storeNo))
                    || !Objects.equals(batch.snapshot(), snapshot(entityNo, storeNo,
                    syncApp.alignment(entityNo, storeNo).stream().sorted(Comparator.comparing(StockSyncAppService.AlignRow::skuNo)).toList()))) {
                updateBatch(batchNo, "STALE", operationNo);
                return view(batch(batchNo, entityNo, storeNo));
            }
        } else if (!List.of("PROCESSING", "PARTIAL", "BLOCKED").contains(batch.status())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        updateBatch(batchNo, "PROCESSING", operationNo);
        String owner = acl.ownerIdOf(entityNo);
        String location = acl.stockLocationOf(entityNo, storeNo);
        if (PrdStoreStockSync.ALIGN_MALL.equals(batch.mode())) {
            location = locations.resolveStockLocation(owner, acl.locationIdOf(entityNo, storeNo));
        }
        for (StoredLine line : lines(batchNo)) {
            if ("COMPLETED".equals(line.status()) || "SKIPPED".equals(line.status())) continue;
            if ("BLOCKED".equals(line.status())) continue;
            if ("PENDING".equals(line.status()) && !lineStillMatches(entityNo, storeNo, line)) {
                updateLine(line.id(), "BLOCKED", "SNAPSHOT_CHANGED", null);
                continue;
            }
            updateLine(line.id(), "PROCESSING", null, null);
            try {
                if (PrdStoreStockSync.ALIGN_MALL.equals(batch.mode()) && !Objects.equals(line.beforeQty(), line.afterQty())) {
                    if (location == null || line.itemId() == null) throw new IllegalStateException("ALIGNMENT_SOURCE_MISSING");
                    counts.adjustOneIdempotent(owner, location, line.itemId(), line.afterQty(),
                            InvEnums.Reason.CHECK, operator, line.lineOperationNo());
                }
                StockSyncService.Result synced = reconcile.sync(entityNo, storeNo, line.skuNo(),
                        "ALIGN:" + line.syncOperationNo(), false, "ALIGNMENT");
                if (synced == null || (synced.skipped() != null && !"DONE_BEFORE".equals(synced.skipped()))) {
                    updateLine(line.id(), "BLOCKED", synced == null ? "STOCK_UNAVAILABLE" : synced.skipped(), null);
                } else {
                    updateLine(line.id(), "COMPLETED", null, writeResult(synced));
                }
            } catch (RuntimeException failure) {
                String status = failure instanceof InventoryWritebackService.MirrorNotCaughtUp ? "WAITING" : "FAILED";
                updateLine(line.id(), status, failureCode(failure), null);
            }
        }
        List<StoredLine> finished = lines(batchNo);
        boolean allDone = finished.stream().allMatch(line -> "COMPLETED".equals(line.status()));
        boolean anyWaiting = finished.stream().anyMatch(line -> "WAITING".equals(line.status()) || "PROCESSING".equals(line.status()));
        boolean anyFailure = finished.stream().anyMatch(line -> "FAILED".equals(line.status()));
        String finalStatus = allDone ? "COMPLETED" : anyWaiting || anyFailure ? "PARTIAL" : "BLOCKED";
        updateBatch(batchNo, finalStatus, operationNo);
        if (allDone) {
            stockSync.markAligned(entityNo, storeNo, batch.mode(), operator);
        }
        return view(batch(batchNo, entityNo, storeNo));
    }

    public BatchView result(String entityNo, String storeNo, String batchNo) {
        Batch batch = batch(batchNo, entityNo, storeNo);
        if (batch == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return view(batch);
    }

    private boolean lineStillMatches(String entityNo, String storeNo, StoredLine line) {
        InventoryAclService.StockAt stock = acl.stockAt(entityNo, storeNo, line.skuNo());
        StockSyncService.Sellable mall = stockSync.sellableOf(storeNo, line.skuNo());
        return stock != null && stock.onHand() == line.beforeQty() && stock.reserved() == line.reserved()
                && stock.safety() == line.safety() && stock.version() == line.inventoryVersion()
                && mall.sellable() + mall.locked() == line.mallStock() && mall.locked() == line.mallLocked()
                && mall.version() == line.mallVersion();
    }

    private String snapshot(String entityNo, String storeNo, List<StockSyncAppService.AlignRow> rows) {
        List<SnapshotLine> values = new ArrayList<>();
        for (var row : rows) {
            var stock = acl.stockAt(entityNo, storeNo, row.skuNo());
            var mall = stockSync.sellableOf(storeNo, row.skuNo());
            values.add(new SnapshotLine(row, stock == null ? 0 : stock.version(), stock == null ? 0 : stock.safety(), mall.version()));
        }
        return hash(writeJson(List.of(context(entityNo, storeNo), values)));
    }

    private String context(String entityNo, String storeNo) {
        List<RuleSnapshot> rules = stockSync.rules(storeNo).stream()
                .sorted(Comparator.comparing(PrdSellRule::getScopeType).thenComparing(PrdSellRule::getScopeRef))
                .map(r -> new RuleSnapshot(r.getScopeType(), r.getScopeRef(), r.getRuleType(),
                        r.getParam() == null ? 0 : r.getParam(), r.getVersion() == null ? 0 : r.getVersion())).toList();
        return hash(writeJson(new ContextSnapshot(acl.stockLocationOf(entityNo, storeNo), rules)));
    }

    private Batch batch(String batchNo, String entityNo, String storeNo) {
        var rows = jdbc.query("SELECT batch_no,entity_no,store_no,mode,snapshot_version,context_version,expires_at,status,operation_no,operator FROM prd_stock_alignment_batch WHERE batch_no=? AND entity_no=? AND store_no=?",
                (rs, i) -> new Batch(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toLocalDateTime(), rs.getString(8), rs.getString(9), rs.getString(10)),
                batchNo, entityNo, storeNo);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<StoredLine> lines(String batchNo) {
        return jdbc.query("SELECT * FROM prd_stock_alignment_line WHERE batch_no=? ORDER BY line_no", (rs, i) ->
                new StoredLine(rs.getLong("id"), rs.getInt("line_no"), rs.getString("goods_no"), rs.getString("sku_no"),
                        rs.getString("item_id"), rs.getString("title"), rs.getString("spec"), integer(rs, "before_qty"),
                        integer(rs, "reserved_qty"), integer(rs, "safety_qty"), rs.getLong("inventory_version"),
                        rs.getInt("mall_stock"), rs.getInt("mall_locked"), rs.getLong("mall_version"),
                        integer(rs, "after_qty"), rs.getString("note"), rs.getString("status"),
                        rs.getString("line_operation_no"), rs.getString("sync_operation_no"), rs.getString("error_code")), batchNo);
    }

    private BatchView view(Batch batch) {
        List<StoredLine> stored = lines(batch.batchNo());
        List<LineView> rows = stored.stream().map(l -> new LineView(l.skuNo(), l.title(), l.spec(), l.beforeQty(),
                l.reserved(), l.mallStock(), l.mallLocked(), l.afterQty(), l.status(), l.lineOperationNo(), l.errorCode())).toList();
        return new BatchView(batch.batchNo(), batch.storeNo(), batch.mode(), batch.snapshot(),
                batch.expires().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), batch.status(),
                (int) stored.stream().filter(l -> "COMPLETED".equals(l.status())).count(),
                (int) stored.stream().filter(l -> "BLOCKED".equals(l.status())).count(),
                (int) stored.stream().filter(l -> "WAITING".equals(l.status()) || "FAILED".equals(l.status())).count(), rows,numbers.find(DocumentType.INVENTORY_ALIGNMENT,
                    new DocumentSource("MAIN","CORE","INVENTORY_ALIGNMENT",batch.batchNo())).orElse(null));
    }

    private void updateBatch(String batchNo, String status, String operationNo) {
        try {
            jdbc.update("UPDATE prd_stock_alignment_batch SET status=?,operation_no=COALESCE(operation_no,?),updated_at=? WHERE batch_no=?",
                    status, operationNo, LocalDateTime.now(), batchNo);
        } catch (DuplicateKeyException duplicateOperation) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private void updateLine(long id, String status, String error, String result) {
        jdbc.update("UPDATE prd_stock_alignment_line SET status=?,error_code=?,result_json=?,updated_at=? WHERE id=?",
                status, error, result, LocalDateTime.now(), id);
    }

    private String lineOperation(String batchNo, String skuNo) { return "ALIGN-" + hash(batchNo + "\n" + skuNo).substring(0, 60); }
    private String writeResult(StockSyncService.Result value) { return writeJson(value); }
    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (RuntimeException e) { throw new IllegalStateException("cannot serialize alignment snapshot", e); }
    }
    private static Integer integer(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static String failureCode(RuntimeException failure) {
        if (failure instanceof IllegalStateException && failure.getMessage() != null) return failure.getMessage();
        if (failure instanceof BizException biz) return biz.errorCode().name();
        return failure.getClass().getSimpleName();
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
