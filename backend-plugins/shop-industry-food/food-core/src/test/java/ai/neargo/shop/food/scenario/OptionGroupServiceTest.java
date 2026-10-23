package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ListOptionGroupsQuery;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishOptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.impl.OptionGroupServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Exercises real primary Mappers and the original idempotency/audit/outbox transaction. */
class OptionGroupServiceTest extends FoodWriteGuardFixture {
    Fixture fixture(AssertableApplicationContext c) {
        var f = prepare(c);
        StorePolicyPublicationTest.createPolicyTables(f);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_option_group (" + common + "group_no VARCHAR(32) UNIQUE,"
                + "name VARCHAR(64),min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),"
                + "published_revision_no VARCHAR(32),status VARCHAR(16),selection_rules TEXT)");
        f.sql().execute("CREATE TABLE fnb_option_group_item (" + common + "option_item_no VARCHAR(32) UNIQUE,"
                + "group_no VARCHAR(32),source_kind VARCHAR(16),spec_value_no VARCHAR(64),modifier_no VARCHAR(32),"
                + "min_qty DECIMAL(18,6),max_qty DECIMAL(18,6),default_qty DECIMAL(18,6),sort_no INT)");
        c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_OPTION_GROUP_CHANGED"));
        return f;
    }

    private static CreateOptionGroupReq req(String name, String min, String max) {
        return new CreateOptionGroupReq(name, new BigDecimal(min), new BigDecimal(max));
    }

    @Test void createsOneDraftAndReplaysNormalizedQuantitiesWithAtomicFacts() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class).run(c -> {
            var f = fixture(c);
            var service = c.getBean(OptionGroupService.class);
            var first = service.createOptionGroup("E", "A", "actor", req(" 糖度 ", "1.0", "2.00"), "new");
            assertThat(first.configNo()).isNotBlank().hasSizeLessThanOrEqualTo(32);
            assertThat(first.version()).isZero();
            assertThat(first.status()).isEqualTo("DRAFT");
            assertThat(service.createOptionGroup("E", "A", "actor", req("糖度", "1", "2"), "new"))
                    .isEqualTo(first);
            var group = service.getOptionGroup("E", "A", first.configNo()).config();
            assertThat(group.data().name()).isEqualTo("糖度");
            assertThat(group.data().items()).isEmpty();
            assertThat(group.data().selectionRules()).isEmpty();
            assertThat(group.data().publishedRevisionNo()).isNull();
            assertThatThrownBy(() -> service.createOptionGroup("E", "A", "actor", req("冰度", "1", "2"), "new"))
                    .isInstanceOf(BizException.class);
            for (String table : List.of("fnb_option_group", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
            assertThat(f.sql().queryForObject("SELECT object_no FROM fnb_action_log", String.class))
                    .isEqualTo(first.configNo());
            assertThat(f.sql().queryForObject("SELECT store_no FROM fnb_action_log", String.class)).isEqualTo("A");
        });
    }

    @Test void rejectsInvalidQuantitiesAndIdentityBeforeAnyDurableWrite() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class).run(c -> {
            var f = fixture(c);
            var service = c.getBean(OptionGroupService.class);
            for (var request : List.of(req(" ", "0", "1"), req("x".repeat(65), "0", "1"),
                    req("糖度", "-1", "1"), req("糖度", "2", "1"), req("糖度", "0", "0.0000001"),
                    req("糖度", "0", "1000000000000"), req("糖度", "0", "1E+2147483647"))) {
                assertThatThrownBy(() -> service.createOptionGroup("E", "A", "actor", request, "bad"))
                        .isInstanceOf(BizException.class);
            }
            assertThatThrownBy(() -> service.createOptionGroup(" E", "A", "actor", req("糖度", "0", "1"), "bad"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> service.createOptionGroup("E", "A", " ", req("糖度", "0", "1"), "bad"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_option_group", Integer.class)).isZero();
        });
    }

    @Test void pagesOnlyTheEntityLibraryAndReadsRealOrderedChildren() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class).run(c -> {
            var f = fixture(c);
            var service = c.getBean(OptionGroupService.class);
            for (String number : List.of("A", "B", "C")) {
                f.sql().update("INSERT INTO fnb_option_group(entity_no,group_no,name,min_qty,max_qty,status,"
                        + "selection_rules) VALUES('E',?, ?,0,2,'DRAFT','{}')", number, number);
            }
            f.sql().update("INSERT INTO fnb_option_group(entity_no,group_no,name,min_qty,max_qty,status,"
                    + "selection_rules) VALUES('OTHER','FOREIGN','foreign',0,1,'DRAFT','{}')");
            for (String number : List.of("I2", "I1")) {
                f.sql().update("INSERT INTO fnb_option_group_item(entity_no,group_no,option_item_no,source_kind,"
                        + "spec_value_no,min_qty,max_qty,default_qty,sort_no) VALUES('E','B',?,'SPEC_VALUE',?,0,1,0,0)",
                        number, number);
            }
            var page = service.listOptionGroups("E", "A", new ListOptionGroupsQuery(2, 1));
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.records()).extracting(x -> x.configNo()).containsExactly("B");
            assertThat(page.records().getFirst().data().items()).extracting(x -> x.optionItemNo())
                    .containsExactly("I1", "I2");
            // Shared entity configuration is visible through another authorized store, not falsely store-owned.
            assertThat(service.getOptionGroup("E", "B", "B").config()).isEqualTo(page.records().getFirst());
            assertThatThrownBy(() -> service.getOptionGroup("E", "A", "FOREIGN"))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            assertThat(service.listOptionGroups("E", "A", new ListOptionGroupsQuery(10, 1)).records()).isEmpty();
            assertThatThrownBy(() -> service.listOptionGroups("E", "A", new ListOptionGroupsQuery(1, 101)))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isZero();
        });
    }

    @Test void publisherFailureRollsBackGroupAuditOutboxAndReplayTogether() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class).run(c -> {
            var f = fixture(c);
            c.getBean(Failure.class).enabled.set(true);
            assertThatThrownBy(() -> c.getBean(OptionGroupService.class)
                    .createOptionGroup("E", "A", "actor", req("糖度", "0", "1"), "rollback"))
                    .isInstanceOf(RuntimeException.class);
            for (String table : List.of("fnb_option_group", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
            }
        });
    }

    @Test void refusesJoiningOuterTransaction() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class).run(c -> {
            fixture(c);
            var tx = new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> tx.execute(s -> c.getBean(OptionGroupService.class)
                    .createOptionGroup("E", "A", "actor", req("糖度", "0", "1"), "outer")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    static class Sources implements GoodsCatalogQueryPort, SpecValueCatalogQueryPort {
        @Override public GoodsFact getGoods(String entity, String store, String goods) {
            throw new UnsupportedOperationException("Only the tested batch protocol is required");
        }
        @Override public List<GoodsSummary> getGoodsBatch(String entity, String store, List<String> goods) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return goods.stream().filter(g -> "G".equals(g) && "A".equals(store)
                    || "X".equals(g) && "B".equals(store)).map(g ->
                    new GoodsSummary(g, entity, store, g, null, false, "PENDING", 0)).toList();
        }
        @Override public List<SpecValueFact> getValues(String entity, List<String> values) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return values.stream().filter(v -> !"MISSING".equals(v)).map(v -> new SpecValueFact(v, "D", "半糖",
                    "糖度", "QUANT".equals(v) ? "QUANT" : "ENUM", "PROP", "MERCHANT",
                    "FOREIGN".equals(v) ? "OTHER" : entity, "PLATFORM", null, 0, 0)).toList();
        }
    }

    static class Failure { final AtomicBoolean enabled = new AtomicBoolean(); }

    @Configuration(proxyBeanMethods = false)
    static class Groups {
        @Bean Sources optionSources() { return new Sources(); }
        @Bean MapperFactoryBean<DishMapper> optionDishes(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var f = new MapperFactoryBean<>(DishMapper.class); f.setSqlSessionTemplate(t); return f;
        }
        @Bean MapperFactoryBean<DishOptionGroupMapper> optionLinks(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var f = new MapperFactoryBean<>(DishOptionGroupMapper.class); f.setSqlSessionTemplate(t); return f;
        }
        @Bean MapperFactoryBean<ModifierMapper> optionModifiers(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var f = new MapperFactoryBean<>(ModifierMapper.class); f.setSqlSessionTemplate(t); return f;
        }
        @Bean Failure optionGroupFailure() { return new Failure(); }
        @Bean MapperFactoryBean<OptionGroupMapper> groups(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var f = new MapperFactoryBean<>(OptionGroupMapper.class); f.setSqlSessionTemplate(t); return f;
        }
        @Bean MapperFactoryBean<OptionGroupItemMapper> items(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var f = new MapperFactoryBean<>(OptionGroupItemMapper.class); f.setSqlSessionTemplate(t); return f;
        }
        @Bean OptionGroupService optionGroupService(OptionGroupMapper groups, OptionGroupItemMapper items,
                ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
                PlatformTransactionManager manager, Failure failure, DishMapper dishes, DishOptionGroupMapper bindings,
                ModifierMapper modifiers, Sources sources) {
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (failure.enabled.get()) throw new IllegalStateException("after-group-publish");
                return receipt;
            };
            return new OptionGroupServiceImpl(groups, items, actions, idem, checked, json, manager,
                    dishes, bindings, modifiers, sources, sources);
        }
    }
}
