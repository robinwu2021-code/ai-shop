package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.svc.client.port.CoreLegacyCouponSourceCommitPortImpl;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort.CommitProof;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class CoreLegacyCouponSourceCommitClientTest {
    @Test void absentTransportCannotBeTreatedAsAnUndecidedSourceAndCommittedProofIsExact() {
        var api=mock(CoreEntitlementInternalApi.class);
        var beans=new StaticListableBeanFactory();beans.addBean("api",api);
        var client=new CoreLegacyCouponSourceCommitPortImpl(beans.getBeanProvider(CoreEntitlementInternalApi.class));
        var ctx=new EntitlementContext("MAIN","PAY","READ","a".repeat(64));
        var source=new SourceIdentity(SourceSystem.PMT,SourceKind.TEMPLATE,"T1");
        var query=new EntitlementCoreInternal.MigrationCommitQuery(ctx,source);
        when(api.legacyMigrationCommit(query)).thenReturn(new EntitlementCoreInternal.MigrationCommitReply(null));
        assertThat(client.decided(ctx,source)).isEmpty();
        when(api.legacyMigrationCommit(query)).thenReturn(null);
        assertThatThrownBy(()->client.decided(ctx,source)).isInstanceOfSatisfying(BizException.class,
                error->assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        var proof=new CommitProof(source,"B1","MD1","b".repeat(64),Instant.parse("2026-10-20T00:00:00Z"));
        when(api.legacyMigrationCommit(query)).thenReturn(new EntitlementCoreInternal.MigrationCommitReply(proof));
        assertThat(client.decided(ctx,source)).contains(proof);
    }
}
