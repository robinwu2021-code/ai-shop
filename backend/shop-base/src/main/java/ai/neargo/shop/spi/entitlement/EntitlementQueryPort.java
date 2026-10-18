package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Payment-owned, scoped consumer projections; no principal/bonus pools, tokens or authorization references. */
public interface EntitlementQueryPort {
    CursorPage<EntitlementItem> mine(EntitlementContext context, AssetQuery query);

    EntitlementDetail detail(EntitlementContext context, DetailQuery query);

    UsageDetail usage(EntitlementContext context, UsageQuery query);

    AllowanceView allowances(EntitlementContext context, AllowanceQuery query);

    enum AssetState { PENDING_ACTIVATION, ACTIVE, FROZEN, EXHAUSTED, EXPIRED, REVOKED }

    record AssetQuery(String userNo, String entityNo, AssetKind kind, AssetState status, String cursor, int size) {
        public AssetQuery {
            ContractChecks.id(userNo);
            ContractChecks.optionalId(entityNo);
            ContractChecks.require(kind != AssetKind.STORED_VALUE);
            checkPage(cursor, size);
        }
    }

    record DetailQuery(String userNo, String entityNo, String instanceNo, String storeNo) {
        public DetailQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(instanceNo);
            ContractChecks.optionalId(storeNo);
        }
    }

    record UsageQuery(String userNo, String entityNo, String usageNo) {
        public UsageQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(usageNo);
        }
    }

    record AllowanceQuery(String userNo, String entityNo, String instanceNo, LocalDate serviceDate) {
        public AllowanceQuery {
            ContractChecks.id(userNo);
            ContractChecks.id(entityNo);
            ContractChecks.id(instanceNo);
        }
    }

    record EntitlementItem(String instanceNo, String title, AssetKind kind, AssetState status,
                           Integer remainingUnits, Integer dailyRemaining, Instant effectiveFrom,
                           Instant effectiveUntil, String scopeSummary, String sourceLabel) {
    }

    record EntitlementDetail(EntitlementItem entitlement, String usageInstructions, List<UsageItem> recentUsage) {
        public EntitlementDetail {
            ContractChecks.present(entitlement);
            recentUsage = List.copyOf(ContractChecks.present(recentUsage));
        }
    }

    record UsageItem(String usageNo, AssetKind assetKind, String title, Long amountMinor, String currency,
                     Integer units, String storeNo, String storeName, Instant occurredAt, String status) {
    }

    record UsageDetail(UsageItem usage, List<UseItem> items, String serviceStaffName, String sourceOrderNo,
                       Integer remainingUnits) {
        public UsageDetail {
            ContractChecks.present(usage);
            items = List.copyOf(ContractChecks.present(items));
        }
    }

    record AllowanceView(String instanceNo, LocalDate serviceDay, String zone, Integer totalRemaining,
                         Integer dayLimit, Integer dayReserved, Integer dayUsed, Integer dayRemaining) {
    }

    /** Opaque cursors are bounded independently of business IDs; implementations verify their signature/scope. */
    static void checkPage(String cursor, int size) {
        ContractChecks.require(size >= 1 && size <= 50);
        ContractChecks.require(cursor == null || (!cursor.isBlank() && cursor.length() <= 512));
    }
}
