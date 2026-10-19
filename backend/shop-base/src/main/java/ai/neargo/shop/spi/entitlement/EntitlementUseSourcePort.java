package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.Instant;

/** Authoritative core order/service facts, obtained before pay locks; a supplied hash is not proof. */
public interface EntitlementUseSourcePort {
    UseSourceFact resolve(EntitlementContext context, SourceRef source);

    record UseSourceFact(String tenantNo, SourceRef source, String userNo, String entityNo, String storeNo,
                         String currency, List<UseItem> items, String orderSnapshotHash,
                         String storeName, String serviceStaffName, LocalDate serviceDay, long freightMinor,
                         Map<String, List<String>> categoryRefsByLine, Instant serviceAt,
                         String stagedOperationNo) {
        /** Compatibility for ordinary source owners: only SERVICE_USE currently requires an explicit staged operation. */
        public UseSourceFact(String tenantNo, SourceRef source, String userNo, String entityNo, String storeNo,
                String currency, List<UseItem> items, String orderSnapshotHash, String storeName,
                String serviceStaffName, LocalDate serviceDay, long freightMinor,
                Map<String, List<String>> categoryRefsByLine, Instant serviceAt) {
            this(tenantNo, source, userNo, entityNo, storeNo, currency, items, orderSnapshotHash, storeName,
                    serviceStaffName, serviceDay, freightMinor, categoryRefsByLine, serviceAt, null);
        }
        public UseSourceFact(String tenantNo, SourceRef source, String userNo, String entityNo, String storeNo,
                String currency, List<UseItem> items, String orderSnapshotHash, String storeName,
                String serviceStaffName, LocalDate serviceDay) {
            this(tenantNo, source, userNo, entityNo, storeNo, currency, items, orderSnapshotHash,
                    storeName, serviceStaffName, serviceDay, 0, Map.of(), null);
        }
        public UseSourceFact(String tenantNo, SourceRef source, String userNo, String entityNo, String storeNo,
                String currency, List<UseItem> items, String orderSnapshotHash, String storeName,
                String serviceStaffName, LocalDate serviceDay, long freightMinor,
                Map<String, List<String>> categoryRefsByLine) {
            this(tenantNo, source, userNo, entityNo, storeNo, currency, items, orderSnapshotHash,
                    storeName, serviceStaffName, serviceDay, freightMinor, categoryRefsByLine, null);
        }
        public UseSourceFact {
            ContractChecks.id(tenantNo);
            ContractChecks.optionalId(stagedOperationNo);
            ContractChecks.present(source);
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            ContractChecks.currency(currency);
            items = List.copyOf(ContractChecks.present(items));
            ContractChecks.require(!items.isEmpty());
            ContractChecks.hash(orderSnapshotHash);
            ContractChecks.require(freightMinor >= 0);
            categoryRefsByLine = Map.copyOf(ContractChecks.present(categoryRefsByLine).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue()))));
            ContractChecks.require(storeName != null && !storeName.isBlank() && storeName.length() <= 100);
        }
    }
}
