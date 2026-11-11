package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.api.biz.BizLocalStockController;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.LocalStockControlService;
import ai.neargo.shop.spi.product.LocalStockControlPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalStockPreflightApiTest {
    private AnnotationConfigApplicationContext context;
    private LocalStockControlService service;
    private StoreDirectoryPort stores;
    private BizLocalStockController api;
    private static final String WIRE = """
            {"industry":"RETAIL","strategy":"LOCAL","itemKind":"SKU","itemNo":"SKU1",
             "baseUnit":"EA","quantity":1,"actions":["RESERVE"]}
            """;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(LocalStockControlService.class); stores = context.getBean(StoreDirectoryPort.class);
        api = context.getBean(BizLocalStockController.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                "S", "Store", "E", "", "ACTIVE", true, "", "", null, null, 0, 0)));
    }
    @AfterEach void cleanup() { context.close(); SecurityContextHolder.clearContext(); BizContext.clear(); }
    private void merchant(String permission) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                LoginUser.merchant("ACT", "B"), "", List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false,
                Map.of(), Map.of("S", Set.of(permission))));
    }
    @Test void actualAuthenticatedScopeAndStockPermissionAreRequired() {
        merchant("OTHER");
        assertThatThrownBy(() -> api.preflight(request(), null)).isInstanceOf(AccessDeniedException.class);
        merchant(BizPerms.STOCK);
        api.preflight(request(), "S");
        verify(service).preflight(new LocalStockControlPort.Scope("MAIN", "E", "S"), request());
    }
    @Test void foreignHeaderAndChangedStoreOwnershipDoNotReachInventoryFacts() {
        merchant(BizPerms.STOCK);
        assertThatThrownBy(() -> api.preflight(request(), "FOREIGN")).isInstanceOf(BizException.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard(
                "S", "Store", "FOREIGN", "", "ACTIVE", true, "", "", null, null, 0, 0)));
        assertThatThrownBy(() -> api.preflight(request(), "S")).isInstanceOf(BizException.class);
        verifyNoInteractions(service);
    }
    @Test void mvcValidatesOnceAndRejectsInjectedIdentityAndMalformedQuantity() throws Exception {
        merchant(BizPerms.STOCK); var mvc = MockMvcBuilders.standaloneSetup(api).build();
        for (String bad : List.of(WIRE.replace("\"quantity\":1", "\"quantity\":0"),
                WIRE.replace("\"quantity\":1", "\"quantity\":0.0000001"),
                WIRE.replace("\"industry\":\"RETAIL\",", ""),
                WIRE.replace("[\"RESERVE\"]", "[]"),
                WIRE.replace("\"quantity\":1,", "\"quantity\":1,\"entityNo\":\"FOREIGN\","))) {
            mvc.perform(post("/biz/stock-control/preflight").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/biz/stock-control/preflight").contentType(MediaType.APPLICATION_JSON).content(WIRE))
                .andExpect(status().isOk());
        verify(service).preflight(any(), any());
    }
    private LocalStockControlPort.Request request() {
        return LocalStockControlServiceTest.request(LocalStockControlPort.Industry.RETAIL,
                LocalStockControlPort.Strategy.LOCAL, LocalStockControlPort.ItemKind.SKU, "1", LocalStockControlPort.Action.RESERVE);
    }
    public static class Permissions { public boolean canBiz(String permission) { return BizContext.current().can(permission); } }
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean(name="perm") public Permissions permissions() { return new Permissions(); }
        @Bean public StoreDirectoryPort stores() { return mock(StoreDirectoryPort.class); }
        @Bean public LocalStockControlService service() { return mock(LocalStockControlService.class); }
        @Bean public LocalStockAccessPolicy access(StoreDirectoryPort stores) { return new LocalStockAccessPolicy(stores); }
        @Bean public BizLocalStockController api(LocalStockControlService service, LocalStockAccessPolicy access) {
            return new BizLocalStockController(service, access);
        }
    }
}
