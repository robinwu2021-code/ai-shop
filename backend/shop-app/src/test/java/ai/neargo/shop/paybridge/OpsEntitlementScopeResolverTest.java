package ai.neargo.shop.paybridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
class OpsEntitlementScopeResolverTest {
    private final OpsEntitlementScopeResolver resolver=new OpsEntitlementScopeResolver();
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void login(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of()));
    }
    @Test void merchantBoundOperatorCannotSelectAnotherEntity() {
        login(LoginUser.operator("OP1","operator",List.of(),List.of(),DataScopeSpec.of(ScopeDim.MERCHANT,Set.of("M1"))));
        var scope=resolver.resolve("M1");
        assertThat(scope.actorNo()).isEqualTo("OP1"); assertThat(scope.platformActor()).isTrue();
        assertThatThrownBy(() -> resolver.resolve("M2")).isInstanceOf(BizException.class);
    }
    @Test void unsupportedDimensionAndConsumerCannotGainPlatformScope() {
        login(LoginUser.operator("OP1","operator",List.of(),List.of(),DataScopeSpec.of(ScopeDim.COMMUNITY,Set.of("C1"))));
        assertThatThrownBy(() -> resolver.resolve("M1")).isInstanceOf(BizException.class);
        login(LoginUser.consumer("U1","buyer"));
        assertThatThrownBy(() -> resolver.resolve("M1")).isInstanceOf(BizException.class);
    }
    @Test void globalOperatorStillMustSelectExplicitEntity() {
        login(LoginUser.operator("OP1","operator",List.of(),List.of()));
        assertThat(resolver.resolve("M1").tenantNo()).isEqualTo("MAIN");
        assertThatThrownBy(() -> resolver.resolve(null)).isInstanceOf(BizException.class);
    }
    @Test void aggregateQueueUsesOnlyActualMerchantRulesAndAnEmptyScopeNeverBecomesGlobal() {
        login(LoginUser.operator("OP1", "operator", List.of(), List.of(),
                DataScopeSpec.of(ScopeDim.MERCHANT, Set.of("M1", "M2"))));
        assertThat(resolver.resolveList(null).entityNos()).containsExactlyInAnyOrder("M1", "M2");
        assertThat(resolver.resolveList("M1").entityNos()).containsExactly("M1");
        assertThatThrownBy(() -> resolver.resolveList("M3")).isInstanceOf(BizException.class);
        login(LoginUser.operator("OP1", "operator", List.of(), List.of(),
                DataScopeSpec.of(ScopeDim.COMMUNITY, Set.of("C1"))));
        assertThat(resolver.resolveList(null).entityNos()).isEmpty();
        login(LoginUser.operator("OP1", "operator", List.of(), List.of()));
        assertThat(resolver.resolveList(null).entityNos()).isNull();
        login(LoginUser.consumer("U1", "buyer"));
        assertThatThrownBy(() -> resolver.resolveList(null)).isInstanceOf(BizException.class);
    }
}
