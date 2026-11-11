package ai.neargo.shop.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.product.api.biz.BizRetailStockReplenishmentController;
import ai.neargo.shop.product.policy.LocalStockAccessPolicy;
import ai.neargo.shop.product.service.RetailStockReplenishmentService;
import ai.neargo.shop.product.service.StockReplenishmentAdmissionService;
import ai.neargo.shop.spi.product.LocalStockControlPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.math.BigDecimal;
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

/** Public RETAIL entry: real permission/scope policy and MVC binding; service is a seam. */
class RetailStockReplenishmentApiTest {
    private static final Scope S = new Scope("MAIN", "E", "S");
    private static final String BASE = "/biz/stock-control/retail-replenishments";
    private static final String WIRE = """
            {"documentNo":"OUT","lineNo":1,"mapping":{"itemNo":"SKU","baseUnit":"EA","factor":1}}
            """;
    private AnnotationConfigApplicationContext context;
    private RetailStockReplenishmentService service;
    private StoreDirectoryPort stores;
    private BizRetailStockReplenishmentController api;

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(RetailStockReplenishmentService.class); stores = context.getBean(StoreDirectoryPort.class);
        api = context.getBean(BizRetailStockReplenishmentController.class);
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

    @Test void stockPermissionTrustedStoreAndOriginalEntityAreRequired() {
        merchant("OTHER");
        assertThatThrownBy(() -> api.get(null, null, "SRP")).isInstanceOf(AccessDeniedException.class);
        merchant(BizPerms.STOCK);
        api.get("S", "E", "SRP");
        verify(service).get(S, "SRP");
        clearInvocations(service);
        assertThatThrownBy(() -> api.get("FOREIGN", null, "SRP")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> api.get("S", "OLD_ENTITY", "SRP")).isInstanceOf(BizException.class);
        verifyNoInteractions(service);
    }

    @Test void contextReportsServerResolvedScopeAndActor() {
        merchant(BizPerms.STOCK);
        when(service.connection(S)).thenReturn(new StockReplenishmentAdmissionService.Connection(true, 1, 2, "INV", "OWNER", 10));
        var context = api.context("S", null);
        assertThat(context.entityNo()).isEqualTo("E");
        assertThat(context.storeNo()).isEqualTo("S");
        assertThat(context.actorNo()).isEqualTo("ACT");
        assertThat(context.connection().enabled()).isTrue();
    }

    @Test void mvcRejectsInjectedOwnersFractionsAndMissingKeysBeforeTheService() throws Exception {
        merchant(BizPerms.STOCK);
        var mvc = MockMvcBuilders.standaloneSetup(api).build();
        for (String bad : List.of(WIRE.replace("\"lineNo\":1", "\"lineNo\":0"),
                WIRE.replace("\"factor\":1", "\"factor\":0"),
                WIRE.replace("\"documentNo\":\"OUT\"", "\"targetOwner\":\"FOOD_LOCAL\",\"documentNo\":\"OUT\"")))
            mvc.perform(post(BASE).header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(WIRE)).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/SRP/receive").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cumulativeQuantity\":1.5}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/SRP/receive").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cumulativeQuantity\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/SRP/return-dispatch").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cumulativeQuantity\":2147483648,\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemNo\":\"SKU\",\"baseUnit\":\"EA\",\"delta\":0.5,\"reason\":\"count\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "K").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemNo\":\"SKU\",\"baseUnit\":\"EA\",\"delta\":3,\"reason\":\"count\",\"target\":{\"storeNo\":\"X\"}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void mvcPassesOnlyOriginalNumbersAndAuthenticatedActor() throws Exception {
        merchant(BizPerms.STOCK);
        var mvc = MockMvcBuilders.standaloneSetup(api).build();
        mvc.perform(post(BASE).header("Idempotency-Key", "REG").header("X-Stock-Entity-No", "E")
                .contentType(MediaType.APPLICATION_JSON).content(WIRE)).andExpect(status().isOk());
        verify(service).register(S, "ACT", "REG", "OUT", 1,
                new StockReplenishmentAdmissionService.Mapping("SKU", "EA", BigDecimal.ONE));
        mvc.perform(post(BASE + "/SRP/receive").header("Idempotency-Key", "R").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cumulativeQuantity\":18}")).andExpect(status().isOk());
        verify(service).receive(S, "ACT", "R", "SRP", 18);
        mvc.perform(post(BASE + "/SRP/return-dispatch").header("Idempotency-Key", "B").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cumulativeQuantity\":2,\"reason\":\"damaged\"}")).andExpect(status().isOk());
        verify(service).dispatchReturn(S, "ACT", "B", "SRP", 2, "damaged");
        mvc.perform(post(BASE + "/SRP/return-ack").header("Idempotency-Key", "A").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentNo\":\"BACK\",\"lineNo\":1}")).andExpect(status().isOk());
        verify(service).ackReturn(S, "ACT", "A", "SRP", "BACK", 1);
        mvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "J").contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemNo\":\"SKU\",\"baseUnit\":\"EA\",\"delta\":-2,\"reason\":\"loss\"}")).andExpect(status().isOk());
        verify(service).adjust(S, "ACT", "J", "SKU", "EA", -2, "loss");
        mvc.perform(post(BASE + "/SRP/manual-receipt").header("Idempotency-Key", "M").contentType(MediaType.APPLICATION_JSON)
                .content("{\"movementNo\":\"SRM1\"}")).andExpect(status().isOk());
        verify(service).adoptManual(S, "ACT", "M", "SRP", "SRM1");
        mvc.perform(post(BASE + "/connection").header("Idempotency-Key", "C").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"expectedVersion\":0,\"reason\":\"start\"}")).andExpect(status().isOk());
        verify(service).configure(S, "ACT", "C", new StockReplenishmentAdmissionService.Change(true, 0, "start"));
        mvc.perform(get(BASE + "/SRP/reconciliation")).andExpect(status().isOk());
        verify(service).reconcile(S, "ACT", "SRP");
        mvc.perform(get(BASE).param("after", "SRP").param("size", "10")).andExpect(status().isOk());
        verify(service).list(S, "SRP", 10, true);
    }

    public static class Permissions { public boolean canBiz(String permission) { return BizContext.current().can(permission); } }
    @Configuration @EnableMethodSecurity
    static class Config {
        @Bean(name="perm") public Permissions permissions() { return new Permissions(); }
        @Bean public StoreDirectoryPort stores() { return mock(StoreDirectoryPort.class); }
        @Bean public RetailStockReplenishmentService service() { return mock(RetailStockReplenishmentService.class); }
        @Bean public LocalStockAccessPolicy access(StoreDirectoryPort stores) { return new LocalStockAccessPolicy(stores); }
        @Bean public BizRetailStockReplenishmentController api(RetailStockReplenishmentService service, LocalStockAccessPolicy access) {
            return new BizRetailStockReplenishmentController(service, access);
        }
    }
}
