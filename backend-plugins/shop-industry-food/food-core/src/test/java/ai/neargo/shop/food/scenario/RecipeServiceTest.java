package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateRecipeReq;
import ai.neargo.shop.food.dto.ListRecipesQuery;
import ai.neargo.shop.food.dto.RecipeLineInput;
import ai.neargo.shop.food.dto.ReplaceRecipeDefinitionReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.impl.RecipeServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real primary tables and atomic outbox verify editable recipes without any stock command. */
class RecipeServiceTest extends FoodWriteGuardFixture {
    private void run(BiConsumer<AssertableApplicationContext, Fixture> action) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Recipes.class).run(c -> {
            var f = prepare(c);
            StorePolicyPublicationTest.createPolicyTables(f);
            if ("true".equals(System.getenv("FOOD_TEST_DISPOSABLE"))) {
                new ResourceDatabasePopulator(new ClassPathResource(
                        "db/industry/food/V4__recipe_draft_tables.sql")).execute(f.source());
            } else {
                String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                        + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                        + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
                f.sql().execute("CREATE TABLE fnb_recipe (" + common + "recipe_no VARCHAR(32) UNIQUE,"
                        + "store_no VARCHAR(32),sku_no VARCHAR(32),recipe_code VARCHAR(32),yield_qty DECIMAL(18,6),"
                        + "yield_unit VARCHAR(16),config_version BIGINT,published_revision_no VARCHAR(32),"
                        + "status VARCHAR(16),UNIQUE(store_no,sku_no,recipe_code))");
                f.sql().execute("CREATE TABLE fnb_recipe_line (" + common + "line_no VARCHAR(32) UNIQUE,"
                        + "recipe_no VARCHAR(32),item_ref VARCHAR(32),qty DECIMAL(18,6),unit VARCHAR(16),"
                        + "conversion_ref VARCHAR(64),material_definition_hash VARCHAR(64),"
                        + "loss_rate DECIMAL(12,6),sort_no INT,UNIQUE(recipe_no,line_no))");
            }
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_RECIPE_CHANGED"));
            action.accept(c, f);
        });
    }
    static BigDecimal n(String v) { return new BigDecimal(v); }
    static CreateRecipeReq create(String sku, String code) { return new CreateRecipeReq(sku, code, n("1"), "EA"); }
    static RecipeLineInput line(String id, String item, String qty, String unit) {
        return new RecipeLineInput(id, item, n(qty), unit, n("0.05"));
    }
    static ReplaceRecipeDefinitionReq definition(long version, RecipeLineInput... lines) {
        return new ReplaceRecipeDefinitionReq(version, n("1.00"), "EA", List.of(lines));
    }
    static void code(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test void createsAndEditsExactBaseUnitLinesWithGeneratedIdentitiesAndRepeatedMaterials() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var first = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe();
            assertThat(first.recipeNo()).isNotBlank().hasSizeLessThanOrEqualTo(32);
            assertThat(first.lines()).isEmpty();
            var result = s.replaceDefinition("E", "A", first.recipeNo(), "actor", definition(0,
                    line(null, "MILK", "0.125000", "KG"), line(null, "MILK", "0.010", "KG")), "edit").recipe();
            assertThat(result.version()).isEqualTo(1);
            assertThat(result.configVersion()).isEqualTo(1);
            assertThat(result.lines()).hasSize(2);
            assertThat(result.lines()).extracting(v -> v.lineNo()).doesNotHaveDuplicates();
            assertThat(result.lines().getFirst().qty()).isEqualByComparingTo("0.125");
            assertThat(result.lines().getFirst().materialDefinitionHash()).isEqualTo(
                    MaterialCatalogFacts.definitionHash("MILK", "E", "牛奶", "KG", 1, "OWN"));
            assertThat(s.getRecipe("E", "A", first.recipeNo()).recipe()).isEqualTo(result);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line WHERE conversion_ref IS NOT NULL",
                    Integer.class)).isZero();
            for (String table : List.of("sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                        .as(table).isEqualTo(2);
            }
        });
    }

    @Test void rejectsForeignMissingMaterialWrongUnitsPrecisionAndInventedLineKeysWithoutChangingDraft() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var id = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe().recipeNo();
            for (var bad : List.of(line(null, "MISSING", "1", "KG"), line(null, "MILK", "1", "G"),
                    line(null, "MILK", "0.0000001", "KG"), line(null, "MILK", "1000000000000", "KG"),
                    line(null, "MILK", "0", "KG"))) {
                code(() -> s.replaceDefinition("E", "A", id, "actor", definition(0, bad), "bad"),
                        ErrorCode.BAD_REQUEST);
            }
            code(() -> s.replaceDefinition("E", "A", id, "actor", definition(0,
                    line("invented", "MILK", "1", "KG")), "bad-id"), ErrorCode.CONFLICT);
            assertThat(s.getRecipe("E", "A", id).recipe().version()).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isOne();
        });
    }

    @Test void scopesEveryHeadAndPageToEntityStoreAndUsesSameFiltersForTotals() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var a = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "a").recipe();
            s.createRecipe("E", "A", "actor", create("TEA", "NO_MILK"), "b");
            s.createRecipe("E", "A", "actor", create("COFFEE", "DEFAULT"), "c");
            s.createRecipe("E", "B", "actor", create("TEA", "DEFAULT"), "d");
            code(() -> s.getRecipe("E", "B", a.recipeNo()), ErrorCode.NOT_FOUND);
            code(() -> s.getRecipe("OTHER", "A", a.recipeNo()), ErrorCode.NOT_FOUND);
            var page = s.listRecipes("E", "A", new ListRecipesQuery(2, 1, "TEA", "DRAFT"));
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.records()).hasSize(1);
            assertThat(s.listRecipes("E", "A", new ListRecipesQuery(1, 20, null, "ACTIVE")).total()).isZero();
            code(() -> s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "dup"), ErrorCode.CONFLICT);
        });
    }

    @Test void completedReceiptsReplayWithoutAuthorityAndStaleOrChangedCommandsConflict() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            var first = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create");
            var req = definition(0, line(null, "MILK", "0.1", "KG"));
            var saved = s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "edit");
            state.outage.set(true);
            assertThat(s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")).isEqualTo(first);
            assertThat(s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "edit")).isEqualTo(saved);
            assertThat(s.getRecipe("E", "A", first.recipe().recipeNo())).isEqualTo(saved);
            state.outage.set(false);
            code(() -> s.replaceDefinition("E", "A", first.recipe().recipeNo(), "actor", req, "stale"),
                    ErrorCode.CONFLICT);
            code(() -> s.createRecipe("E", "A", "actor", create("OTHER", "DEFAULT"), "create"), ErrorCode.CONFLICT);
        });
    }

    @Test void outboxFailureRollsBackHeadLinesAuditAndIdempotencyAndAllowsSameKeyRetry() {
        run((c, f) -> {
            var s = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            state.fail.set(true);
            assertThatThrownBy(() -> s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create"))
                    .isInstanceOf(IllegalStateException.class);
            for (String table : List.of("fnb_recipe", "sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
            }
            state.fail.set(false);
            var head = s.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create").recipe();
            state.fail.set(true);
            assertThatThrownBy(() -> s.replaceDefinition("E", "A", head.recipeNo(), "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "edit")).isInstanceOf(IllegalStateException.class);
            assertThat(s.getRecipe("E", "A", head.recipeNo()).recipe()).isEqualTo(head);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
        });
    }

    @Test void invalidOriginalFactsAndAuthorityOutagesCannotWriteOrBecomeEmptySuccess() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var state = c.getBean(State.class);
            state.badSku.set(true);
            assertThatThrownBy(() -> service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "bad"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe", Integer.class)).isZero();
            state.badSku.set(false);
            var original = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create");
            state.badMaterial.set(true);
            assertThatThrownBy(() -> service.replaceDefinition("E", "A", original.recipe().recipeNo(), "actor",
                    definition(0, line(null, "MILK", "1", "KG")), "bad-material"))
                    .isInstanceOf(IllegalStateException.class);
            state.outage.set(true);
            assertThatThrownBy(() -> service.createRecipe("E", "A", "actor", create("TEA", "OTHER"), "offline"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.getRecipe("E", "A", original.recipe().recipeNo())).isEqualTo(original);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_recipe_line", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isOne();
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void nativeConcurrentDefinitionUpdatesHaveOneWinnerAndOneConflict() {
        run((c, f) -> {
            var service = c.getBean(RecipeService.class);
            var id = service.createRecipe("E", "A", "actor", create("TEA", "DEFAULT"), "create")
                    .recipe().recipeNo();
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var tasks = List.of("1", "2").stream().map(qty -> workers.submit(() -> {
                    start.await();
                    try {
                        service.replaceDefinition("E", "A", id, "actor", definition(0,
                                line(null, "MILK", qty, "KG")), "race-" + qty);
                        return "OK";
                    } catch (BizException failure) { return failure.errorCode().name(); }
                })).toList();
                start.countDown();
                assertThat(List.of(tasks.get(0).get(20, TimeUnit.SECONDS), tasks.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("OK", "CONFLICT");
            } catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(service.getRecipe("E", "A", id).recipe().version()).isEqualTo(1);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
        });
    }

    static class State {
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicBoolean badSku = new AtomicBoolean();
        final AtomicBoolean badMaterial = new AtomicBoolean();
        final AtomicBoolean outage = new AtomicBoolean();
    }
    @Configuration(proxyBeanMethods=false)
    static class Recipes {
        @Bean State recipeState() { return new State(); }
        @Bean MapperFactoryBean<RecipeMapper> recipeHeads(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(RecipeMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean MapperFactoryBean<RecipeLineMapper> recipeLines(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var b = new MapperFactoryBean<>(RecipeLineMapper.class); b.setSqlSessionTemplate(t); return b;
        }
        @Bean RecipeService recipeService(RecipeMapper heads, RecipeLineMapper lines, ActionLogMapper actions,
                IdempotencyService idem, @Qualifier("foodOutboxPublisher") OutboxPublisher outbox,
                ObjectMapper json, PlatformTransactionManager manager, State state) {
            var catalog = mock(GoodsCatalogQueryPort.class);
            when(catalog.getSku(anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (state.outage.get()) throw new IllegalStateException("authority unavailable");
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2), "G",
                        state.badSku.get() ? "OTHER" : i.getArgument(0),
                        i.getArgument(1), 0);
            });
            MaterialCatalogQueryPort materials = (entity, ids) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (state.outage.get()) throw new IllegalStateException("inventory unavailable");
                String owner = state.badMaterial.get() ? "OTHER" : entity;
                return ids.contains("MILK") ? List.of(new MaterialCatalogQueryPort.MaterialFact("MILK", owner,
                        "牛奶", "KG", 1, "OWN", "BASE_ONLY", "INTEGER_ONLY",
                        MaterialCatalogFacts.definitionHash("MILK", owner, "牛奶", "KG", 1, "OWN"))) : List.of();
            };
            OutboxPublisher checked = (event, options) -> {
                var receipt = outbox.publish(event, options);
                if (state.fail.get()) throw new IllegalStateException("after-recipe-outbox");
                return receipt;
            };
            return new RecipeServiceImpl(heads, lines, actions, idem, checked, json, manager, catalog, materials);
        }
    }
}
