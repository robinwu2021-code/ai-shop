package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.IndustryChannelOrderFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.entity.OrdChannelPolicy;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelPolicyMapper;
import ai.neargo.shop.trade.service.IndustryChannelOrderPolicyService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Policy updates do not release existing reservations or reinterpret confirmed channel receivables.
 */
@Service
@ConditionalOnProperty(prefix = "shop.trade.channel", name = "enabled", havingValue = "true")
public final class IndustryChannelOrderPolicyServiceImpl
        implements IndustryChannelOrderPolicyService {
    private static final long MAX_MINOR = 9_007_199_254_740_991L;
    private final ChannelPolicyMapper policies;
    private final MerchantScopeFactsPort owners;
    private final IdempotencyService idempotency;
    private final TransactionTemplate write;

    public IndustryChannelOrderPolicyServiceImpl(
            ChannelPolicyMapper policies,
            MerchantScopeFactsPort owners,
            IdempotencyService idempotency,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.policies = policies;
        this.owners = owners;
        this.idempotency = idempotency;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /**
     * Lists actual configured policies; absent configuration is not represented as invented funding
     * evidence.
     */
    @Override
    public PageData<Policy> list(Scope scope, int page, int size) {
        scope(scope);
        if (page < 1 || size < 1 || size > 100) throw BizException.of(ErrorCode.BAD_REQUEST);
        long offset = Math.multiplyExact((long) page - 1, size);
        authorize(scope);
        return DataScopeContext.executeWithoutScope(
                () -> {
                    var q =
                            Wrappers.<OrdChannelPolicy>query()
                                    .eq("tenant_no", scope.tenantNo())
                                    .eq("entity_no", scope.entityNo())
                                    .eq("store_no", scope.storeNo());
                    long total = policies.selectCount(q);
                    var rows =
                            policies.selectList(
                                    q.orderByDesc("created_at", "id")
                                            .last("LIMIT " + size + " OFFSET " + offset));
                    return PageData.of(rows.stream().map(this::view).toList(), total, page, size);
                });
    }

    /** A scoped read never turns on a policy or acknowledges a provider payment. */
    @Override
    public Policy get(Scope scope, String channelStoreKey) {
        scope(scope);
        channel(channelStoreKey);
        authorize(scope);
        return DataScopeContext.executeWithoutScope(
                () -> view(requirePolicy(scope, channelStoreKey)));
    }

    /**
     * Completed writes replay before remote owner reads; the original journal still binds actor,
     * scope and payload.
     */
    @Override
    public Policy save(
            Scope scope, String actor, String channelStoreKey, SaveReq request, String key) {
        scope(scope);
        channel(channelStoreKey);
        text(actor, 32);
        text(key, 128);
        validate(request);
        String endpoint =
                "CHANNEL_ORDER_POLICY:"
                        + PricingSnapshotHash.of(
                                List.of(
                                        scope.tenantNo(),
                                        scope.entityNo(),
                                        scope.storeNo(),
                                        channelStoreKey));
        String hash = PricingSnapshotHash.of(request);
        var replay =
                DataScopeContext.executeWithoutScope(
                        () -> idempotency.findCompleted(key, endpoint, actor, hash, Policy.class));
        if (replay.isPresent()) return replay.get();
        authorize(scope);
        return DataScopeContext.executeWithoutScope(
                () ->
                        write.execute(
                                tx ->
                                        idempotency.execute(
                                                key,
                                                endpoint,
                                                actor,
                                                hash,
                                                Policy.class,
                                                () ->
                                                        persist(
                                                                scope,
                                                                actor,
                                                                channelStoreKey,
                                                                request))));
    }

    private Policy persist(Scope scope, String actor, String channelStoreKey, SaveReq request) {
        var row =
                policies.selectOne(
                        Wrappers.<OrdChannelPolicy>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("channel_store_key", channelStoreKey)
                                .last("FOR UPDATE"));
        boolean create = row == null;
        if (create) {
            if (request.version() != 0) throw BizException.of(ErrorCode.CONFLICT);
            row = new OrdChannelPolicy();
            row.setPolicyNo(BizKey.next("OCP"));
            row.setTenantNo(scope.tenantNo());
            row.setEntityNo(scope.entityNo());
            row.setStoreNo(scope.storeNo());
            row.setChannelStoreKey(channelStoreKey);
            row.setReservedMinor(0L);
            row.setVersion(0L);
            row.setDeleted(0);
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            row.setCreatedBy(actor);
        } else {
            if (!request.version().equals(row.getVersion()))
                throw BizException.of(ErrorCode.CONFLICT);
            if (row.getReservedMinor() > 0
                    && !Objects.equals(row.getCurrency(), request.currency()))
                throw BizException.of(ErrorCode.CONFLICT);
        }
        row.setCurrency(request.currency());
        row.setEnabled(request.enabled());
        row.setAllowPlatformCollected(request.allowPlatformCollected());
        row.setAllowCashOnDelivery(request.allowCashOnDelivery());
        row.setMaxSingleMinor(request.maxSingleMinor());
        row.setMaxOutstandingMinor(request.maxOutstandingMinor());
        row.setHoldSeconds(request.holdSeconds());
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        if (create) policies.insert(row);
        else {
            int updated =
                    policies.update(
                            Wrappers.<OrdChannelPolicy>update()
                                    .eq("tenant_no", scope.tenantNo())
                                    .eq("entity_no", scope.entityNo())
                                    .eq("store_no", scope.storeNo())
                                    .eq("id", row.getId())
                                    .eq("version", request.version())
                                    .set("currency", row.getCurrency())
                                    .set("enabled", row.getEnabled())
                                    .set(
                                            "allow_platform_collected",
                                            row.getAllowPlatformCollected())
                                    .set("allow_cash_on_delivery", row.getAllowCashOnDelivery())
                                    .set("max_single_minor", row.getMaxSingleMinor())
                                    .set("max_outstanding_minor", row.getMaxOutstandingMinor())
                                    .set("hold_seconds", row.getHoldSeconds())
                                    .set("updated_by", actor)
                                    .set("updated_at", row.getUpdatedAt())
                                    .set("version", request.version() + 1));
            if (updated != 1) throw BizException.of(ErrorCode.CONFLICT);
            row.setVersion(request.version() + 1);
        }
        return view(row);
    }

    private void validate(SaveReq request) {
        if (request == null
                || request.version() == null
                || request.version() < 0
                || request.currency() == null
                || !request.currency().matches("[A-Z]{3}")
                || request.enabled() == null
                || request.allowPlatformCollected() == null
                || request.allowCashOnDelivery() == null
                || request.enabled()
                        && !request.allowPlatformCollected()
                        && !request.allowCashOnDelivery()
                || request.maxSingleMinor() == null
                || request.maxSingleMinor() < 0
                || request.maxSingleMinor() > MAX_MINOR
                || request.maxOutstandingMinor() == null
                || request.maxOutstandingMinor() < request.maxSingleMinor()
                || request.maxOutstandingMinor() > MAX_MINOR
                || request.holdSeconds() == null
                || request.holdSeconds() < 180
                || request.holdSeconds() > 3600) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private void authorize(Scope scope) {
        if (!owners.entityActive(scope.tenantNo(), scope.entityNo())
                || !owners.storesOwned(scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo())))
            throw BizException.of(ErrorCode.FORBIDDEN);
    }

    private void scope(Scope scope) {
        try {
            IndustryChannelOrderFacts.scope(scope);
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private void channel(String value) {
        if (!IndustryChannelOrderFacts.hash(value)) throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private void text(String value, int max) {
        try {
            IndustryChannelOrderFacts.key(value, max);
        } catch (RuntimeException invalid) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private OrdChannelPolicy requirePolicy(Scope scope, String channelStoreKey) {
        var row =
                policies.selectOne(
                        Wrappers.<OrdChannelPolicy>query()
                                .eq("tenant_no", scope.tenantNo())
                                .eq("entity_no", scope.entityNo())
                                .eq("store_no", scope.storeNo())
                                .eq("channel_store_key", channelStoreKey));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private Policy view(OrdChannelPolicy row) {
        return new Policy(
                row.getPolicyNo(),
                row.getChannelStoreKey(),
                row.getCurrency(),
                Boolean.TRUE.equals(row.getEnabled()),
                Boolean.TRUE.equals(row.getAllowPlatformCollected()),
                Boolean.TRUE.equals(row.getAllowCashOnDelivery()),
                row.getMaxSingleMinor(),
                row.getMaxOutstandingMinor(),
                row.getReservedMinor(),
                row.getHoldSeconds(),
                row.getVersion());
    }
}
