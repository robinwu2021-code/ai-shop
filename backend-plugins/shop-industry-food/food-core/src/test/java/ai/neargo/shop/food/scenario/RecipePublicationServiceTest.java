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
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeAdjustmentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeLineMapper;
import ai.neargo.shop.food.mapper.FoodMappers.RecipeMapper;
import ai.neargo.shop.food.policy.RecipePublicationSource;
import ai.neargo.shop.food.service.RecipeService;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Uses the existing draft write path, real primary Mappers, idempotency and Outbox with controllable authorities. */
class RecipePublicationServiceTest {
    static final OffsetDateTime NOW = ConfigRevisionServiceTest.NOW;
    record Case(ConfigRevisionServiceImpl publications, RecipeService drafts, String recipe,
            FoodWriteGuardFixture.Fixture f,
            AssertableApplicationContext context,
            AtomicBoolean enabled, AtomicBoolean outage, AtomicBoolean fail, AtomicBoolean changed,
            Runnable[] during) { }
    void run(Consumer<Case> test) {
        var fixture = new RecipeServiceTest();
        try {
            fixture.run((c, f) -> {
            f.sql().execute("CREATE TABLE fnb_config_revision(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                    + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,"
                    + "deleted INT DEFAULT 0,revision_no VARCHAR(32) UNIQUE,"
                    + "config_kind VARCHAR(24),config_no VARCHAR(32),"
                    + "revision BIGINT,content_schema INT,definition_snapshot TEXT,content_hash VARCHAR(64),"
                    + "effective_at DATETIME(3),status VARCHAR(16),previous_revision_no VARCHAR(32),"
                    + "scope_store_nos TEXT,"
                    + "UNIQUE(entity_no,config_kind,config_no,revision))");
            f.sql().execute("CREATE TABLE fnb_dish_option_group(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "entity_no VARCHAR(32),tenant_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),"
                    + "updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,"
                    + "goods_no VARCHAR(32),group_no VARCHAR(32),sort_no INT)");
            var template = c.getBean(SqlSessionTemplate.class);
            for (var mapper : List.of(ConfigRevisionMapper.class, DishOptionGroupMapper.class,
                    ModifierMapper.class, ModifierComponentMapper.class)) {
                if (!template.getConfiguration().hasMapper(mapper)) template.getConfiguration().addMapper(mapper);
            }
            var enabled = new AtomicBoolean(true);
            var outage = new AtomicBoolean(); var fail = new AtomicBoolean(); var changed = new AtomicBoolean();
            Runnable[] during = {() -> { }};
            var goods = mock(GoodsCatalogQueryPort.class);
            when(goods.getSku(anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (outage.get()) throw new IllegalStateException("authority unavailable");
                during[0].run();
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2), "G", i.getArgument(0),
                        i.getArgument(1), 0);
            });
            MaterialCatalogQueryPort materials = (entity, ids) -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (outage.get()) throw new IllegalStateException("material unavailable");
                String name = changed.get() ? "Changed" : "牛奶";
                return List.of(new MaterialCatalogQueryPort.MaterialFact("MILK", entity, name, "KG", 1, "OWN",
                        "BASE_ONLY", "INTEGER_ONLY", MaterialCatalogFacts.definitionHash(
                                "MILK", entity, name, "KG", 1, "OWN")));
            };
            var outbox = c.getBean("foodOutboxPublisher", OutboxPublisher.class);
            OutboxPublisher checked = (event, options) -> {
                var result = outbox.publish(event, options);
                if (fail.get()) throw new IllegalStateException("after-publication");
                return result;
            };
            var publisher = new ConfigRevisionServiceImpl(template.getMapper(ConfigRevisionMapper.class),
                    template.getMapper(ModifierMapper.class), template.getMapper(ModifierComponentMapper.class),
                    c.getBean(OptionGroupMapper.class), c.getBean(OptionGroupItemMapper.class),
                    c.getBean(ActionLogMapper.class), c.getBean(IdempotencyService.class), checked,
                    c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class), goods,
                    new OptionGroupServiceTest.Sources(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), null,
                    new RecipePublicationSource(c.getBean(RecipeMapper.class), c.getBean(RecipeLineMapper.class),
                            c.getBean(RecipeAdjustmentMapper.class), c.getBean(OptionGroupItemMapper.class),
                            c.getBean(OptionGroupMapper.class), template.getMapper(DishOptionGroupMapper.class),
                            enabled::get, c.getBean(ObjectMapper.class)), materials);
            c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of(
                    "PLATFORM:FOOD_RECIPE_CHANGED", "PLATFORM:FOOD_CONFIG_PUBLISHED"));
            var drafts = c.getBean(RecipeService.class);
            String number = drafts.createRecipe("E", "A", "actor", RecipeServiceTest.create("TEA", "DEFAULT"),
                    "create").recipe().recipeNo();
            drafts.replaceDefinition("E", "A", number, "actor", RecipeServiceTest.definition(0,
                    RecipeServiceTest.line(null, "MILK", "0.123456", "KG")), "define");
            test.accept(new Case(publisher, drafts, number, f, c, enabled, outage, fail, changed, during));
            });
        } finally { fixture.cleanupFixture(); }
    }
    static PublishConfigReq req(long version) { return new PublishConfigReq(version, NOW, null); }
    static void code(Runnable run, ErrorCode code) {
        assertThatThrownBy(run::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
    @Test void freezesCompleteRecipeAndEditingPublishedHeadCannotRewriteHistory() {
        run(t -> {
            var published = t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "publish");
            var frozen = t.publications().getRevision("E", "A", published.publication().revisionNo());
            assertThat(frozen.definitionSnapshot()).contains("\"contentSchema\":3", "\"qty\":\"0.123456\"");
            var head = t.drafts().getRecipe("E", "A", t.recipe()).recipe();
            assertThat(head.version()).isEqualTo(2); assertThat(head.configVersion()).isEqualTo(1);
            t.drafts().replaceDefinition("E", "A", t.recipe(), "actor", RecipeServiceTest.definition(2), "clear");
            assertThat(t.drafts().getRecipe("E", "A", t.recipe()).recipe().status()).isEqualTo("DRAFT");
            assertThat(t.publications().getRevision("E", "A", published.publication().revisionNo())).isEqualTo(frozen);
            t.enabled().set(false); t.outage().set(true);
            assertThat(t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "publish"))
                    .isEqualTo(published);
            assertThat(t.publications().listRevisions("E", "A",
                    new ListConfigRevisionsQuery("RECIPE", t.recipe(), 1, 20)).total()).isEqualTo(1);
        });
    }
    @Test void schedulesByStoreAndTimeAndDisabledWriterDoesNotDisableReadersOrCompletedReceipts() {
        run(t -> {
            t.enabled().set(false);
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "disabled"),
                    ErrorCode.NOT_FOUND);
            t.enabled().set(true);
            var first = t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "first");
            var next = t.publications().publishRecipe("E", "A", t.recipe(), "actor",
                    new PublishConfigReq(2L, NOW.plusHours(1), null), "future");
            assertThat(t.publications().resolveRevision("E", "A", "RECIPE", t.recipe(), NOW).publication())
                    .isEqualTo(first.publication());
            assertThat(t.publications().resolveRevision("E", "A", "RECIPE", t.recipe(), NOW.plusHours(1))
                    .publication().revisionNo()).isEqualTo(next.publication().revisionNo());
            code(() -> t.publications().getRevision("E", "B", first.publication().revisionNo()), ErrorCode.NOT_FOUND);
            code(() -> t.publications().publishRecipe("E", "B", t.recipe(), "actor", req(3), "foreign"),
                    ErrorCode.NOT_FOUND);
            t.enabled().set(false); t.outage().set(true);
            assertThat(t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "first"))
                    .isEqualTo(first);
        });
    }
    @Test void malformedWorkingDefinitionCannotReturnSuccessfulPublication() {
        run(t -> {
            t.f().sql().update("UPDATE fnb_recipe SET recipe_code=''");
            assertThatThrownBy(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "bad"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(t.f().sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
        });
    }
    @Test void materialChangesAndAuthorityFailureCannotCreatePublication() {
        run(t -> {
            t.changed().set(true);
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "changed"),
                    ErrorCode.CONFLICT);
            t.outage().set(true);
            assertThatThrownBy(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "down"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(t.f().sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
        });
    }
    @Test void sameVersionDefinitionRaceAndStaleVersionCannotSlipPastLockedRecapture() {
        run(t -> {
            t.during()[0] = () -> t.f().sql().update("UPDATE fnb_recipe_line SET qty=0.5");
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "race"),
                    ErrorCode.CONFLICT);
            assertThat(t.f().sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
        });
    }
    @Test void outboxFailureRollsBackAllPublicationFactsAndSameKeyCanRetry() {
        run(t -> {
            t.fail().set(true);
            assertThatThrownBy(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "p"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(t.drafts().getRecipe("E", "A", t.recipe()).recipe().version()).isEqualTo(1);
            for (String table : List.of("sys_idempotent", "sys_outbox", "fnb_action_log")) {
                assertThat(t.f().sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isEqualTo(2);
            }
            assertThat(t.f().sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
            t.fail().set(false);
            assertThat(t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), "p")
                    .publication().revision()).isEqualTo(1);
        });
    }
    @Test void adjustmentsRequirePublishedGroupAndActualRootProductBinding() {
        run(t -> {
            RecipeServiceTest.options(t.f());
            t.f().sql().update("UPDATE fnb_option_group SET selection_rules='{\"mutexSets\":[],\"requires\":[]}'");
            var head = t.drafts().getRecipe("E", "A", t.recipe()).recipe();
            var base = head.lines().getFirst();
            var input = RecipeServiceTest.line(base.lineNo(), "MILK", "0.123456", "KG");
            var edit = RecipeServiceTest.adjusted(t.context().getBean(ObjectMapper.class), 1, input,
                    RecipeServiceTest.adjustment(null, "NO_SUGAR", base.lineNo(), "SET", "0", "KG"));
            t.drafts().replaceDefinition("E", "A", t.recipe(), "actor", edit, "adjust");
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(2), "no-group"),
                    ErrorCode.BAD_REQUEST);
            t.publications().publishOptionGroup("E", "A", "SUGAR", "actor", req(0), "group");
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(2), "no-binding"),
                    ErrorCode.BAD_REQUEST);
            t.f().sql().update("INSERT INTO fnb_dish_option_group(entity_no,goods_no,group_no) "
                    + "VALUES('E','G','SUGAR')");
            var published = t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(2), "yes");
            var frozen = t.publications().getRevision("E", "A", published.publication().revisionNo());
            assertThat(frozen.definitionSnapshot()).contains("NO_SUGAR", "optionIdentityHash", "OPTION_GROUP");
            t.f().sql().update("DELETE FROM fnb_dish_option_group");
            code(() -> t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(3), "removed"),
                    ErrorCode.BAD_REQUEST);
            assertThat(t.publications().getRevision("E", "A", published.publication().revisionNo())).isEqualTo(frozen);
        });
    }
    @Test
    @EnabledIfEnvironmentVariable(named="FOOD_TEST_DISPOSABLE", matches="true")
    void nativeConcurrentPublicationsHaveOneWinner() {
        run(t -> {
            var start = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var jobs = List.of("one", "two").stream().map(key -> workers.submit(() -> {
                    start.await();
                    try {
                        t.publications().publishRecipe("E", "A", t.recipe(), "actor", req(1), key);
                        return "OK";
                    } catch (BizException e) { return e.errorCode().name(); }
                })).toList();
                start.countDown();
                assertThat(List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("OK", "CONFLICT");
            } catch (Exception e) { throw new AssertionError(e); }
        });
    }
}
