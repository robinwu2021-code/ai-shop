package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvRefundRestore;
import ai.neargo.shop.inventory.entity.InvReturnReceipt;
import ai.neargo.shop.inventory.mapper.RefundRestoreMapper;
import ai.neargo.shop.inventory.mapper.ReturnReceiptMapper;
import ai.neargo.shop.inventory.service.InboundService;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.service.ReturnReceiptService;
import ai.neargo.shop.inventory.service.impl.OriginalReservationSource.Source;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Posts accepted physical returns by quality without waiting for or mutating cash-refund execution. */
@Service
@ConditionalOnInventory
public class ReturnReceiptServiceImpl implements ReturnReceiptService {
    private final OriginalReservationSource source;
    private final RefundRestoreMapper restoreClaims;
    private final ReturnReceiptMapper receipts;
    private final LocationService locations;
    private final InboundService inbound;

    public ReturnReceiptServiceImpl(OriginalReservationSource source, RefundRestoreMapper restoreClaims,
                                    ReturnReceiptMapper receipts, LocationService locations,
                                    InboundService inbound) {
        this.source = source;
        this.restoreClaims = restoreClaims;
        this.receipts = receipts;
        this.locations = locations;
        this.inbound = inbound;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", readOnly = true)
    public String status(String receiptNo) {
        return receipts.selectCount(Wrappers.<InvReturnReceipt>lambdaQuery()
                .eq(InvReturnReceipt::getReceiptNo, receiptNo)) > 0 ? "COMPLETED" : "PENDING";
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager", isolation = Isolation.READ_COMMITTED)
    public void receive(String receiptNo, String afterSaleNo, String originalOrderNo, String fulfillmentRef,
                        List<Line> lines, String operator) {
        Map<Key, Integer> requested = normalize(lines);
        String requestHash = hash(receiptNo, afterSaleNo, originalOrderNo, fulfillmentRef, requested);
        List<InvReturnReceipt> replay = receipts.selectList(Wrappers.<InvReturnReceipt>lambdaQuery()
                .eq(InvReturnReceipt::getReceiptNo, receiptNo).orderByAsc(InvReturnReceipt::getLineNo));
        if (!replay.isEmpty()) {
            if (replay.stream().anyMatch(row -> !requestHash.equals(row.getRequestHash()))) throw conflict();
            return;
        }

        List<Source> candidates = source.lock(originalOrderNo).stream()
                .filter(row -> fulfillmentRef.equals(row.line().getFulfillmentRef()))
                .sorted(Comparator.comparing(row -> row.line().getId())).toList();
        if (candidates.isEmpty() || candidates.stream().anyMatch(row -> row.line().getIssuedQty() == null
                || row.line().getIssuedQty() <= 0)) throw conflict();

        List<Long> ids = candidates.stream().map(row -> row.line().getId()).toList();
        Map<Long, Integer> alreadyClaimed = new LinkedHashMap<>();
        restoreClaims.selectList(Wrappers.<InvRefundRestore>lambdaQuery()
                .in(InvRefundRestore::getOriginalLineId, ids))
                .forEach(row -> alreadyClaimed.merge(row.getOriginalLineId(), row.getQty(), Math::addExact));
        receipts.selectList(Wrappers.<InvReturnReceipt>lambdaQuery()
                .in(InvReturnReceipt::getOriginalLineId, ids))
                .forEach(row -> alreadyClaimed.merge(row.getOriginalLineId(), row.getQty(), Math::addExact));

        int lineNo = 1;
        for (var request : requested.entrySet()) {
            int remaining = request.getValue();
            for (Source candidate : candidates) {
                if (!request.getKey().itemId().equals(candidate.line().getItemId())) continue;
                long originalLineId = candidate.line().getId();
                int available = candidate.restorableQty() - alreadyClaimed.getOrDefault(originalLineId, 0);
                if (available <= 0) continue;
                int accepted = Math.min(remaining, available);
                String destination = request.getKey().quality() == Quality.SALEABLE
                        ? candidate.line().getLocationId()
                        : locations.quarantineLocation(candidate.line().getOwnerId());
                String inboundNo = inbound.postDirectly(new InboundService.Draft(candidate.line().getOwnerId(),
                                destination, "RETURN", receiptNo, null, null, LocalDateTime.now(),
                                "Return receipt " + afterSaleNo + " / " + request.getKey().quality(),
                                List.of(new InboundService.Line(candidate.line().getItemId(), accepted, null,
                                        candidate.unitCostMinor()))), operator);
                InvReturnReceipt row = new InvReturnReceipt();
                row.setReceiptNo(receiptNo);
                row.setLineNo(lineNo++);
                row.setAfterSaleNo(afterSaleNo);
                row.setOriginalOrderNo(originalOrderNo);
                row.setFulfillmentRef(fulfillmentRef);
                row.setRequestHash(requestHash);
                row.setOriginalLineId(originalLineId);
                row.setOwnerId(candidate.line().getOwnerId());
                row.setItemId(candidate.line().getItemId());
                row.setQty(accepted);
                row.setQuality(request.getKey().quality().name());
                row.setLocationId(destination);
                row.setInboundNo(inboundNo);
                row.setCreatedBy(operator);
                receipts.insert(row);
                alreadyClaimed.merge(originalLineId, accepted, Math::addExact);
                remaining -= accepted;
                if (remaining == 0) break;
            }
            if (remaining != 0) throw conflict();
        }
    }

    private static Map<Key, Integer> normalize(List<Line> lines) {
        if (lines == null || lines.isEmpty()) throw conflict();
        Map<Key, Integer> requested = new TreeMap<>(Comparator.comparing(Key::itemId)
                .thenComparing(key -> key.quality().name()));
        for (Line line : lines) {
            if (line == null || line.itemId() == null || line.itemId().isBlank() || line.qty() <= 0
                    || line.quality() == null) throw conflict();
            requested.merge(new Key(line.itemId(), line.quality()), line.qty(), Math::addExact);
        }
        return requested;
    }

    private static String hash(String receiptNo, String afterSaleNo, String originalOrderNo,
                               String fulfillmentRef, Map<Key, Integer> requested) {
        if (blank(receiptNo) || blank(afterSaleNo) || blank(originalOrderNo) || blank(fulfillmentRef)) throw conflict();
        StringBuilder value = new StringBuilder(String.join("\n", receiptNo, afterSaleNo, originalOrderNo, fulfillmentRef));
        requested.forEach((key, qty) -> value.append('\n').append(key.itemId()).append('|')
                .append(key.quality().name()).append('|').append(qty));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static IllegalStateException conflict() {
        return new IllegalStateException("RETURN_RECEIPT_QUANTITY_OR_IDEMPOTENCY_CONFLICT");
    }

    private record Key(String itemId, Quality quality) { }
}
