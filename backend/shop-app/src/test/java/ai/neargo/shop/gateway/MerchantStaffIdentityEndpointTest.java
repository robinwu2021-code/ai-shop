package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import ai.neargo.shop.payclient.PayBizException;
import ai.neargo.shop.payclient.PaySourceLinkApi;
import ai.neargo.shop.portal.internal.MerchantStaffIdentityEndpoint;
import ai.neargo.svc.client.ServiceCallException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** B 端统一认证接口（TDD-支付三端经网关透传与权限归属 §3.2、§6）。依赖全部是桩，只验这一层的口径。 */
@DisplayName("网关 · B 端统一认证接口 merchant-staff")
class MerchantStaffIdentityEndpointTest {

    private static final String CALLER = "pay-biz-caller";
    /** near-pay 阶段 2 要传的前缀（§13.5） */
    private static final String NS = "mch:pay:,biz:finance,biz:store:admin";

    private final TokenStore tokens = mock(TokenStore.class);
    private final MerchantEntityService entities = mock(MerchantEntityService.class);
    private final BizIdentityResolver resolver = mock(BizIdentityResolver.class);
    private final PaySourceLinkApi links = mock(PaySourceLinkApi.class);
    private final StoreSyncProperties sync =
            new StoreSyncProperties(false, null, null, null, "UME", null, null, null, null);
    private MerchantStaffIdentityEndpoint endpoint;

    private final LoginUser owner = LoginUser.merchant("U-OWNER", "o");
    private final LoginUser staff = LoginUser.merchant("U-STAFF", "s");

    @BeforeEach
    void setUp() {
        endpoint = endpoint(CALLER);
        when(tokens.get("btk_owner")).thenReturn(Optional.of(TokenStore.SessionData.of(owner)));
        when(tokens.get("btk_staff")).thenReturn(Optional.of(TokenStore.SessionData.of(staff)));
        when(tokens.get("ctk_buyer")).thenReturn(Optional.of(TokenStore.SessionData.of(LoginUser.consumer("U-BUYER", "b"))));
        linked("E-OWN", "PM-OWN");
        linked("E-NOSTORE", "PM-NOSTORE");
        linked("E-SHOP", "PM-SHOP");
        when(links.status("UME", "E-UNLINKED")).thenReturn(null);
    }

    @Test
    @DisplayName("★★★ 进程凭据不对、没带或服务端没配：403，不看令牌")
    void callerCredentialRequired() {
        assertThat(call(null, "btk_owner", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call("wrong", "btk_owner", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(endpoint("").merchantStaff(CALLER, "Bearer btk_owner", NS, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(tokens);
    }

    @Test
    @DisplayName("★★★ 没令牌、令牌无效、过期、不是商户端令牌：401")
    void invalidSessions() {
        when(tokens.get("btk_expired")).thenReturn(Optional.empty());
        assertThat(endpoint.merchantStaff(CALLER, null, NS, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(CALLER, "btk_expired", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(CALLER, "ctk_buyer", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("★★★ 店主：名下主体关联的支付商户回 *，含还没建门店的主体；没关联支付商户的主体不出现")
    void ownerGetsWildcardOnOwnedMerchants() {
        when(entities.myStores(owner)).thenReturn(List.of(
                group("E-OWN", "S-1", "S-2"), group("E-UNLINKED", "S-9"), group("E-NOSTORE")));
        when(entities.myEntities("U-OWNER")).thenReturn(List.of(entity("E-OWN"), entity("E-UNLINKED"), entity("E-NOSTORE")));
        when(resolver.resolveAuthenticated(eq(owner), eq("S-1")))
                .thenReturn(new BizContext("E-OWN", Set.of(), Set.of(), Set.of("S-1", "S-2"), "S-1", true));
        when(resolver.resolveAuthenticated(eq(owner), eq("S-9")))
                .thenReturn(new BizContext("E-UNLINKED", Set.of(), Set.of(), Set.of("S-9"), "S-9", true));

        List<Map<String, Object>> rows = memberships(call(CALLER, "btk_owner", null));

        assertThat(rows).extracting(r -> r.get("payMerchantNo")).containsExactly("PM-OWN", "PM-NOSTORE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("role")).isEqualTo("OWNER");
            assertThat(r.get("perms")).isEqualTo(List.of("*"));
            assertThat(r.get("storeScope")).isEqualTo("ALL");
        });
    }

    @Test
    @DisplayName("★★★ 店员：按门店回本命名空间的码；码是角色给的；一个支付码都没有的主体不出现；不是本人的商户不出现")
    void staffGetsRoleCodesPerStore() {
        when(entities.myStores(staff)).thenReturn(List.of(group("E-SHOP", "S-A", "S-B", "S-C"), group("E-OWN", "S-1")));
        when(entities.myEntities("U-STAFF")).thenReturn(List.of());
        when(resolver.resolveAuthenticated(eq(staff), eq("S-A"))).thenReturn(new BizContext("E-SHOP", Set.of(), Set.of(),
                Set.of("S-A", "S-B"), "S-A", false, Map.of(),
                Map.of("S-A", Set.of("mch:pay:order:view", "mch:pay:refund", "biz:order:view", "biz:finance"),
                        "S-B", Set.of("mch:pay:order:view"))));
        when(resolver.resolveAuthenticated(eq(staff), eq("S-1"))).thenReturn(new BizContext("E-OWN", Set.of(), Set.of(),
                Set.of("S-1"), "S-1", false, Map.of(), Map.of("S-1", Set.of("biz:order:view"))));

        List<Map<String, Object>> rows = memberships(call(CALLER, "btk_staff", null));

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("payMerchantNo")).isEqualTo("PM-SHOP");
        assertThat(row.get("role")).isEqualTo("STAFF");
        assertThat(row.get("perms")).isEqualTo(List.of("biz:finance", "mch:pay:order:view", "mch:pay:refund"));
        assertThat(row.get("storeScope")).isEqualTo("STORES");
        assertThat(row.get("stores")).isEqualTo(List.of(
                Map.of("storeNo", "S-A", "perms", List.of("biz:finance", "mch:pay:order:view", "mch:pay:refund")),
                Map.of("storeNo", "S-B", "perms", List.of("mch:pay:order:view"))));
    }

    @Test
    @DisplayName("解析器回落到别的主体（门店不属于本人）：这张证照不出现")
    void resolverFallbackIsIgnored() {
        when(entities.myStores(staff)).thenReturn(List.of(group("E-SHOP", "S-A")));
        when(entities.myEntities("U-STAFF")).thenReturn(List.of());
        when(resolver.resolveAuthenticated(eq(staff), eq("S-A")))
                .thenReturn(new BizContext("E-OTHER", Set.of(), Set.of(), Set.of("S-X"), "S-X", true));

        assertThat(memberships(call(CALLER, "btk_staff", null))).isEmpty();
    }

    @Test
    @DisplayName("带了 X-Pay-Merchant-No 只回那一个商户")
    void requestedMerchantNarrows() {
        when(entities.myStores(owner)).thenReturn(List.of(group("E-OWN", "S-1")));
        when(entities.myEntities("U-OWNER")).thenReturn(List.of(entity("E-OWN"), entity("E-NOSTORE")));
        when(resolver.resolveAuthenticated(eq(owner), eq("S-1")))
                .thenReturn(new BizContext("E-OWN", Set.of(), Set.of(), Set.of("S-1"), "S-1", true));

        assertThat(memberships(call(CALLER, "btk_owner", "PM-NOSTORE")))
                .extracting(r -> r.get("payMerchantNo")).containsExactly("PM-NOSTORE");
    }

    @Test
    @DisplayName("near-pay 查关联失败：抛 503，不当成「没有商户」")
    void linkUnavailableIsNotEmpty() {
        when(entities.myStores(owner)).thenReturn(List.of());
        when(entities.myEntities("U-OWNER")).thenReturn(List.of(entity("E-DOWN")));
        when(links.status("UME", "E-DOWN")).thenThrow(mock(ServiceCallException.class));

        assertThatThrownBy(() -> call(CALLER, "btk_owner", null))
                .isInstanceOfSatisfying(PayBizException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private MerchantStaffIdentityEndpoint endpoint(String callerToken) {
        var beans = new StaticListableBeanFactory(Map.of(
                "entities", entities, "resolver", resolver, "links", links, "sync", sync));
        return new MerchantStaffIdentityEndpoint(callerToken, tokens,
                beans.getBeanProvider(MerchantEntityService.class), beans.getBeanProvider(BizIdentityResolver.class),
                beans.getBeanProvider(PaySourceLinkApi.class), beans.getBeanProvider(StoreSyncProperties.class));
    }

    private ResponseEntity<Map<String, Object>> call(String caller, String token, String merchant) {
        return endpoint.merchantStaff(caller, "Bearer " + token, NS, merchant);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> memberships(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("memberships");
    }

    private void linked(String entityNo, String merchant) {
        when(links.status("UME", entityNo)).thenReturn(new PaySourceLinkApi.Status("APPROVED", merchant, "VERIFIED", "APPROVED", null));
    }

    private static EntityVO entity(String entityNo) {
        return new EntityVO(entityNo, entityNo, "ACTIVE", true, "COMPANY", 0, false, true);
    }

    private static EntityStoresVO group(String entityNo, String... storeNos) {
        // 不用 when(...)：group() 常写在另一个 when(...) 的参数里，嵌套桩 Mockito 会拒
        List<StoreVO> stores = Arrays.stream(storeNos).map(no -> mock(StoreVO.class,
                (Answer<Object>) call -> "storeNo".equals(call.getMethod().getName())
                        ? no : Mockito.RETURNS_DEFAULTS.answer(call))).toList();
        return new EntityStoresVO(entity(entityNo), stores);
    }
}
