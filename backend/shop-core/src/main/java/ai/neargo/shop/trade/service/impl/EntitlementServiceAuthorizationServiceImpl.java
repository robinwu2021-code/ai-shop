package ai.neargo.shop.trade.service.impl;

import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.hash;
import static ai.neargo.shop.trade.entitlement.EntitlementServiceSnapshotPolicy.snapshot;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.AuthorizationCommand;
import ai.neargo.shop.trade.dto.AuthorizationView;
import ai.neargo.shop.trade.entitlement.config.EntitlementAuthorizationProperties;
import ai.neargo.shop.trade.entitlement.entity.OrdEntAuthorization;
import ai.neargo.shop.trade.mapper.EntitlementServiceOrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.EntitlementAuthorizationMapper;
import ai.neargo.shop.trade.service.EntitlementServiceAuthorizationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Reuses OrdEntAuthorization and C09. Only the source lock/hash verification differs from ordinary orders. */
@Service
public class EntitlementServiceAuthorizationServiceImpl implements EntitlementServiceAuthorizationService {
    private final EntitlementServiceOrderMapper orders;
    private final EntitlementAuthorizationMapper authorizations;
    private final EntitlementAuthorizationProperties features;
    private final ObjectMapper json;
    public EntitlementServiceAuthorizationServiceImpl(EntitlementServiceOrderMapper orders,
            EntitlementAuthorizationMapper authorizations, EntitlementAuthorizationProperties features,
            ObjectMapper json) {
        this.orders = orders; this.authorizations = authorizations; this.features = features; this.json = json;
    }
    @Override public boolean supports(String tenant, String no) { return orders.find(tenant, no) != null; }
    @Override @Transactional
    public void stage(String tenant, String no, SourceRef source, String previewRef, String selection,
                       long amount, String currency, Instant expiresAt) {
        DataScopeContext.executeWithoutScope(() -> {
            stageUnscoped(tenant, no, source, previewRef, selection, amount, currency, expiresAt); return null;
        });
    }
    private void stageUnscoped(String tenant, String no, SourceRef source, String previewRef, String selection,
                               long amount, String currency, Instant expiresAt) {
        enabled(); var row = orders.lock(tenant, no);
        if (row == null) notFound(); var order = snapshot(row, json);
        if (!List.of("PREVIEWED", "PENDING").contains(row.getStatus()) || order.preview() == null
                || !source.equals(order.fact().source()) || amount != 0 || !currency.equals(order.fact().currency())
                || !previewRef.equals(order.preview().preview().previewRef())
                || !selection.equals(order.preview().preview().snapshotHash())
                || !expiresAt.equals(order.preview().preview().expiresAt())) conflict();
        Instant now = Instant.now();
        Instant expiry = expiresAt.isBefore(order.deadline()) ? expiresAt : order.deadline();
        if (expiry.isAfter(now.plusSeconds(600))) expiry = now.plusSeconds(600);
        if (!now.isBefore(expiry)) conflict();
        String payload = hash(json.writeValueAsString(List.of(tenant, no, source, previewRef, selection,
                order.fact().orderSnapshotHash(), amount, currency)));
        var previous = authorizations.selectOne(Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, tenant).eq(OrdEntAuthorization::getQuoteRef, previewRef));
        if (previous != null) {
            if (!payload.equals(previous.getPayloadHash())) conflict(); return;
        }
        var auth = new OrdEntAuthorization(); auth.setTenantNo(tenant);
        auth.setIntentKey(BizKey.next(BizKey.ENTITLEMENT_INTENT)); auth.setPayloadHash(payload);
        auth.setSourceKey(source.useIntentKey()); auth.setSourceSnapshot(json.writeValueAsString(source));
        auth.setSourceRevision(source.sourceRevision()); auth.setOrderNo(no); auth.setUserNo(row.getUserNo());
        auth.setEntityNo(row.getEntityNo()); auth.setStoreNo(row.getStoreNo()); auth.setQuoteRef(previewRef);
        auth.setSelectionDigest(selection); auth.setOrderSnapshotHash(row.getSourceHash());
        auth.setAmountMinor(0L); auth.setCurrency(currency); auth.setStatus("PENDING");
        auth.setExpiresAt(LocalDateTime.ofInstant(expiry, ZoneOffset.UTC)); auth.setVersion(0L); auth.setDeleted(0);
        auth.setCreatedAt(utcNow()); auth.setUpdatedAt(auth.getCreatedAt());
        auth.setCreatedBy(row.getUserNo()); auth.setUpdatedBy(row.getUserNo());
        if (authorizations.insert(auth) != 1) conflict();
    }
    @Override @Transactional
    public AuthorizationView confirm(String tenant, String user, String no, AuthorizationCommand c) {
        return DataScopeContext.executeWithoutScope(() -> confirmUnscoped(tenant, user, no, c));
    }
    private AuthorizationView confirmUnscoped(String tenant, String user, String no, AuthorizationCommand c) {
        enabled(); if (c == null) badRequest();
        var prior = authorizations.selectOne(Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, tenant).eq(OrdEntAuthorization::getUserNo, user)
                .eq(OrdEntAuthorization::getRequestKey, c.requestKey()));
        if (prior != null) {
            if (!no.equals(prior.getOrderNo()) || !c.quoteRef().equals(prior.getQuoteRef())
                    || !c.selectionDigest().equals(prior.getSelectionDigest())
                    || c.orderRevision() != prior.getSourceRevision()) conflict();
            return view(prior);
        }
        var row = orders.lock(tenant, no);
        if (row == null || !user.equals(row.getUserNo())) notFound();
        var order = snapshot(row, json);
        if (!List.of("PREVIEWED", "PENDING").contains(row.getStatus())
                || order.preview() == null || !Instant.now().isBefore(order.deadline())
                || !c.quoteRef().equals(order.preview().preview().previewRef())
                || !c.selectionDigest().equals(order.preview().preview().snapshotHash())
                || c.orderRevision() != order.fact().source().sourceRevision()) conflict();
        var auth = authorizations.selectOne(Wrappers.<OrdEntAuthorization>lambdaQuery()
                .eq(OrdEntAuthorization::getTenantNo, tenant).eq(OrdEntAuthorization::getOrderNo, no)
                .eq(OrdEntAuthorization::getQuoteRef, c.quoteRef()).last("FOR UPDATE"));
        if (auth == null || !"PENDING".equals(auth.getStatus()) || !user.equals(auth.getUserNo())
                || !row.getEntityNo().equals(auth.getEntityNo()) || !row.getStoreNo().equals(auth.getStoreNo())
                || !Objects.equals(auth.getAmountMinor(), 0L) || !order.fact().currency().equals(auth.getCurrency())
                || !order.fact().source().equals(json.readValue(auth.getSourceSnapshot(), SourceRef.class))
                || !row.getSourceHash().equals(auth.getOrderSnapshotHash())
                || !utcNow().isBefore(auth.getExpiresAt())) conflict();
        auth.setAuthorizationNo(BizKey.next(BizKey.ENTITLEMENT_AUTHORIZATION));
        auth.setRequestKey(c.requestKey()); auth.setStatus("AUTHORIZED"); auth.setAuthorizedAt(utcNow());
        auth.setUpdatedAt(auth.getAuthorizedAt()); auth.setUpdatedBy(user);
        if (authorizations.updateById(auth) != 1) conflict();
        row.setStatus("AUTHORIZED"); row.setUpdatedAt(utcNow()); row.setUpdatedBy(user);
        if (orders.updateById(row) != 1) conflict();
        return view(auth);
    }
    private AuthorizationView view(OrdEntAuthorization row) {
        return new AuthorizationView(row.getAuthorizationNo(), row.getStatus(),
                row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }
    private void enabled() {
        if (!features.isEnabled()) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }
    private static LocalDateTime utcNow() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS); }
    private static void badRequest() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    private static void notFound() { throw BizException.of(ErrorCode.NOT_FOUND); }
}
