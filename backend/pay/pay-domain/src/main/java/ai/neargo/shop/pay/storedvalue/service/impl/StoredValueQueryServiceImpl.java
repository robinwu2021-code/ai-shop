package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAccountScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvUserStoreIndexMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvUserStoreIndex;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.pay.storedvalue.service.StoredValueQueryService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.BalanceView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ConsumptionQuery;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreActivityType;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreItem;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.StoreQuery;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Member-safe read side: visible stores require credited money, and all histories contain committed use only. */
@Service
public class StoredValueQueryServiceImpl implements StoredValueQueryService {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    private final SvAccountMapper accounts;
    private final SvUserStoreIndexMapper stores;
    private final EntUsageMapper usages;
    private final StoredValueCursorCodec cursors;
    private final ObjectMapper json;

    public StoredValueQueryServiceImpl(SvAccountMapper accounts, SvUserStoreIndexMapper stores,
            EntUsageMapper usages, StoredValueCursorCodec cursors, ObjectMapper json) {
        this.accounts = accounts;
        this.stores = stores;
        this.usages = usages;
        this.cursors = cursors;
        this.json = json;
    }

    @Override
    public BalanceView balance(EntitlementContext context, BalanceQuery query) {
        StoredValueAccountScope.requireMigrated(accounts, context.tenantNo(), query.userNo(), query.entityNo(), query.currency());
        var account = accounts.findOwner(context.tenantNo(), query.userNo(), query.entityNo(), query.currency(), query.storeNo());
        if (account == null) return new BalanceView(null, query.currency(), 0, 0, 0, null);
        long balance = Math.addExact(account.getPrincipalMinor(), account.getBonusMinor());
        long held = Math.addExact(account.getHeldPrincipalMinor(), account.getHeldBonusMinor());
        if (held > balance) throw BizException.of(ErrorCode.CONFLICT);
        long available = "ACTIVE".equals(account.getStatus()) ? balance - held : 0;
        return new BalanceView(account.getAccountNo(), account.getCurrency(), balance, held, available, null);
    }

    @Override
    public CursorPage<StoreItem> stores(EntitlementContext context, StoreQuery query) {
        String filters = digest(json.writeValueAsString(List.of("STORED_VALUE_STORES")));
        var cursor = cursors.decode(query.cursor(), "STORES", context.tenantNo(), query.userNo(), filters);
        LocalDateTime afterAt = cursor == null ? null : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cursor.occurredAtMillis()), ZoneOffset.UTC);
        String afterStore = cursor == null ? null : cursor.sortKey();
        var rows = cursor == null
                ? stores.firstDirectoryPage(context.tenantNo(), query.userNo(), query.size() + 1)
                : stores.directoryPage(context.tenantNo(), query.userNo(), afterAt, afterStore, query.size() + 1);
        boolean more = rows.size() > query.size();
        var shown = rows.subList(0, Math.min(rows.size(), query.size()));
        String next = null;
        if (more) {
            var last = shown.getLast();
            next = cursors.encode("STORES", context.tenantNo(), query.userNo(), filters,
                    instant(last.getLastActivityAt()).toEpochMilli(), last.getStoreNo());
        }
        return new CursorPage<>(shown.stream().map(this::storeItem).toList(), next);
    }

    @Override
    public CursorPage<UsageItem> consumptions(EntitlementContext context, ConsumptionQuery query) {
        String filters = digest(json.writeValueAsString(List.of("STORED_VALUE_CONSUMPTIONS",
                query.entityNo(), query.storeNo() == null ? "" : query.storeNo())));
        var cursor = cursors.decode(query.cursor(), "CONSUMPTIONS", context.tenantNo(), query.userNo(), filters);
        LocalDateTime afterAt = cursor == null ? null : LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cursor.occurredAtMillis()), ZoneOffset.UTC);
        String afterUsage = cursor == null ? null : cursor.sortKey();
        var rows = cursor == null
                ? usages.firstStoredValuePage(context.tenantNo(), query.userNo(), query.entityNo(), query.storeNo(),
                        query.size() + 1)
                : usages.storedValuePage(context.tenantNo(), query.userNo(), query.entityNo(), query.storeNo(),
                        afterAt, afterUsage, query.size() + 1);
        boolean more = rows.size() > query.size();
        var shown = rows.subList(0, Math.min(rows.size(), query.size()));
        String next = null;
        if (more) {
            var last = shown.getLast();
            next = cursors.encode("CONSUMPTIONS", context.tenantNo(), query.userNo(), filters,
                    instant(last.getOccurredAt()).toEpochMilli(), last.getUsageNo());
        }
        return new CursorPage<>(shown.stream().map(this::usageItem).toList(), next);
    }

    private StoreItem storeItem(SvUserStoreIndex row) {
        return new StoreItem(row.getStoreNo(), row.getEntityNo(), row.getStoreName(), row.getStoreStatus(),
                StoreActivityType.valueOf(row.getLastActivityType()), instant(row.getLastActivityAt()));
    }

    private UsageItem usageItem(EntUsage row) {
        return new UsageItem(row.getUsageNo(), AssetKind.STORED_VALUE, row.getTitle(), row.getAmountMinor(),
                row.getCurrency(), row.getUnits(), row.getStoreNo(), row.getStoreName(), instant(row.getOccurredAt()),
                row.getStatus(), documentNumbers.find(ai.neargo.shop.numbering.DocumentType.ENTITLEMENT_USAGE, row.getTenantNo(), row.getUsageNo()));
    }
}
