package ai.neargo.shop.pay.entitlement.service;

import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.audit;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.changed;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.conflict;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.digest;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.instant;
import static ai.neargo.shop.pay.entitlement.service.EntitlementRecords.now;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.pay.entitlement.entity.EntCodeToken;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.mapper.entitlement.EntCodeTokenMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Credential writes only; these transactions never mutate asset counters, activation or financial ledgers. */
@Component
public class EntitlementCodeWriter {
    private final EntCodeTokenMapper tokens;
    private final EntInstanceMapper instances;
    private final PayEntitlementFeatureGate features;
    private final EntitlementCodeSigner signer;
    private final ObjectMapper json;
    public EntitlementCodeWriter(EntCodeTokenMapper tokens, EntInstanceMapper instances,
            PayEntitlementFeatureGate features, EntitlementCodeSigner signer, ObjectMapper json) {
        this.tokens = tokens; this.instances = instances; this.features = features;
        this.signer = signer; this.json = json;
    }
    @Transactional("payTxManager")
    public CodeView issue(EntitlementContext ctx, String user, String instance, String keyHash, String payloadHash) {
        var asset = owned(ctx, user, instance, true);
        var prior = unscoped(() -> tokens.findRequest(ctx.tenantNo(), user, keyHash));
        if (prior != null) {
            if (!prior.getPayloadHash().equals(payloadHash)) conflict();
            return view(prior);
        }
        features.requireCode(); var until = usable(asset);
        var row = new EntCodeToken(); audit(row, ctx);
        row.setInstanceNo(instance); row.setUserNo(user); row.setEntityNo(asset.getEntityNo());
        row.setRequestKeyHash(keyHash); row.setPayloadHash(payloadHash);
        row.setNonce(UUID.randomUUID().toString().replace("-", "")); row.setStatus("ACTIVE");
        var expires = row.getCreatedAt().plusSeconds(60);
        row.setExpiresAt(until != null && until.isBefore(expires) ? until : expires);
        row.setTokenHash(digest(signer.token(row))); changed(unscoped(() -> tokens.insert(row)));
        return view(row);
    }
    @Transactional("payTxManager")
    public ResolvedCode resolve(EntitlementContext ctx, String entity, String store, String hash, String operation) {
        var row = unscoped(() -> tokens.lock(ctx.tenantNo(), hash));
        if (row == null || !entity.equals(row.getEntityNo()) || !now().isBefore(row.getExpiresAt())
                || !List.of("ACTIVE", "USED").contains(row.getStatus())) notFound();
        var asset = owned(ctx, row.getUserNo(), row.getInstanceNo(), false);
        if (!entity.equals(asset.getEntityNo())) notFound();
        usable(asset);
        var definition = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
        if ("STORES".equals(definition.redemptionScope().mode())
                && !definition.redemptionScope().refs().contains(store)) notFound();
        if ("USED".equals(row.getStatus())) {
            if (!operation.equals(row.getConsumedOperationNo()) || !store.equals(row.getConsumedStoreNo())
                    || !ctx.actorNo().equals(row.getConsumedActorNo())) conflict();
        } else {
            row.setStatus("USED"); row.setConsumedOperationNo(operation); row.setConsumedStoreNo(store);
            row.setConsumedActorNo(ctx.actorNo()); row.setConsumedAt(now()); row.setUpdatedAt(now());
            row.setUpdatedBy(ctx.actorNo()); changed(unscoped(() -> tokens.updateById(row)));
        }
        return new ResolvedCode(row.getUserNo(), row.getInstanceNo(), instant(row.getExpiresAt()));
    }
    @Transactional("payTxManager")
    public int invalidate(EntitlementContext ctx, String user, String instance) {
        owned(ctx, user, instance, true);
        return unscoped(() -> tokens.update(Wrappers.<EntCodeToken>lambdaUpdate()
                .eq(EntCodeToken::getTenantNo, ctx.tenantNo()).eq(EntCodeToken::getUserNo, user)
                .eq(EntCodeToken::getInstanceNo, instance).eq(EntCodeToken::getStatus, "ACTIVE")
                .set(EntCodeToken::getStatus, "REVOKED").set(EntCodeToken::getUpdatedAt, now())
                .set(EntCodeToken::getUpdatedBy, ctx.actorNo())));
    }
    public CodeView view(EntCodeToken row) {
        return new CodeView(signer.token(row), instant(row.getExpiresAt()), row.getInstanceNo());
    }
    private EntInstance owned(EntitlementContext ctx, String user, String instance, boolean lock) {
        var row = lock ? unscoped(() -> instances.lock(ctx.tenantNo(), instance))
                : unscoped(() -> instances.selectOne(Wrappers.<EntInstance>lambdaQuery()
                        .eq(EntInstance::getTenantNo, ctx.tenantNo())
                        .eq(EntInstance::getInstanceNo, instance)));
        if (row == null || !user.equals(row.getUserNo()) || !instance.equals(row.getInstanceNo())) notFound();
        return row;
    }
    private LocalDateTime usable(EntInstance asset) {
        if (!"STORE_EXCHANGE".equals(asset.getKind())
                || !List.of("ACTIVE", "PENDING_ACTIVATION").contains(asset.getStatus())
                || asset.getRemainingUnits() == null || asset.getRemainingUnits() <= asset.getReservedUnits()) conflict();
        var rule = json.readValue(asset.getRuleSnapshot(), EntitlementDefinition.class);
        if (!"STORE_EXCHANGE".equals(rule.kind())) conflict();
        var window = new EntitlementValidityPolicy().calculate(rule.validity(), instant(asset.getSourcePaidAt()),
                instant(asset.getCreatedAt()), instant(asset.getActivationAt()));
        if (window.pendingActivation()) {
            if (!"PENDING_ACTIVATION".equals(asset.getStatus())) conflict();
            return null;
        }
        var from = asset.getEffectiveFrom() == null ? window.from() : instant(asset.getEffectiveFrom());
        var until = asset.getEffectiveUntil() == null ? window.until() : instant(asset.getEffectiveUntil());
        if (instant(now()).isBefore(from) || !instant(now()).isBefore(until)) conflict();
        return EntitlementRecords.utc(until);
    }
    private static <T> T unscoped(Supplier<T> query) { return DataScopeContext.executeWithoutScope(query); }
    private static void notFound() { throw BizException.of(ErrorCode.NOT_FOUND); }
}
