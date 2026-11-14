package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.api.biz.BizStockReplenishmentController;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.StockReplenishmentService;
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

class StockReplenishmentApiTest {
    private AnnotationConfigApplicationContext context;
    private StockReplenishmentService service;
    private StoreDirectoryPort stores;
    private BizStockReplenishmentController api;
    private static final String WIRE = """
            {"documentNo":"OUT","lineNo":1,"mapping":{"itemNo":"RAW","baseUnit":"G","factor":1}}
            """;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(StockReplenishmentService.class); stores = context.getBean(StoreDirectoryPort.class);
        api = context.getBean(BizStockReplenishmentController.class);
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
    @Test void stockPermissionAndTrustedStoreScopeAreRequired() {
        merchant("OTHER");
        assertThatThrownBy(() -> api.get(null,null,"R")).isInstanceOf(AccessDeniedException.class);
        merchant(BizPerms.STOCK);api.get("S",null,"R");
        verify(service).get(new LocalStockControlPort.Scope("MAIN","E","S"),"R");
        clearInvocations(service);
        assertThatThrownBy(() -> api.get("FOREIGN",null,"R")).isInstanceOf(BizException.class);
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S",new StoreDirectoryPort.StoreCard("S","Store","FOREIGN","","ACTIVE",true,"","",null,null,0,0)));
        assertThatThrownBy(() -> api.get("S",null,"R")).isInstanceOf(BizException.class);verifyNoInteractions(service);
    }
    @Test void mvcRejectsIdentityInjectionMissingKeysAndUnsupportedQuantities() throws Exception {
        merchant(BizPerms.STOCK);var mvc=MockMvcBuilders.standaloneSetup(api).build();
        for(String bad:List.of(WIRE.replace("\"lineNo\":1","\"lineNo\":0"),
                WIRE.replace("\"factor\":1","\"factor\":0"),WIRE.replace("\"documentNo\":\"OUT\"","\"entityNo\":\"FOREIGN\",\"documentNo\":\"OUT\"")))
            mvc.perform(post("/biz/stock-control/replenishments").header("Idempotency-Key","K").contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        mvc.perform(post("/biz/stock-control/replenishments").contentType(MediaType.APPLICATION_JSON).content(WIRE)).andExpect(status().isBadRequest());
        mvc.perform(post("/biz/stock-control/replenishments/R/receive").header("Idempotency-Key","K").contentType(MediaType.APPLICATION_JSON).content("{\"cumulativeQuantity\":0.0000001}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        mvc.perform(post("/biz/stock-control/replenishments").header("Idempotency-Key","K").contentType(MediaType.APPLICATION_JSON).content(WIRE)).andExpect(status().isOk());
        verify(service).register(eq(new LocalStockControlPort.Scope("MAIN","E","S")),eq("ACT"),eq("K"),eq("OUT"),eq(1),any());
    }
    @Test void staleExpectedEntityCannotApplyAnOldAppIntentAfterStoreOwnershipChanges(){
        merchant(BizPerms.STOCK);assertThatThrownBy(()->api.get("S","OLD_ENTITY","R")).isInstanceOf(BizException.class);verifyNoInteractions(service);
    }
    @Test void decimalTextFromAppAndConnectionInputsUseFrameworkValidation()throws Exception {
        merchant(BizPerms.STOCK);var mvc=MockMvcBuilders.standaloneSetup(api).build();
        mvc.perform(post("/biz/stock-control/replenishments/R/receive").header("Idempotency-Key","K").header("X-Stock-Entity-No","E")
                .contentType(MediaType.APPLICATION_JSON).content("{\"cumulativeQuantity\":\"0.125000\"}")).andExpect(status().isOk());
        verify(service).receive(any(),eq("ACT"),eq("K"),eq("R"),eq(new java.math.BigDecimal("0.125000")));
        mvc.perform(post("/biz/stock-control/replenishments/connection").header("Idempotency-Key","C").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"on\"}")).andExpect(status().isBadRequest());
    }
    @Test void cancellationUsesAuthenticatedActorAndValidatedOriginalKey()throws Exception {
        merchant(BizPerms.STOCK);var mvc=MockMvcBuilders.standaloneSetup(api).build();
        mvc.perform(post("/biz/stock-control/replenishments/commands/REPLENISH_CONFIG/cancel").header("X-Stock-Entity-No","E")
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"OLD\",\"reason\":\"stop\"}")).andExpect(status().isOk());
        verify(service).cancelCommand(new LocalStockControlPort.Scope("MAIN","E","S"),"ACT","REPLENISH_CONFIG","OLD","stop");
        mvc.perform(post("/biz/stock-control/replenishments/commands/REPLENISH_CONFIG/cancel")
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"\",\"reason\":\"stop\"}")).andExpect(status().isBadRequest());
    }
    public static class Permissions { public boolean canBiz(String permission) { return BizContext.current().can(permission); } }
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean(name="perm") public Permissions permissions() { return new Permissions(); }
        @Bean public StoreDirectoryPort stores() { return mock(StoreDirectoryPort.class); }
        @Bean public StockReplenishmentService service() { return mock(StockReplenishmentService.class); }
        @Bean public LocalStockAccessPolicy access(StoreDirectoryPort stores) { return new LocalStockAccessPolicy(stores); }
        @Bean public BizStockReplenishmentController api(StockReplenishmentService service, LocalStockAccessPolicy access) {
            return new BizStockReplenishmentController(service, access);
        }
    }
}
