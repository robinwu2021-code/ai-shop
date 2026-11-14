package ai.neargo.shop.pay.storedvalue.policy;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Funding;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

public final class StoredValueFacts {
    private StoredValueFacts() { }
    public record ReceivedScope(RedemptionScope redemptionScope) { }

    public static ReceivedScope requireReceived(SvRecharge r, CreditRecharge c, ObjectMapper json) {
        if (r == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var expected = new SourceRef("PAY", "RECHARGE", r.getRechargeNo(), null, null, null, 0);
        if (!expected.equals(c.sourceRef()) || !List.of("RECEIVED", "CREDITED").contains(r.getStatus())
                || r.getReceivedDecisionSnapshot() == null || r.getReceivedAt() == null
                || r.getReceivedBy() == null || r.getReceiptRef() == null || r.getReceiptDigest() == null
                || r.getCollectionMethod() == null
                || !List.of("CASH", "TRANSFER", "MERCHANT_OFFLINE").contains(r.getCollectionMethod())
                || !Objects.equals(r.getActualAmountMinor(), r.getPrincipalMinor()) || r.getPrincipalMinor() <= 0
                || r.getBonusMinor() < 0) conflict();
        var decision = json.readValue(r.getReceivedDecisionSnapshot(), DecisionRef.class);
        if (!decision.equals(c.decisionRef()) || !decision.occurredAt().equals(instant(r.getReceivedAt()))) conflict();
        decision.requireFor(expected, DecisionRef.DecisionType.RECHARGE_RECEIVED);
        var source = json.readValue(r.getSourceSnapshot(), SourceRef.class);
        if("ISSUER_RELEASE".equals(r.getSourceKind())){
            var f=json.readValue(r.getPermitSnapshot(),ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.Funding.class);
            if(!new SourceRef("PAY","ISSUER_RECHARGE",r.getRechargeNo(),null,null,null,0).equals(source)||!r.getRechargeNo().equals(f.rechargeNo())||!Objects.equals(r.getUserNo(),f.userNo())||!r.customerIdentity().equals(f.customerNo())||!r.getEntityNo().equals(f.operatingEntityNo())||!r.getStoreNo().equals(f.storeNo())||!r.getCurrency().equals(f.currency())||!Objects.equals(r.getPrincipalMinor(),f.paidAmountMinor())||!Objects.equals(r.getBonusMinor(),f.bonusAmountMinor())||!Objects.equals(r.getActivityNo(),f.activityNo())||!Objects.equals(r.getActivityVersionNo(),f.activityVersionNo()))conflict();
            return new ReceivedScope(new RedemptionScope("ALL",List.of()));
        }
        if ("PLAN".equals(r.getSourceKind())) {
            var f = json.readValue(r.getPermitSnapshot(), Funding.class);
            if (!new SourceRef("PAY", "RECHARGE_PLAN", r.getRechargeNo(), null, null, null, 0).equals(source)
                    || !r.getRechargeNo().equals(f.rechargeNo()) || !Objects.equals(r.getUserNo(),f.userNo()) || !r.customerIdentity().equals(f.customerNo())
                    || !r.getEntityNo().equals(f.entityNo()) || !r.getStoreNo().equals(f.storeNo())
                    || !r.getCurrency().equals(f.currency()) || !Objects.equals(r.getPlanNo(), f.planNo())
                    || !Objects.equals(r.getPlanVersionNo(), f.versionNo())
                    || !Objects.equals(r.getPrincipalMinor(), f.paidAmountMinor())
                    || !Objects.equals(r.getBonusMinor(), f.bonusAmountMinor())
                    || !new RedemptionScope("STORES", List.of(r.getStoreNo())).equals(f.redemptionScope())) conflict();
            return new ReceivedScope(f.redemptionScope());
        }
        var permit = json.readValue(r.getPermitSnapshot(), ParticipationPermit.class);
        if (!"CORE".equals(source.sourceSystem()) || !"RECHARGE".equals(source.sourceType())
                || source.sourceRevision() != 0 || source.sourceLineNo() != null
                || !source.equals(permit.sourceRef()) || !r.getReservationNo().equals(permit.reservationNo())
                || !r.getActivityNo().equals(permit.activityNo())
                || !r.getActivityVersionNo().equals(permit.versionNo())
                || !r.getUserNo().equals(permit.userNo()) || !r.getEntityNo().equals(permit.entityNo())
                || !r.getStoreNo().equals(permit.storeNo()) || !r.getCurrency().equals(permit.currency())
                || !Objects.equals(r.getPrincipalMinor(), permit.payAmountMinor())
                || !Objects.equals(r.getBonusMinor(), permit.bonusAmountMinor()) || !permit.benefitRefs().isEmpty()) {
            conflict();
        }
        return new ReceivedScope(permit.redemptionScope());
    }
    public static RechargeView view(SvRecharge r) {
        return new RechargeView(r.getRechargeNo(), RechargeState.valueOf(r.getStatus()), r.getUserNo(), r.getEntityNo(),
                r.getStoreNo(), r.getCurrency(), r.getPrincipalMinor(), add(r.getPrincipalMinor(), r.getBonusMinor()),
                instant(r.getReceivedAt()), instant(r.getCreditedAt()), r.getVersion(),r.customerIdentity());
    }
    public static long add(long first, long second) {
        try { return Math.addExact(first, second); }
        catch (ArithmeticException overflow) { throw BizException.of(ErrorCode.BAD_REQUEST); }
    }
    public static String no(String prefix, Object value, ObjectMapper json) {
        return prefix + digest(value, json).substring(0, 60);
    }
    public static String digest(Object value, ObjectMapper json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 required", impossible); }
    }
    public static void audit(BaseEntity row, EntitlementContext ctx, LocalDateTime at) {
        row.setTenantNo(ctx.tenantNo()); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedBy(ctx.actorNo()); row.setUpdatedBy(ctx.actorNo()); row.setCreatedAt(at); row.setUpdatedAt(at);
    }
    public static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    public static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
