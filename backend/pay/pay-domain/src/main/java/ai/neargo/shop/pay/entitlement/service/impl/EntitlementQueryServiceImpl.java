package ai.neargo.shop.pay.entitlement.service.impl;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.utc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.BizUsageQuery;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.entitlement.entity.EntPeriodBucket;
import ai.neargo.shop.pay.entitlement.entity.EntUsage;
import ai.neargo.shop.pay.entitlement.entity.EntUseAllocation;
import ai.neargo.shop.pay.entitlement.service.EntitlementCursorCodec;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageHistoryQuery;
import ai.neargo.shop.pay.entitlement.service.EntitlementQueryService;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntPeriodBucketMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUsageMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntUseAllocationMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationAction;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.ActivationQuote;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AllowanceView;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.AssetState;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.DetailQuery;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.EntitlementItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageDetail;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageItem;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort.UsageQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** White-listed reads bind tenant and beneficiary; merchant records additionally bind owner and permitted stores. */
@Service
public class EntitlementQueryServiceImpl implements EntitlementQueryService {
    private final EntInstanceMapper instances;
    private final EntPeriodBucketMapper buckets;
    private final EntUsageMapper usages;
    private final EntUseAllocationMapper allocations;
    private final EntLedgerMapper ledger;
    private final EntitlementCursorCodec cursors;
    private final ObjectMapper json;
    private final StoredValueCursorCodec historyCursors;
    public EntitlementQueryServiceImpl(EntInstanceMapper instances, EntPeriodBucketMapper buckets,
            EntUsageMapper usages, EntUseAllocationMapper allocations, EntLedgerMapper ledger,
            EntitlementCursorCodec cursors, ObjectMapper json, StoredValueCursorCodec historyCursors) {
        this.instances = instances;
        this.buckets = buckets;
        this.usages = usages;
        this.allocations = allocations;
        this.ledger = ledger;
        this.cursors = cursors;
        this.json = json;
        this.historyCursors=historyCursors;
    }

    @Override
    public CursorPage<EntitlementItem> mine(EntitlementContext context, AssetQuery q) {
        String filters = digest(json.writeValueAsString(new Filters(q.entityNo(), q.kind(), q.status())));
        long last = cursors.decode(q.cursor(), context.tenantNo(), q.userNo(), filters);
        var condition = Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo, context.tenantNo())
                .eq(EntInstance::getUserNo, q.userNo()).eq(q.entityNo() != null, EntInstance::getEntityNo, q.entityNo())
                .eq(q.kind() != null, EntInstance::getKind, q.kind() == null ? null : q.kind().name())
                .lt(EntInstance::getId, last).orderByDesc(EntInstance::getId);
        filterState(condition, q.status());
        var rows = instances.selectList(condition.last("LIMIT " + (q.size() + 1)));
        boolean more = rows.size() > q.size();
        var shown = rows.subList(0, Math.min(rows.size(), q.size()));
        String next = more ? cursors.encode(context.tenantNo(), q.userNo(), filters, shown.getLast().getId()) : null;
        return new CursorPage<>(shown.stream().map(a -> item(context, a)).toList(), next);
    }

    @Override
    public EntitlementDetail detail(EntitlementContext context, DetailQuery q) {
        var asset = owned(context, q.userNo(), q.entityNo(), q.instanceNo());
        var scope = rule(asset).redemptionScope();
        if (q.storeNo() != null && "STORES".equals(scope.mode()) && !scope.refs().contains(q.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return new EntitlementDetail(item(context, asset), "按有效期及适用范围使用，以门店确认的消费记录为准",
                usages.recent(context.tenantNo(), q.userNo(), asset.getEntityNo(), q.instanceNo(), q.storeNo()).stream()
                        .map(this::usageItem).toList());
    }

    @Override
    public PageData<UsageItem> merchantUsage(EntitlementContext ctx, BizUsageQuery q) {
        if (q.storeNo() != null && q.allowedStores() != null && !q.allowedStores().contains(q.storeNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (q.allowedStores() != null && q.allowedStores().isEmpty()) return PageData.empty(q.page(), q.size());
        var condition = Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo, ctx.tenantNo())
                .eq(EntUsage::getEntityNo, q.entityNo())
                .in(q.allowedStores() != null, EntUsage::getStoreNo, q.allowedStores())
                .eq(q.storeNo() != null, EntUsage::getStoreNo, q.storeNo())
                .eq(q.assetKind() != null, EntUsage::getAssetKind, q.assetKind() == null ? null : q.assetKind().name())
                .ge(q.from() != null, EntUsage::getOccurredAt, q.from() == null ? null : utc(q.from()))
                .lt(q.to() != null, EntUsage::getOccurredAt, q.to() == null ? null : utc(q.to()));
        long total = usages.selectCount(condition);
        var rows = usages.selectList(condition.orderByDesc(EntUsage::getOccurredAt, EntUsage::getUsageNo)
                .last("LIMIT " + q.size() + " OFFSET " + ((q.page() - 1) * q.size())));
        return new PageData<>(rows.stream().map(this::usageItem).toList(), total, q.page(), q.size());
    }

    @Override
    public UsageDetail usage(EntitlementContext context, UsageQuery q) {
        var row = usages.selectOne(Wrappers.<EntUsage>lambdaQuery().eq(EntUsage::getTenantNo, context.tenantNo())
                .eq(EntUsage::getUserNo, q.userNo()).eq(q.entityNo() != null, EntUsage::getEntityNo, q.entityNo())
                .eq(EntUsage::getUsageNo, q.usageNo()));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if ("MIGRATED".equals(row.getProtocol())) {
            return new UsageDetail(usageItem(row), List.of(), null, row.getSourceOrderNo(), null);
        }
        if ("LEGACY".equals(row.getProtocol())) {
            var original = ledger.selectOne(Wrappers.<EntLedger>lambdaQuery()
                    .eq(EntLedger::getTenantNo, context.tenantNo()).eq(EntLedger::getInstanceNo, row.getLegacyInstanceNo())
                    .eq(EntLedger::getBizKey, "LEGACY_COMMIT:" + row.getOperationNo()));
            return new UsageDetail(usageItem(row), List.of(), null, row.getSourceOrderNo(),
                    original == null ? null : original.getAfterUnits());
        }
        var allocation = allocations.selectOne(Wrappers.<EntUseAllocation>lambdaQuery()
                .eq(EntUseAllocation::getTenantNo, context.tenantNo())
                .eq(EntUseAllocation::getOperationNo, row.getOperationNo()).last("LIMIT 1"));
        EntLedger entry = allocation == null ? null : ledger.selectOne(Wrappers.<EntLedger>lambdaQuery()
                .eq(EntLedger::getTenantNo, context.tenantNo()).eq(EntLedger::getInstanceNo, allocation.getInstanceNo())
                .eq(EntLedger::getBizKey, "USE:" + row.getOperationNo()));
        return new UsageDetail(usageItem(row), List.of(json.readValue(row.getItemsSnapshot(), UseItem[].class)),
                row.getServiceStaffName(), row.getSourceOrderNo(), entry == null ? null : entry.getAfterUnits());
    }

    @Override
    public CursorPage<UsageItem> history(EntitlementContext context,UsageHistoryQuery q) {
        String entity=q.entityNo(),instance=q.instanceNo();
        if(instance!=null) entity=owned(context,q.userNo(),entity,instance).getEntityNo();
        String filters=digest(json.writeValueAsString(java.util.Arrays.asList(entity,instance)));
        var cursor=historyCursors.decode(q.cursor(),"RIGHTS_USAGE",context.tenantNo(),q.userNo(),filters);
        var rows=usages.history(context.tenantNo(),q.userNo(),entity,instance,
                cursor==null?null:utc(java.time.Instant.ofEpochMilli(cursor.occurredAtMillis())),
                cursor==null?null:cursor.sortKey(),q.size()+1);
        boolean more=rows.size()>q.size();
        var page=more?rows.subList(0,q.size()):rows;
        var last=page.isEmpty()?null:page.getLast();
        String next=more?historyCursors.encode("RIGHTS_USAGE",context.tenantNo(),q.userNo(),filters,
                instant(last.getOccurredAt()).toEpochMilli(),last.getUsageNo()):null;
        return new CursorPage<>(page.stream().map(this::usageItem).toList(),next);
    }

    @Override
    public AllowanceView allowances(EntitlementContext context, AllowanceQuery q) {
        var asset = owned(context, q.userNo(), q.entityNo(), q.instanceNo());
        var d = rule(asset);
        LocalDate day = q.serviceDate() == null ? LocalDate.now(ZoneId.of(d.validity().zone())) : q.serviceDate();
        var bucket = bucket(context, asset, day);
        boolean daily = "PERIOD_DAILY".equals(d.usageModel());
        int reserved = bucket == null ? 0 : bucket.getReservedUnits();
        int used = bucket == null ? 0 : bucket.getUsedUnits();
        return new AllowanceView(q.instanceNo(), day, d.validity().zone(), asset.getRemainingUnits(),
                daily ? d.dailyUnits() : null, daily ? reserved : null, daily ? used : null,
                daily ? d.dailyUnits() - reserved - used : null);
    }

    @Override
    public ActivationQuote activationQuote(EntitlementContext context, DetailQuery q) {
        var asset = owned(context, q.userNo(), q.entityNo(), q.instanceNo());
        var validity = rule(asset).validity();
        return new ActivationQuote(q.instanceNo(), validity.startBasis(), validity.periodBasis(),
                instant(asset.getEffectiveFrom()), instant(asset.getEffectiveUntil()), asset.getActivationAt() == null
                && "FIRST_USE".equals(validity.startBasis())
                ? ActivationAction.ON_FIRST_COMMIT : ActivationAction.NONE);
    }

    private EntitlementItem item(EntitlementContext context, EntInstance asset) {
        var d = rule(asset);
        Integer dailyRemaining = null;
        if ("PERIOD_DAILY".equals(d.usageModel())) {
            var bucket = bucket(context, asset, LocalDate.now(ZoneId.of(d.validity().zone())));
            dailyRemaining = d.dailyUnits() - (bucket == null ? 0 : bucket.getReservedUnits() + bucket.getUsedUnits());
        }
        return new EntitlementItem(asset.getInstanceNo(), d.title(), AssetKind.valueOf(asset.getKind()), state(asset),
                asset.getRemainingUnits() == null ? null : asset.getRemainingUnits() - asset.getReservedUnits(),
                dailyRemaining, instant(asset.getEffectiveFrom()), instant(asset.getEffectiveUntil()),
                scopeSummary(d), "PAID_CARD".equals(asset.getKind()) ? "购买取得" : "权益发放");
    }
    private String scopeSummary(EntitlementDefinition d) {
        return switch (d.redemptionScope().mode()) {
            case "ALL" -> "该商家适用门店";
            case "STORES" -> "指定门店";
            case "GOODS" -> "指定商品或服务";
            case "CATEGORIES" -> "指定类别";
            default -> throw new IllegalStateException("Invalid stored redemption scope");
        };
    }
    private UsageItem usageItem(EntUsage row) {
        return new UsageItem(row.getUsageNo(), AssetKind.valueOf(row.getAssetKind()), row.getTitle(),
                row.getAmountMinor(), row.getCurrency(), row.getUnits(), row.getStoreNo(), row.getStoreName(),
                instant(row.getOccurredAt()), row.getStatus());
    }
    private EntInstance owned(EntitlementContext context, String user, String entity, String no) {
        var row = instances.selectOne(Wrappers.<EntInstance>lambdaQuery()
                .eq(EntInstance::getTenantNo, context.tenantNo())
                .eq(EntInstance::getUserNo, user).eq(entity != null, EntInstance::getEntityNo, entity)
                .eq(EntInstance::getInstanceNo, no));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private EntPeriodBucket bucket(EntitlementContext context, EntInstance asset, LocalDate day) {
        return buckets.selectOne(Wrappers.<EntPeriodBucket>lambdaQuery()
                .eq(EntPeriodBucket::getTenantNo, context.tenantNo())
                .eq(EntPeriodBucket::getInstanceNo, asset.getInstanceNo())
                .eq(EntPeriodBucket::getPeriodKey, EntitlementUseWriter.PERIOD)
                .eq(EntPeriodBucket::getServiceDay, day));
    }
    private EntitlementDefinition rule(EntInstance asset) {
        return json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
    }
    private AssetState state(EntInstance asset) {
        if ("REVOKED".equals(asset.getStatus())) return AssetState.REVOKED;
        if ("FROZEN".equals(asset.getStatus())) return AssetState.FROZEN;
        if (asset.getRemainingUnits() != null && asset.getRemainingUnits() == 0) return AssetState.EXHAUSTED;
        if (asset.getEffectiveUntil() != null && !Instant.now().isBefore(instant(asset.getEffectiveUntil()))) {
            return AssetState.EXPIRED;
        }
        return AssetState.valueOf(asset.getStatus());
    }
    private void filterState(LambdaQueryWrapper<EntInstance> query, AssetState state) {
        if (state == null) return;
        switch (state) {
            case REVOKED, FROZEN -> query.eq(EntInstance::getStatus, state.name());
            case EXHAUSTED -> query.notIn(EntInstance::getStatus, "REVOKED", "FROZEN")
                    .eq(EntInstance::getRemainingUnits, 0);
            case EXPIRED -> query.notIn(EntInstance::getStatus, "REVOKED", "FROZEN")
                    .and(w -> w.isNull(EntInstance::getRemainingUnits).or().gt(EntInstance::getRemainingUnits, 0))
                    .le(EntInstance::getEffectiveUntil, utc(Instant.now()));
            case ACTIVE -> query.eq(EntInstance::getStatus, "ACTIVE")
                    .and(w -> w.isNull(EntInstance::getRemainingUnits).or().gt(EntInstance::getRemainingUnits, 0))
                    .gt(EntInstance::getEffectiveUntil, utc(Instant.now()));
            case PENDING_ACTIVATION -> query.eq(EntInstance::getStatus, "PENDING_ACTIVATION");
        }
    }
    private record Filters(String entity, AssetKind kind, AssetState status) { }
}
