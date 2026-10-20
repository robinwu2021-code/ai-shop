package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.impl.LegacyCouponRouteResolver;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort.Origin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LegacyCouponRouteResolverTest {
    private final EntitlementContext ctx=new EntitlementContext("MAIN","ACTOR","ROUTE","a".repeat(64));
    private final LegacyCouponIdentityPort identities=mock(LegacyCouponIdentityPort.class);
    private final EntitlementOwnerRegistryPort owners=mock(EntitlementOwnerRegistryPort.class);
    private final LegacyCouponRouteResolver resolver=new LegacyCouponRouteResolver(identities,owners);
    private Origin origin(SourceSystem source,String state,String proof) {
        return new Origin(new SourceIdentity(source,SourceKind.INSTANCE,"U1"),"T1","M1","MERCHANT",state,
                proof==null?null:"B1",proof,true,false,true);
    }
    private OwnerRef pay(Origin origin,String target) {
        return new OwnerRef(origin.source(),target,OwnerSystem.PAY,MigrationState.CUTOVER,"B1","c".repeat(64),1);
    }
    @Test void explicitOpenCoreFactsPreserveTheExistingSourcePriorityWithoutCallingPay() {
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(
                origin(SourceSystem.MKT_LEGACY,"OPEN",null),origin(SourceSystem.PMT,"ABORT_DECIDED",null)));
        var result=resolver.resolve(ctx,"USER","U1");
        assertThat(result.ownerSystem()).isEqualTo(OwnerSystem.CORE_LEGACY);
        assertThat(result.source().sourceSystem()).isEqualTo(SourceSystem.PMT);
        verifyNoInteractions(owners);
    }
    @Test void frozenMixedOrUnprovedSourcesNeverFallThroughToAnotherWriter() {
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(origin(SourceSystem.PMT,"FROZEN",null)));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class);
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(
                origin(SourceSystem.PMT,"COMMIT_DECIDED","c".repeat(64)),origin(SourceSystem.MKT_LEGACY,"OPEN",null)));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class);
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(origin(SourceSystem.PMT,"COMMIT_DECIDED",null)));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class);
        verifyNoInteractions(owners);
    }
    @Test void committedSourceRequiresTheExactOwnerBatchAndDurableProof() {
        var original=origin(SourceSystem.PMT,"COMMIT_DECIDED","c".repeat(64));
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(original));
        when(owners.resolve(ctx,original.source())).thenReturn(pay(original,"EI1"));
        assertThat(resolver.resolve(ctx,"USER","U1").targetNo()).isEqualTo("EI1");
        when(owners.resolve(ctx,original.source())).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class);
    }
    @Test void aliasesMustConvergeOnOneTargetAndNeverChooseTheLargerOrFirstAsset() {
        var pmt=origin(SourceSystem.PMT,"COMMIT_DECIDED","c".repeat(64));
        var mkt=origin(SourceSystem.MKT_LEGACY,"COMMIT_DECIDED","c".repeat(64));
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(mkt,pmt));
        when(owners.resolve(ctx,pmt.source())).thenReturn(pay(pmt,"EI1"));
        when(owners.resolve(ctx,mkt.source())).thenReturn(pay(mkt,"EI1"));
        assertThat(resolver.resolve(ctx,"USER","U1").targetNo()).isEqualTo("EI1");
        when(owners.resolve(ctx,mkt.source())).thenReturn(pay(mkt,"EI2"));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class);
    }
    @Test void remoteOwnerLookupsAreRejectedBeforeAnyCallWhileFinancialTransactionIsActive() {
        var original=origin(SourceSystem.PMT,"COMMIT_DECIDED","c".repeat(64));
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(original));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOf(BizException.class); }
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(owners);
    }
    @Test void noLegacyOriginOnlyAllowsAnExplicitNativeSourceRegistration() {
        when(identities.ownedOrigins(ctx,"USER","PAY-U1")).thenReturn(List.of());
        var nativeSource=new SourceIdentity(SourceSystem.PAY,SourceKind.INSTANCE,"PAY-U1");
        when(owners.resolve(ctx,nativeSource)).thenReturn(
                new OwnerRef(nativeSource,"PAY-U1",OwnerSystem.PAY,MigrationState.CUTOVER,"G1","d".repeat(64),0));
        assertThat(resolver.resolve(ctx,"USER","PAY-U1").source()).isEqualTo(nativeSource);
        when(owners.resolve(ctx,nativeSource)).thenThrow(BizException.of(ErrorCode.NOT_FOUND));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","PAY-U1")).isInstanceOf(BizException.class);
    }
    @Test void aKnownDeletedLegacyIdentityCannotResurrectThroughANativeNamespaceFallback() {
        var deleted=new Origin(new SourceIdentity(SourceSystem.PMT,SourceKind.INSTANCE,"U1"),"T1","M1","MERCHANT",
                "OPEN",null,null,true,true,true);
        when(identities.ownedOrigins(ctx,"USER","U1")).thenReturn(List.of(deleted));
        assertThatThrownBy(()->resolver.resolve(ctx,"USER","U1")).isInstanceOfSatisfying(BizException.class,
                failure->assertThat(failure.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(owners);
    }

    @Test void ordinaryOtherTenantKeepsCoreRoutingButCannotEnterPayOwnership() {
        var other=new EntitlementContext("OTHER","ACTOR","ROUTE","a".repeat(64));
        when(identities.ownedOrigins(other,"USER","U1")).thenReturn(List.of(origin(SourceSystem.PMT,"OPEN",null)));
        assertThat(resolver.resolve(other,"USER","U1").ownerSystem()).isEqualTo(OwnerSystem.CORE_LEGACY);
        when(identities.ownedOrigins(other,"USER","U1")).thenReturn(List.of(origin(SourceSystem.PMT,"COMMIT_DECIDED","c".repeat(64))));
        assertThatThrownBy(()->resolver.resolve(other,"USER","U1")).isInstanceOf(BizException.class);
        when(identities.ownedOrigins(other,"USER","U1")).thenReturn(List.of());
        assertThatThrownBy(()->resolver.resolve(other,"USER","U1")).isInstanceOf(BizException.class);
        verifyNoInteractions(owners);
    }

}
