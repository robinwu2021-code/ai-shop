package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.SaveDishReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.DishMapper;
import ai.neargo.shop.food.service.DishService;
import ai.neargo.shop.food.service.impl.DishServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real primary Mapper, idempotency, audit and outbox; product facts remain the external authority. */
class DishServiceTest extends FoodWriteGuardFixture {
    private Fixture fixture(AssertableApplicationContext c) {
        var f = prepare(c);
        StorePolicyPublicationTest.createPolicyTables(f);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_dish (" + common + "goods_no VARCHAR(32) UNIQUE,"
                + "dish_kind VARCHAR(16),prep_seconds INT,status VARCHAR(16),presentation TEXT,"
                + "measurement_policy TEXT,config_version BIGINT)");
        c.getBean(UnifiedOutboxFacade.class)
                .setArchiveOnly(Set.of("PLATFORM:FOOD_DISH_CHANGED"));
        return f;
    }

    private SaveDishReq req(Long version, String name) {
        return new SaveDishReq("SINGLE", 60,
                new DishPresentation(name, List.of(), List.of(), List.of("热饮"), List.of("含奶")),
                new MeasurementPolicy("EA", "COUNT", 0, null), version);
    }

    @Test void createsAndEditsOnlyTheExtensionWithStableReplayAndCas() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Dishes.class).run(c -> {
            var f = fixture(c);
            var s = c.getBean(DishService.class);
            var first = s.saveDish("E", "A", "G", "actor", req(null, " 奶茶 "), "create");
            assertThat(first.dish().goodsNo()).isEqualTo("G");
            assertThat(first.dish().presentation().displayName()).isEqualTo("奶茶");
            assertThat(first.dish().version()).isZero();
            assertThat(first.dish().configVersion()).isEqualTo(1);
            assertThat(s.saveDish("E", "A", "G", "actor", req(null, "奶茶"), "create")).isEqualTo(first);
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor", req(null, "奶茶"), "duplicate"))
                    .isInstanceOf(BizException.class);
            var edited = s.saveDish("E", "A", "G", "actor", req(0L, null), "edit");
            assertThat(edited.dish().version()).isEqualTo(1);
            assertThat(edited.dish().configVersion()).isEqualTo(2);
            assertThat(edited.dish().presentation().displayName()).isNull();
            assertThat(s.saveDish("E", "A", "G", "actor", req(0L, null), "edit")).isEqualTo(edited);
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor", req(0L, "旧版"), "stale"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor", req(1L, "异载荷"), "edit"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_dish", Integer.class)).isOne();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_action_log", Integer.class)).isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_outbox", Integer.class)).isEqualTo(2);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isEqualTo(2);
        });
    }

    @Test void refusesForeignOrUnavailableFactsAndDoesNotInsertAnExtension() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Dishes.class).run(c -> {
            var f = fixture(c);
            var s = c.getBean(DishService.class);
            c.getBean(Facts.class).foreign = true;
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor", req(null, "奶茶"), "foreign"))
                    .isInstanceOf(BizException.class);
            c.getBean(Facts.class).foreign = false;
            c.getBean(Facts.class).unavailable = true;
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor", req(null, "奶茶"), "failure"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_dish", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        });
    }

    @Test void refusesUnverifiedMediaAndWeightUnitsRatherThanPretendingTheyAreSupported() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Dishes.class).run(c -> {
            var f = fixture(c);
            var s = c.getBean(DishService.class);
            var base = req(null, "奶茶");
            var media = new DishPresentation(null, List.of(), List.of("foreign-asset"), List.of(), List.of());
            assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor",
                    new SaveDishReq("SINGLE", 0, media, base.measurement(), null), "media"))
                    .isInstanceOf(BizException.class);
            for (var measurement : List.of(new MeasurementPolicy("g", "WEIGHT", 1, new BigDecimal("0.1")),
                    new MeasurementPolicy("arbitrary", "COUNT", 0, null))) {
                assertThatThrownBy(() -> s.saveDish("E", "A", "G", "actor",
                        new SaveDishReq("SINGLE", 0, base.presentation(), measurement, null), "unit"))
                        .isInstanceOf(BizException.class);
            }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_dish", Integer.class)).isZero();
        });
    }

    @Test void outboxFailureRollsBackDishAuditAndIdempotencyTogether() {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Dishes.class).run(c -> {
            var f = fixture(c);
            c.getBean(FailAfterPublish.class).enabled.set(true);
            assertThatThrownBy(() -> c.getBean(DishService.class)
                    .saveDish("E", "A", "G", "actor", req(null, "奶茶"), "rollback"))
                    .isInstanceOf(RuntimeException.class);
            for (String table : List.of("fnb_dish", "fnb_action_log", "sys_idempotent", "sys_outbox"))
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
        });
    }

    static class Facts implements GoodsCatalogQueryPort {
        boolean foreign;
        boolean unavailable;
        @Override public GoodsFact getGoods(String entity, String store, String goods) {
            if (unavailable) throw new IllegalStateException("catalog-unavailable");
            return new GoodsFact(goods, foreign ? "OTHER" : entity, store, "基础名", "base-cover", false,
                    "PENDING", 1, List.of(new SkuFact("SKU", "中杯", 1)));
        }
    }

    static class FailAfterPublish { final AtomicBoolean enabled = new AtomicBoolean(); }

    @Configuration(proxyBeanMethods = false)
    static class Dishes {
        @Bean FailAfterPublish failAfterPublish() { return new FailAfterPublish(); }
        @Bean Facts facts() { return new Facts(); }
        @Bean MapperFactoryBean<DishMapper> dishes(@Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var factory = new MapperFactoryBean<>(DishMapper.class);
            factory.setSqlSessionTemplate(t);
            return factory;
        }
        @Bean DishService dishService(DishMapper mapper, ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
                PlatformTransactionManager manager, Facts facts, FailAfterPublish fail) {
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (fail.enabled.get()) throw new IllegalStateException("after-dish-publish");
                return receipt;
            };
            return new DishServiceImpl(mapper, actions, idem, checked, json, manager, facts);
        }
    }
}
