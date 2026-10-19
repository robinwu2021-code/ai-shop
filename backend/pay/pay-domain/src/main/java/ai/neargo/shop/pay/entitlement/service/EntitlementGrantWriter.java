package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.PreparedGrant;
import ai.neargo.shop.pay.entitlement.entity.EntGrant;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.entity.EntLedger;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.policy.EntitlementValidityPolicy;
import ai.neargo.shop.pay.mapper.entitlement.EntGrantMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLedgerMapper;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.EntitlementGranted;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantView;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.State;
import ai.neargo.shop.spi.entitlement.SourceRef;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Pay-only transactions; external fact lookups must finish before invoking this component. */
@Component
public class EntitlementGrantWriter {
    private final EntGrantMapper grants;
    private final EntInstanceMapper instances;
    private final EntLedgerMapper ledger;
    private final EntitlementIssuePolicyService policies;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    public EntitlementGrantWriter(EntGrantMapper grants, EntInstanceMapper instances, EntLedgerMapper ledger,
            EntitlementIssuePolicyService policies, PayEntitlementEventPublisher events, ObjectMapper json) {
        this.grants = grants; this.instances = instances; this.ledger = ledger;
        this.policies = policies; this.events = events; this.json = json;
    }
    @Transactional("payTxManager")
    public GrantView prepare(EntitlementContext context, PreparedGrant prepared) {
        var c = prepared.command();
        var row = new EntGrant(); audit(row, context);
        row.setGrantNo(c.grantNo()); row.setSourceKey(c.sourceRef().grantKey());
        row.setSourceSnapshot(json.writeValueAsString(c.sourceRef())); row.setTemplateVersionNo(c.templateVersionNo());
        row.setUserNo(c.recipientUserNo()); row.setEntityNo(c.entityNo()); row.setQuantity(c.quantity());
        row.setReservationNo(c.permit() == null ? null : c.permit().reservationNo()); row.setPaidFactRef(c.paidFactRef());
        row.setPayloadHash(digest(json.writeValueAsString(c))); row.setPreparedSnapshot(json.writeValueAsString(prepared));
        grants.claim(row);
        var original = grants.lock(context.tenantNo(), c.grantNo());
        if (original == null || !original.getPayloadHash().equals(row.getPayloadHash())) conflict();
        if (!"PREPARING".equals(original.getStatus())) return view(original);
        policies.reserve(scope(context, c.entityNo()), prepared.templateNo(), c.recipientUserNo(),
                c.quantity(), prepared.exposureMinor());
        original.setStatus("PREPARED"); original.setUpdatedAt(now()); original.setUpdatedBy(context.actorNo());
        changed(grants.updateById(original));
        return view(original);
    }
    @Transactional("payTxManager")
    public GrantView finish(EntitlementContext context, String no, SourceRef source, DecisionRef decision, boolean issue) {
        var row = grants.lock(context.tenantNo(), no);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var prepared = json.readValue(row.getPreparedSnapshot(), PreparedGrant.class);
        if (!prepared.command().sourceRef().equals(source)) conflict();
        if (!"PREPARED".equals(row.getStatus())) {
            if (!(issue ? "COMMITTED" : "ABORTED").equals(row.getStatus())
                    || !decision.decisionNo().equals(row.getSourceDecisionNo())) conflict();
            return view(row);
        }
        policies.settle(scope(context, row.getEntityNo()), prepared.templateNo(), row.getUserNo(),
                row.getQuantity(), prepared.exposureMinor(), issue);
        if (issue) {
            Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var d = prepared.definition();
            var window = new EntitlementValidityPolicy().calculate(d.validity(), prepared.paidAt(), at, null);
            for (int i = 1; i <= row.getQuantity(); i++) {
                var asset = new EntInstance(); audit(asset, context);
                asset.setInstanceNo("EI" + UUID.randomUUID().toString().replace("-", ""));
                asset.setTemplateVersionNo(row.getTemplateVersionNo()); asset.setGrantNo(no); asset.setUnitIndex(i);
                asset.setSourceUnitKey(digest(row.getSourceKey() + ":" + i)); asset.setUserNo(row.getUserNo());
                asset.setEntityNo(row.getEntityNo()); asset.setKind(d.kind()); asset.setTotalUnits(d.totalUnits());
                asset.setRemainingUnits(d.totalUnits()); asset.setReservedUnits(0); asset.setAssetSeq(1L);
                asset.setEffectiveFrom(utc(window.from())); asset.setEffectiveUntil(utc(window.until()));
                asset.setActivationAt(window.pendingActivation() ? null : utc(at));
                asset.setSourcePaidAt(utc(prepared.paidAt())); asset.setRuleSnapshot(json.writeValueAsString(d));
                asset.setStatus(window.pendingActivation() ? "PENDING_ACTIVATION"
                        : (!at.isBefore(window.until()) ? "EXPIRED" : "ACTIVE"));
                changed(instances.insert(asset));
                var entry = new EntLedger(); audit(entry, context);
                entry.setEntryNo("EL" + UUID.randomUUID().toString().replace("-", ""));
                entry.setInstanceNo(asset.getInstanceNo()); entry.setAssetSeq(1L); entry.setBizType("GRANT");
                entry.setBizKey("GRANT:" + no); entry.setUnitsDelta(d.totalUnits() == null ? 0 : d.totalUnits());
                entry.setReservedDelta(0); entry.setAfterUnits(d.totalUnits()); entry.setAfterReserved(0);
                entry.setOccurredAt(utc(at)); changed(ledger.insert(entry));
            }
            row.setCommittedAt(utc(at));
            events.publish(new EntitlementGranted("EG" + digest(context.tenantNo() + ":" + no).substring(0, 60),
                    no, row.getUserNo(), row.getEntityNo(), row.getTemplateVersionNo(), source.sourceType(),
                    source.sourceNo(), at.toEpochMilli()));
        }
        row.setStatus(issue ? "COMMITTED" : "ABORTED"); row.setSourceDecisionNo(decision.decisionNo());
        row.setUpdatedAt(now()); row.setUpdatedBy(context.actorNo()); changed(grants.updateById(row));
        return view(row);
    }
    public GrantView view(EntGrant row) {
        var source = json.readValue(row.getSourceSnapshot(), SourceRef.class);
        List<String> ids = "COMMITTED".equals(row.getStatus()) ? instances.selectList(
                Wrappers.<EntInstance>lambdaQuery().eq(EntInstance::getTenantNo, row.getTenantNo())
                        .eq(EntInstance::getGrantNo, row.getGrantNo()).orderByAsc(EntInstance::getUnitIndex))
                .stream().map(EntInstance::getInstanceNo).toList() : List.of();
        return new GrantView(row.getGrantNo(), State.valueOf(row.getStatus()), ids, source);
    }
    public static String digest(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required SHA-256 is unavailable", e); }
    }
    private static CatalogScope scope(EntitlementContext context, String entity) {
        return new CatalogScope(context.tenantNo(), entity, context.actorNo(), false);
    }
    private static LocalDateTime now() { return utc(Instant.now().truncatedTo(ChronoUnit.MILLIS)); }
    private static LocalDateTime utc(Instant time) { return time == null ? null : LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static void audit(BaseEntity row, EntitlementContext context) {
        row.setTenantNo(context.tenantNo()); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(now()); row.setUpdatedAt(now()); row.setCreatedBy(context.actorNo()); row.setUpdatedBy(context.actorNo());
    }
    private static void changed(int n) { if (n != 1) conflict(); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
