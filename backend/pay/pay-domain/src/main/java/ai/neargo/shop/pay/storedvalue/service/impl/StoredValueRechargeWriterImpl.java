package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.conflict;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;


import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.event.PayEntitlementEventPublisher;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeWriter;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeCancelled;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeReceived;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreateRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Issuer quota precedes receipt locks; credit shares quota -> account -> receipt order. */
@Service
public class StoredValueRechargeWriterImpl implements StoredValueRechargeWriter {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerLedgerBridge issuerLedger;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.entitlement.service.PayRightsDocumentNumbers documentNumbers;
    private final SvRechargeMapper recharges;
    private final EntitlementCommandDeduplicator commands;
    private final PayEntitlementEventPublisher events;
    private final ObjectMapper json;
    public StoredValueRechargeWriterImpl(SvRechargeMapper recharges, EntitlementCommandDeduplicator commands,
                                         PayEntitlementEventPublisher events, ObjectMapper json) {
        this.recharges = recharges; this.commands = commands; this.events = events; this.json = json;
    }
    @Override public RechargeView existing(EntitlementContext ctx, String entity, CreateRecharge c) {
        var r = recharges.find(ctx.tenantNo(), rechargeNo(c)); if (r == null) return null;
        requireCreate(r, ctx, entity, c); return view(r);
    }
    @Override public RechargeView query(EntitlementContext ctx, String entity, String no) {
        no=documentNumbers.resolve(ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,ctx.tenantNo(),no);
        var r = recharges.find(ctx.tenantNo(), no);
        if (r == null || !entity.equals(r.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return view(r);
    }
    @Override public boolean exists(EntitlementContext ctx, String entity, String no) {
        var r = recharges.find(ctx.tenantNo(), no);
        if (r != null && !entity.equals(r.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return r != null;
    }
    @Override @Transactional("payTxManager")
    public RechargeView create(EntitlementContext ctx, String entity, CreateRecharge c, SourceFact source) {
        commands.execute(scope(ctx, entity), "SV_CREATE", ctx.requestId(), c, source.rechargeNo(), RechargeView.class,
                () -> {
                    var prior = recharges.lock(ctx.tenantNo(), source.rechargeNo());
                    if (prior != null) { requireCreate(prior, ctx, entity, c); return view(prior); }
                    var r = frozen(source); recharges.insert(r); return view(r);
                });
        // Creation replay reflects a durable cancellation, never a stale instruction to collect cash.
        var actual = recharges.lock(ctx.tenantNo(), source.rechargeNo()); requireCreate(actual, ctx, entity, c);
        return view(actual);
    }
    @Override @Transactional("payTxManager")
    public RechargeView submit(EntitlementContext ctx, String entity, SubmitRecharge c) {
        issuerLedger.beforeOriginal(ctx,entity,c.rechargeNo());
        return commands.execute(scope(ctx, entity), "SV_SUBMIT", ctx.requestId(), c, c.rechargeNo(),
                RechargeView.class,
                () -> {
                    var r = owned(ctx, entity, c.rechargeNo()); revision(r, c.expectedRevision());
                    if (!"WAIT_COLLECTION".equals(r.getStatus()) || !now().isBefore(r.getExpiresAt())
                            || c.actualAmountMinor() != r.getPrincipalMinor()) conflict();
                    r.setCollectionMethod(c.collectionMethod().name()); r.setActualAmountMinor(c.actualAmountMinor());
                    r.setReceiptRef(c.receiptRef()); r.setReceiptDigest(c.receiptDigest()); r.setStatus("SUBMITTED");
                    save(r, ctx); return view(r);
                });
    }
    @Override @Transactional("payTxManager")
    public RechargeView receive(EntitlementContext ctx, String entity, ReceiveRecharge c) {
        issuerLedger.beforeOriginal(ctx,entity,c.rechargeNo());
        return commands.execute(scope(ctx, entity), "SV_RECEIVE", ctx.requestId(), c, c.rechargeNo(),
                RechargeView.class,
                () -> {
                    var r = owned(ctx, entity, c.rechargeNo()); revision(r, c.expectedRevision());
                    if (!"SUBMITTED".equals(r.getStatus()) || !c.receiptDigest().equals(r.getReceiptDigest())
                            || r.getActualAmountMinor() == null
                            || !r.getPrincipalMinor().equals(r.getActualAmountMinor())) conflict();
                    LocalDateTime at = now();
                    var decision = new DecisionRef(no("RD", List.of(ctx.tenantNo(), r.getRechargeNo()), json),
                            at.toInstant(ZoneOffset.UTC), 0, DecisionRef.DecisionType.RECHARGE_RECEIVED);
                    r.setStatus("RECEIVED"); r.setReceivedAt(at); r.setReceivedBy(ctx.actorNo());
                    r.setConfirmationNote(c.confirmationNote());
                    r.setReceivedDecisionSnapshot(json.writeValueAsString(decision));
                    r.setReceivedReceiptKey(digest(List.of(r.getCollectionMethod(), r.getReceiptRef()), json));
                    save(r, ctx);
                    events.publish(new StoredValueRechargeReceived(no("SR", List.of(ctx.tenantNo(), r.getRechargeNo()),
                            json), ctx.tenantNo(), r.getRechargeNo(), entity, at.toInstant(ZoneOffset.UTC).toEpochMilli()));
                    return view(r);
                });
    }
    @Override @Transactional("payTxManager")
    public RechargeView cancel(EntitlementContext ctx, String entity, CancelRecharge c, SourceFact source) {
        issuerLedger.beforeOriginal(ctx,entity,c.rechargeNo());
        return commands.execute(scope(ctx, entity), "SV_CANCEL", ctx.requestId(), c, c.rechargeNo(),
                RechargeView.class,
                () -> {
                    var r = recharges.lock(ctx.tenantNo(), c.rechargeNo());
                    if (r == null) {
                        if (source == null || c.expectedRevision() != 0) conflict();
                        r = frozen(source); recharges.insert(r);
                    }
                    if (!entity.equals(r.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
                    if ("CANCELLED".equals(r.getStatus())) return view(r);
                    revision(r, c.expectedRevision());
                    if (!List.of("WAIT_COLLECTION", "SUBMITTED").contains(r.getStatus())) conflict();
                    r.setStatus("CANCELLED"); r.setCancellationReason(c.reason()); r.setCancelledAt(now()); save(r, ctx);
                    issuerLedger.cancelled(ctx,entity,r.getRechargeNo());
                    events.publish(new StoredValueRechargeCancelled(no("SX", List.of(ctx.tenantNo(), r.getRechargeNo()),
                            json), ctx.tenantNo(), r.getRechargeNo(), entity,
                            r.getCancelledAt().toInstant(ZoneOffset.UTC).toEpochMilli()));
                    return view(r);
                });
    }
    private SvRecharge frozen(SourceFact f) {
        var p = f.permit();
        if (p.payAmountMinor() == null || !p.benefitRefs().isEmpty()) conflict();
        var r = new SvRecharge(); audit(r, f.context(), LocalDateTime.ofInstant(f.createdAt(), ZoneOffset.UTC));
        r.setRechargeNo(f.rechargeNo()); r.setRequestId(f.context().requestId());
        documentNumbers.bind(ai.neargo.shop.numbering.DocumentType.SV_RECHARGE, f.context().tenantNo(), r.getRechargeNo());
        r.setPayloadHash(digest(new CreateRecharge(f.source(), p), json));
        r.setReservationNo(p.reservationNo()); r.setActivityNo(p.activityNo()); r.setActivityVersionNo(p.versionNo());
        r.setUserNo(p.userNo()); r.setEntityNo(p.entityNo()); r.setStoreNo(p.storeNo()); r.setStoreName(f.storeName());
        r.setCurrency(p.currency()); r.setPrincipalMinor(p.payAmountMinor()); r.setBonusMinor(p.bonusAmountMinor());
        r.setStatus("WAIT_COLLECTION"); r.setOperatorNo(f.context().actorNo());
        r.setExpiresAt(LocalDateTime.ofInstant(f.expiresAt(), ZoneOffset.UTC));
        r.setPermitSnapshot(json.writeValueAsString(p)); r.setSourceSnapshot(json.writeValueAsString(f.source()));
        return r;
    }
    private RechargeView view(SvRecharge row) {
        var result=ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.view(row);
        return new RechargeView(result.rechargeNo(),result.status(),result.userNo(),result.entityNo(),result.storeNo(),
                result.currency(),result.paidAmountMinor(),result.creditedTotalMinor(),result.receivedAt(),result.creditedAt(),
                result.revision(),result.customerNo(),documentNumbers.find(ai.neargo.shop.numbering.DocumentType.SV_RECHARGE,
                        row.getTenantNo(),row.getRechargeNo()));
    }
    private void requireCreate(SvRecharge r, EntitlementContext ctx, String entity, CreateRecharge c) {
        if (r == null || !r.getEntityNo().equals(entity) || !r.getOperatorNo().equals(ctx.actorNo())
                || !r.getRequestId().equals(ctx.requestId()) || !r.getPayloadHash().equals(digest(c, json))) conflict();
    }
    private SvRecharge owned(EntitlementContext ctx, String entity, String no) {
        var r = recharges.lock(ctx.tenantNo(), no);
        if (r == null || !entity.equals(r.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return r;
    }
    private void save(SvRecharge r, EntitlementContext ctx) {
        r.setUpdatedAt(now()); r.setUpdatedBy(ctx.actorNo());
        if (recharges.updateById(r) != 1) conflict();
    }
    private static void revision(SvRecharge r, long expected) { if (r.getVersion() != expected) conflict(); }
    private static CatalogScope scope(EntitlementContext ctx, String entity) {
        return new CatalogScope(ctx.tenantNo(), entity, ctx.actorNo(), false);
    }
    private static String rechargeNo(CreateRecharge c) { return "RC" + c.sourceRef().grantKey().substring(0, 60); }
    private static LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC);
    }
}
