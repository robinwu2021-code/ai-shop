package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.SalesQuotaClaimSource;
import ai.neargo.shop.food.dto.SalesQuotaClaimVO;
import ai.neargo.shop.food.entity.FnbSalesQuotaClaim;
import ai.neargo.shop.food.entity.FnbSalesRestriction;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.SalesQuotaClaimMapper;
import ai.neargo.shop.food.mapper.FoodMappers.SalesRestrictionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.policy.MenuDefinitionPolicy;
import ai.neargo.shop.food.policy.SalesRestrictionPolicy;
import ai.neargo.shop.food.service.SalesQuotaClaimService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sales admission only. The caller freezes the batch and these references in this same FOOD transaction.
 * Store-policy locks serialize rule creation, then ordered restriction locks serialize every quota counter.
 */
@Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
public class SalesQuotaClaimServiceImpl implements SalesQuotaClaimService {
    private final SalesRestrictionMapper restrictions;
    private final StorePolicyMapper policies;
    private final SalesQuotaClaimMapper claims;
    private final Clock clock;

    public SalesQuotaClaimServiceImpl(SalesRestrictionMapper restrictions, StorePolicyMapper policies,
                                     SalesQuotaClaimMapper claims, Clock clock) {
        this.restrictions = restrictions;
        this.policies = policies;
        this.claims = claims;
        this.clock = clock;
    }

    @Override
    public List<SalesQuotaClaimVO> reserve(String entity, String store, String batch, String source,
                                           String fulfillment, List<SalesQuotaClaimSource> sources) {
        context(entity, store, batch);
        MenuDefinitionPolicy.key(source);
        MenuDefinitionPolicy.key(fulfillment);
        SalesRestrictionPolicy.scope(source, fulfillment);
        Map<String, BigDecimal> requested = quantities(sources);
        return DataScopeContext.executeWithoutScope(() -> {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            FnbStorePolicy policy = lockStore(entity, store, now);
            List<FnbSalesRestriction> locked = lockRestrictions(entity, store);
            Map<String, FnbSalesRestriction> byNo = locked.stream().collect(Collectors.toMap(
                    FnbSalesRestriction::getRestrictionNo, Function.identity()));
            List<FnbSalesQuotaClaim> old = lockClaims(entity, batch);
            if (!old.isEmpty()) {
                for (FnbSalesQuotaClaim claim : old) {
                    FnbSalesRestriction original = byNo.get(claim.getRestrictionNo());
                    BigDecimal wanted = original == null ? null : requested.get(original.getSkuNo());
                    if (wanted == null || claim.getQuantity() == null || claim.getQuantity().compareTo(wanted) != 0
                            || !List.of("RESERVED", "CONSUMED").contains(claim.getStatus())) throw conflict();
                }
                return views(old);
            }
            LocalDate businessDate = businessDate(policy, now);
            List<FnbSalesRestriction> selected = locked.stream().filter(row -> requested.containsKey(row.getSkuNo())
                    && "ACTIVE".equals(row.getStatus()) && row.getStartsAt() != null
                    && !row.getStartsAt().isAfter(now) && (row.getExpiresAt() == null || row.getExpiresAt().isAfter(now))
                    && (row.getOrderSource() == null || row.getOrderSource().equals(source))
                    && (row.getFulfillment() == null || row.getFulfillment().equals(fulfillment))
                    && (row.getBusinessDate() == null || row.getBusinessDate().equals(businessDate))).toList();
            // Validate every overlapping rule before posting any counter or claim.
            for (FnbSalesRestriction row : selected) {
                if ("SOLD_OUT".equals(row.getMode())) throw conflict();
                if (!"QUOTA".equals(row.getMode())) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                validCounters(row);
                if (row.getQuotaQty() == null || row.getQuotaQty().subtract(row.getReservedQty())
                        .subtract(row.getConsumedQty()).compareTo(requested.get(row.getSkuNo())) < 0) throw conflict();
            }
            List<FnbSalesQuotaClaim> result = new ArrayList<>();
            for (FnbSalesRestriction row : selected) {
                BigDecimal quantity = requested.get(row.getSkuNo());
                updateCounters(row, row.getReservedQty().add(quantity), row.getConsumedQty());
                FnbSalesQuotaClaim claim = new FnbSalesQuotaClaim();
                claim.setTenantNo("MAIN");
                claim.setEntityNo(entity);
                claim.setClaimNo(BizKey.next("FSQC"));
                claim.setRestrictionNo(row.getRestrictionNo());
                claim.setBatchNo(batch);
                claim.setQuantity(quantity);
                claim.setStatus("RESERVED");
                claims.insert(claim);
                result.add(claim);
            }
            return views(result);
        });
    }

    @Override public List<SalesQuotaClaimVO> release(String entity, String store, String batch) {
        return transition(entity, store, batch, "RELEASED");
    }
    @Override public List<SalesQuotaClaimVO> consume(String entity, String store, String batch) {
        return transition(entity, store, batch, "CONSUMED");
    }

    private List<SalesQuotaClaimVO> transition(String entity, String store, String batch, String target) {
        context(entity, store, batch);
        return DataScopeContext.executeWithoutScope(() -> {
            lockStore(entity, store, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            Map<String, FnbSalesRestriction> original = lockRestrictions(entity, store).stream()
                    .collect(Collectors.toMap(FnbSalesRestriction::getRestrictionNo, Function.identity()));
            List<FnbSalesQuotaClaim> rows = lockClaims(entity, batch);
            for (FnbSalesQuotaClaim claim : rows) {
                if (!original.containsKey(claim.getRestrictionNo()) || claim.getQuantity() == null
                        || claim.getQuantity().signum() <= 0 || !(target.equals(claim.getStatus())
                        || "RESERVED".equals(claim.getStatus()))) throw conflict();
            }
            for (FnbSalesQuotaClaim claim : rows) {
                if (target.equals(claim.getStatus())) continue;
                FnbSalesRestriction restriction = original.get(claim.getRestrictionNo());
                validCounters(restriction);
                BigDecimal reserved = restriction.getReservedQty().subtract(claim.getQuantity());
                BigDecimal consumed = restriction.getConsumedQty();
                if ("CONSUMED".equals(target)) consumed = consumed.add(claim.getQuantity());
                updateCounters(restriction, reserved, consumed);
                if (claims.update(null, Wrappers.<FnbSalesQuotaClaim>lambdaUpdate()
                        .eq(FnbSalesQuotaClaim::getTenantNo, "MAIN").eq(FnbSalesQuotaClaim::getEntityNo, entity)
                        .eq(FnbSalesQuotaClaim::getClaimNo, claim.getClaimNo())
                        .eq(FnbSalesQuotaClaim::getStatus, "RESERVED").set(FnbSalesQuotaClaim::getStatus, target)
                        .set(FnbSalesQuotaClaim::getVersion, claim.getVersion() + 1)
                        .set(FnbSalesQuotaClaim::getUpdatedAt, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                        .set(FnbSalesQuotaClaim::getUpdatedBy, "CHECKOUT"))
                        != 1) throw conflict();
                claim.setStatus(target);
            }
            return views(rows);
        });
    }

    private FnbStorePolicy lockStore(String entity, String store, LocalDateTime now) {
        List<FnbStorePolicy> locked = policies.selectList(Wrappers.<FnbStorePolicy>lambdaQuery()
                .eq(FnbStorePolicy::getTenantNo, "MAIN").eq(FnbStorePolicy::getEntityNo, entity)
                .eq(FnbStorePolicy::getStoreNo, store).orderByAsc(FnbStorePolicy::getPolicyNo).last("FOR UPDATE"));
        return locked.stream().filter(row -> row.getEffectiveFrom() != null && !row.getEffectiveFrom().isAfter(now))
                .max(Comparator.comparing(FnbStorePolicy::getEffectiveFrom)).orElseThrow(this::conflict);
    }
    private List<FnbSalesRestriction> lockRestrictions(String entity, String store) {
        return restrictions.selectList(Wrappers.<FnbSalesRestriction>lambdaQuery()
                .eq(FnbSalesRestriction::getTenantNo, "MAIN").eq(FnbSalesRestriction::getEntityNo, entity)
                .eq(FnbSalesRestriction::getStoreNo, store).orderByAsc(FnbSalesRestriction::getRestrictionNo)
                .last("FOR UPDATE"));
    }
    private List<FnbSalesQuotaClaim> lockClaims(String entity, String batch) {
        return claims.selectList(Wrappers.<FnbSalesQuotaClaim>lambdaQuery()
                .eq(FnbSalesQuotaClaim::getTenantNo, "MAIN").eq(FnbSalesQuotaClaim::getEntityNo, entity)
                .eq(FnbSalesQuotaClaim::getBatchNo, batch).orderByAsc(FnbSalesQuotaClaim::getRestrictionNo)
                .last("FOR UPDATE"));
    }
    private LocalDate businessDate(FnbStorePolicy policy, LocalDateTime now) {
        if (policy.getTimezone() == null || !ZoneId.getAvailableZoneIds().contains(policy.getTimezone())
                || policy.getCutoverMinute() == null || policy.getCutoverMinute() < 0
                || policy.getCutoverMinute() > 1439) throw conflict();
        return now.toInstant(ZoneOffset.UTC).atZone(ZoneId.of(policy.getTimezone()))
                .minusMinutes(policy.getCutoverMinute()).toLocalDate();
    }
    private void validCounters(FnbSalesRestriction row) {
        if (!decimal(row.getReservedQty(), false) || !decimal(row.getConsumedQty(), false)
                || row.getQuotaQty() != null && (!decimal(row.getQuotaQty(), true)
                || row.getReservedQty().add(row.getConsumedQty()).compareTo(row.getQuotaQty()) > 0)
                || row.getVersion() == null || row.getVersion() == Long.MAX_VALUE) throw conflict();
    }
    private void updateCounters(FnbSalesRestriction row, BigDecimal reserved, BigDecimal consumed) {
        if (!decimal(reserved, false) || !decimal(consumed, false) || row.getQuotaQty() == null
                || reserved.add(consumed).compareTo(row.getQuotaQty()) > 0) throw conflict();
        if (restrictions.update(null, Wrappers.<FnbSalesRestriction>lambdaUpdate()
                .eq(FnbSalesRestriction::getTenantNo, "MAIN").eq(FnbSalesRestriction::getEntityNo, row.getEntityNo())
                .eq(FnbSalesRestriction::getStoreNo, row.getStoreNo())
                .eq(FnbSalesRestriction::getRestrictionNo, row.getRestrictionNo())
                .eq(FnbSalesRestriction::getVersion, row.getVersion())
                .set(FnbSalesRestriction::getReservedQty, reserved).set(FnbSalesRestriction::getConsumedQty, consumed)
                .set(FnbSalesRestriction::getVersion, row.getVersion() + 1)
                .set(FnbSalesRestriction::getUpdatedAt, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .set(FnbSalesRestriction::getUpdatedBy, "CHECKOUT")) != 1) {
            throw conflict();
        }
        row.setReservedQty(reserved);
        row.setConsumedQty(consumed);
        row.setVersion(row.getVersion() + 1);
    }
    private Map<String, BigDecimal> quantities(List<SalesQuotaClaimSource> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 1024) throw BizException.of(ErrorCode.BAD_REQUEST);
        Map<String, SalesQuotaClaimSource> unique = new LinkedHashMap<>();
        Map<String, BigDecimal> requested = new HashMap<>();
        for (SalesQuotaClaimSource source : sources) {
            if (source == null || !MaterialCatalogFacts.validKey(source.sourceRef(), 128)
                    || !decimal(source.quantity(), true)) throw BizException.of(ErrorCode.BAD_REQUEST);
            MenuDefinitionPolicy.key(source.skuNo());
            SalesQuotaClaimSource existing = unique.putIfAbsent(source.sourceRef(), source);
            if (existing != null) {
                if (!Objects.equals(existing.skuNo(), source.skuNo())
                        || existing.quantity().compareTo(source.quantity()) != 0) throw conflict();
                continue;
            }
            requested.merge(source.skuNo(), source.quantity(), BigDecimal::add);
            if (!decimal(requested.get(source.skuNo()), true)) throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return requested;
    }
    private boolean decimal(BigDecimal value, boolean positive) {
        if (value == null || (positive ? value.signum() <= 0 : value.signum() < 0)) return false;
        BigDecimal exact = value.stripTrailingZeros();
        return exact.scale() <= 6 && exact.precision() - exact.scale() <= 12;
    }
    private void context(String entity, String store, String batch) {
        MenuDefinitionPolicy.key(entity);
        MenuDefinitionPolicy.key(store);
        MenuDefinitionPolicy.key(batch);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Quota claims require the caller's writable FOOD batch transaction");
        }
    }
    private List<SalesQuotaClaimVO> views(List<FnbSalesQuotaClaim> rows) {
        return rows.stream().map(row -> new SalesQuotaClaimVO(row.getClaimNo(), row.getRestrictionNo(),
                row.getBatchNo(), row.getQuantity(), row.getStatus())).toList();
    }
    private BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
