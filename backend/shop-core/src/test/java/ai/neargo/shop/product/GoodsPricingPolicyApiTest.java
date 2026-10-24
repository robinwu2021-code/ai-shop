package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.product.api.biz.BizGoodsPricingPolicyController;
import ai.neargo.shop.product.dto.GoodsPricingPolicyCommands.SaveReq;
import ai.neargo.shop.product.policy.GoodsPricingPolicyAccessPolicy;
import ai.neargo.shop.product.service.GoodsPricingPolicyService;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GoodsPricingPolicyApiTest {
    private AnnotationConfigApplicationContext context;
    private GoodsPricingPolicyService service;
    private StoreDirectoryPort stores;
    private BizGoodsPricingPolicyController api;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(GoodsPricingPolicyService.class); stores = context.getBean(StoreDirectoryPort.class);
        api = context.getBean(BizGoodsPricingPolicyController.class);
        card("E");
    }
    private void card(String entity) {
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                "S", "Store", entity, "", "ACTIVE", true, "", "", null, null, 0, 0)));
    }
    @AfterEach void clear() { context.close(); SecurityContextHolder.clearContext(); BizContext.clear(); }
    private void merchant(boolean owner, String permission) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACT", "B"), "", List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", owner,
                Map.of(), Map.of("S", Set.of(permission))));
    }
    @Test void stockReadersCannotChangeTaxClassificationWithoutMerchantOwnership() {
        merchant(false, BizPerms.STOCK); api.get("G", "A");
        verify(service).get(any(), eq("G"), eq("A"));
        assertThatThrownBy(() -> api.save("G", "A", new SaveReq(0, true, TaxClass.STANDARD), "K"))
                .isInstanceOf(BizException.class);
    }
    @Test void noPermissionAndChangedStoreOwnershipCannotReachProductService() {
        merchant(false, "OTHER");
        assertThatThrownBy(() -> api.get("G", "A")).isInstanceOf(AccessDeniedException.class);
        merchant(true, BizPerms.STOCK); card("OTHER");
        assertThatThrownBy(() -> api.save("G", "A", new SaveReq(0, true, TaxClass.STANDARD), "K"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(service);
    }
    @Test void merchantOwnerWritesCurrentServerScopeAndOriginalIdempotencyKey() {
        merchant(true, BizPerms.STOCK);
        var request = new SaveReq(0, false, TaxClass.EXEMPT);
        api.save("G", "A", request, "K");
        verify(service).save(any(), eq("G"), eq("A"), eq(request), eq("K"));
    }
    @Test void strictWireRejectsIdentityInjectionMissingFieldsAndCoercion() throws Exception {
        merchant(true, BizPerms.STOCK);
        var mvc = MockMvcBuilders.standaloneSetup(api).build();
        String valid = "{\"expectedVersion\":0,\"promotionEligible\":true,\"taxClass\":\"STANDARD\"}";
        String path = "/biz/goods/G/skus/A/pricing-policy";
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isBadRequest());
        for (String invalid : List.of(valid.replace("0,", "0.1,"), valid.replace("0,", "\"0\","),
                valid.replace("\"expectedVersion\":0,", ""), valid.replace("true", "\"true\""),
                valid.replace("true", "1"), valid.replace("true", "null"), valid.replace("STANDARD", "UNKNOWN"),
                valid.replace("}", ",\"entityNo\":\"FOREIGN\"}"))) {
            mvc.perform(put(path).header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                    .content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(put(path).header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON).content(valid))
                .andExpect(status().isOk());
        verify(service).save(any(), eq("G"), eq("A"), any(), eq("K"));
    }
    public static class Permissions {
        public boolean canBiz(String permission) { return BizContext.current().can(permission); }
    }
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean(name = "perm") public Permissions permissions() { return new Permissions(); }
        @Bean public GoodsPricingPolicyService service() { return mock(GoodsPricingPolicyService.class); }
        @Bean public StoreDirectoryPort stores() { return mock(StoreDirectoryPort.class); }
        @Bean public GoodsPricingPolicyAccessPolicy access(StoreDirectoryPort stores) {
            return new GoodsPricingPolicyAccessPolicy(stores);
        }
        @Bean public BizGoodsPricingPolicyController api(GoodsPricingPolicyService service,
                GoodsPricingPolicyAccessPolicy access) { return new BizGoodsPricingPolicyController(service, access); }
    }
}
