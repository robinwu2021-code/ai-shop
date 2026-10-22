package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeAdjustmentInput;
import ai.neargo.shop.food.dto.RecipeAdjustmentVO;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.RecipeLineVO;
import ai.neargo.shop.food.dto.RecipeResultVO;
import ai.neargo.shop.food.dto.RecipeVO;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.foodclient.RemoteRecipeService;
import ai.neargo.shop.food.spi.RecipeInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RemoteRecipeServiceTest {
    static RecipeVO view(String store, String sku) {
        return new RecipeVO("R1", store, sku, "DEFAULT", BigDecimal.ONE, "EA", "DRAFT", 0, 0, List.of());
    }
    @Test void requiresMatchedCreatedSkuAndStoreAndRejectsMalformedPageScopeAndFilters() {
        var api = mock(RecipeInternalApi.class);
        var s = new RemoteRecipeService(api);
        var req = new CreateRecipeReq("TEA", "DEFAULT", BigDecimal.ONE, "EA");
        when(api.createRecipe(any())).thenReturn(new RecipeResultVO(view("A", "TEA")));
        assertThat(s.createRecipe("E", "A", "actor", req, "key").recipe().recipeNo()).isEqualTo("R1");
        when(api.createRecipe(any())).thenReturn(new RecipeResultVO(view("B", "TEA")));
        assertThatThrownBy(() -> s.createRecipe("E", "A", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        when(api.listRecipes(any())).thenReturn(
                new RecipeInternalApi.PageReceipt(List.of(view("A", "OTHER")), 1L, 1L, 20L));
        assertThatThrownBy(() -> s.listRecipes("E", "A", new ListRecipesQuery(1, 20, "TEA", null)))
                .isInstanceOf(IllegalStateException.class);
        when(api.getRecipe(any())).thenReturn(new RecipeResultVO(view("B", "TEA")));
        assertThatThrownBy(() -> s.getRecipe("E", "A", "R1")).isInstanceOf(IllegalStateException.class);
    }
    @Test void wireScopesAndPaginationNeverCoerceNonTextOrFractionalTokens() {
        var json = new ObjectMapper();
        String normal = json.writeValueAsString(new RecipeInternalApi.CreateCommand("E", "A", "actor", "key",
                new CreateRecipeReq("TEA", "DEFAULT", BigDecimal.ONE, "EA")));
        for (String field : List.of("entityNo", "storeNo", "actorNo", "idempotencyKey")) {
            String bad = normal.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":123");
            assertThatThrownBy(() -> json.readValue(bad, RecipeInternalApi.CreateCommand.class))
                    .isInstanceOf(RuntimeException.class);
        }
        for (String bad : List.of("1.5", "\"1\"", "2147483648")) {
            String input = "{\"entityNo\":\"E\",\"storeNo\":\"A\",\"query\":{\"page\":" + bad + "}}";
            assertThatThrownBy(() -> json.readValue(input, RecipeInternalApi.Lookup.class))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test void responsePageCountersRequirePresentExactNonnegativeIntegerTokens() throws Exception {
        var json = new ObjectMapper();
        var type = RecipeInternalApi.class.getMethod("listRecipes", RecipeInternalApi.Lookup.class).getReturnType();
        var api = mock(RecipeInternalApi.class);
        var service = new RemoteRecipeService(api);
        String normal = "{\"records\":[],\"total\":0,\"page\":1,\"size\":20}";
        doAnswer(i -> json.readValue(normal, type)).when(api).listRecipes(any());
        assertThat(service.listRecipes("E", "A", null).total()).isZero();
        for (String field : List.of("total", "page", "size")) {
            for (String bad : List.of("0.9", "\"1\"", "null", "-1", "9223372036854775808")) {
                String input = normal.replaceFirst("\"" + field + "\":\\d+", "\"" + field + "\":" + bad);
                doAnswer(i -> json.readValue(input, type)).when(api).listRecipes(any());
                assertThatThrownBy(() -> service.listRecipes("E", "A", null))
                        .as("response %s=%s", field, bad).isInstanceOf(RuntimeException.class);
            }
            String input = normal.replaceFirst(",\"" + field + "\":\\d+", "");
            doAnswer(i -> json.readValue(input, type)).when(api).listRecipes(any());
            assertThatThrownBy(() -> service.listRecipes("E", "A", null))
                    .as("missing response %s", field).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void mutationReceiptsCannotDropRebindOrChangeAdjustmentsAndCopyThemToTheCaller() {
        var api = mock(RecipeInternalApi.class);
        var service = new RemoteRecipeService(api);
        var line = new RecipeLineVO("L1", "MILK", BigDecimal.ONE, "KG", BigDecimal.ZERO, "a".repeat(64));
        var adjustment = new RecipeAdjustmentVO("A1", "NO_SUGAR", "L1", "SET",
                BigDecimal.ZERO, "KG", "b".repeat(64));
        var req = new ReplaceRecipeDefinitionReq(0L, BigDecimal.ONE, "EA",
                List.of(new RecipeLineInput("L1", "MILK", BigDecimal.ONE, "KG", BigDecimal.ZERO)),
                List.of(new RecipeAdjustmentInput(null, "NO_SUGAR", "L1", "SET", BigDecimal.ZERO, "KG")));
        var valid = new RecipeVO("R1", "A", "TEA", "DEFAULT", BigDecimal.ONE, "EA", "DRAFT", 1, 1,
                List.of(line), List.of(adjustment));
        when(api.replaceDefinition(any())).thenReturn(new RecipeResultVO(new RecipeVO("R1", "A", "TEA",
                "DEFAULT", BigDecimal.ONE, "EA", "DRAFT", 1, 1, List.of(line))));
        assertThatThrownBy(() -> service.replaceDefinition("E", "A", "R1", "actor", req, "key"))
                .isInstanceOf(IllegalStateException.class);
        for (var bad : List.of(new RecipeAdjustmentVO("A1", "OTHER", "L1", "SET",
                        BigDecimal.ZERO, "KG", "b".repeat(64)),
                new RecipeAdjustmentVO("A1", "NO_SUGAR", "OTHER", "SET", BigDecimal.ZERO, "KG", "b".repeat(64)),
                new RecipeAdjustmentVO("A1", "NO_SUGAR", "L1", "SET", BigDecimal.ONE, "KG", "b".repeat(64)),
                new RecipeAdjustmentVO("A1", "NO_SUGAR", "L1", "REMOVE", BigDecimal.ONE, "KG", "b".repeat(64)),
                new RecipeAdjustmentVO("A1", "NO_SUGAR", "L1", "SET", BigDecimal.ZERO, "KG", "invalid"))) {
            when(api.replaceDefinition(any())).thenReturn(new RecipeResultVO(new RecipeVO("R1", "A", "TEA",
                    "DEFAULT", BigDecimal.ONE, "EA", "DRAFT", 1, 1, List.of(line), List.of(bad))));
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", "R1", "actor", req, "key"))
                    .isInstanceOf(IllegalStateException.class);
        }
        when(api.replaceDefinition(any())).thenReturn(new RecipeResultVO(valid));
        assertThat(service.replaceDefinition("E", "A", "R1", "actor", req, "key").recipe().adjustments())
                .containsExactly(adjustment);
    }

    @Test void additiveAdjustmentContractPreservesHistoricalFingerprintsAndStrictText() {
        var json = new ObjectMapper();
        var old = new ReplaceRecipeDefinitionReq(0L, BigDecimal.ONE, "EA", List.of());
        assertThat(json.writeValueAsString(old)).isEqualTo(
                "{\"version\":0,\"yieldQty\":1,\"yieldUnit\":\"EA\",\"lines\":[]}");
        String normal = "{\"optionItemNo\":\"O\",\"targetLineNo\":\"L\",\"action\":\"SET\",\"qty\":0,\"unit\":\"KG\"}";
        for (String field : List.of("optionItemNo", "targetLineNo", "action", "unit")) {
            String bad = normal.replaceFirst("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":123");
            assertThatThrownBy(() -> json.readValue(bad, RecipeAdjustmentInput.class))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> json.readValue(normal.replace("{", "{\"entityNo\":\"OTHER\","),
                RecipeAdjustmentInput.class)).isInstanceOf(RuntimeException.class);
        String legacy = json.writeValueAsString(view("A", "TEA")).replace(",\"adjustments\":[]", "");
        assertThat(json.readValue(legacy, RecipeVO.class).adjustments()).isEmpty();
    }

    @Test void businessErrorsAndTransportOutagesKeepOneAttemptWithoutEmptySuccess() {
        for (int status : List.of(400, 403, 404, 409, 500, 0)) {
            var calls = new AtomicInteger();
            var api = mock(RecipeInternalApi.class);
            when(api.getRecipe(any())).thenAnswer(i -> {
                calls.incrementAndGet();
                throw new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.FOOD, status, "safe");
            });
            var s = new RemoteRecipeService(api);
            assertThatThrownBy(() -> s.getRecipe("E", "A", "R1"))
                    .isInstanceOf(status >= 400 && status < 500 ? BizException.class : ServiceCallException.class);
            assertThat(calls).hasValue(1);
        }
    }
}
