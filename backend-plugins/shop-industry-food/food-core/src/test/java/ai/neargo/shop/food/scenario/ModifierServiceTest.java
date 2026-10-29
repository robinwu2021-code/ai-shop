package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.scenario.OptionGroupServiceTest.Groups;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.ListModifiersQuery;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.impl.ModifierServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real primary Mappers prove drafts never create prices or inventory side effects. */
class ModifierServiceTest extends FoodWriteGuardFixture {
    Fixture fixture(AssertableApplicationContext c) {
        var f = new OptionGroupServiceTest().fixture(c);
        String common = "id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) DEFAULT 'MAIN',"
                + "entity_no VARCHAR(32),created_at DATETIME(3),created_by VARCHAR(32),updated_at DATETIME(3),"
                + "updated_by VARCHAR(32),version BIGINT DEFAULT 0,deleted INT DEFAULT 0,";
        f.sql().execute("CREATE TABLE fnb_modifier (" + common + "modifier_no VARCHAR(32) UNIQUE,"
                + "name VARCHAR(64),kind VARCHAR(16),sales_sku_no VARCHAR(32),inventory_mode VARCHAR(16),published_revision_no VARCHAR(32),"
                + "status VARCHAR(16))");
        f.sql().execute("CREATE TABLE fnb_modifier_component (" + common + "component_no VARCHAR(32) UNIQUE,"
                + "combo_no VARCHAR(32),member_no VARCHAR(32),quantity DECIMAL(12,3),UNIQUE(combo_no,member_no))");
        c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_MODIFIER_CHANGED"));
        return f;
    }

    private CreateModifierReq addon(String sku) { return new CreateModifierReq(" 珍珠 ", "ADDON", sku, List.of()); }
    private ModifierComponentInput part(String number, String qty) {
        return new ModifierComponentInput(number, new BigDecimal(qty));
    }
    private void run(java.util.function.BiConsumer<AssertableApplicationContext,Fixture> test) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class, Groups.class, Modifiers.class)
                .run(c -> test.accept(c, fixture(c)));
    }

    @Test void createsDraftAndReplaysWithoutDuplicatingFacts() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            var first = s.createModifier("E", "A", "actor", addon("SKU"), "one");
            assertThat(first.configNo()).isNotBlank().hasSizeLessThanOrEqualTo(32);
            assertThat(first.status()).isEqualTo("DRAFT");
            assertThat(first.version()).isZero();
            assertThat(s.createModifier("E", "A", "actor", addon("SKU"), "one")).isEqualTo(first);
            var view = s.listModifiers("E", "A", null).records().getFirst();
            assertThat(view.data().name()).isEqualTo("珍珠");
            assertThat(view.data().salesSkuNo()).isEqualTo("SKU");
            assertThat(view.data().components()).isEmpty();
            for (String table : List.of("fnb_modifier", "fnb_action_log", "sys_outbox", "sys_idempotent")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
        });
    }

    @Test void comboReferencesExistingSameEntityAddonsAndRejectsNestingDuplicatesAndPrecision() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            var a = s.createModifier("E", "A", "actor", addon(null), "a");
            var combo = new CreateModifierReq("双拼", "COMBO", "SKU", List.of(part(a.configNo(), "1.500")));
            var result = s.createModifier("E", "A", "actor", combo, "combo");
            var page = s.listModifiers("E", "B", null);
            assertThat(page.total()).isEqualTo(2);
            var view = page.records().stream().filter(v -> v.configNo().equals(result.configNo())).findFirst().orElseThrow();
            assertThat(view.data().components()).hasSize(1);
            assertThat(view.data().components().getFirst().quantity()).isEqualByComparingTo("1.5");
            for (var bad : List.of(new CreateModifierReq("bad", "COMBO", null, List.of(part(result.configNo(), "1"))),
                    new CreateModifierReq("bad", "COMBO", null, List.of(part(a.configNo(), "1"), part(a.configNo(), "2"))),
                    new CreateModifierReq("bad", "COMBO", null, List.of(part(a.configNo(), "0"))),
                    new CreateModifierReq("bad", "COMBO", null, List.of(part(a.configNo(), "0.0001"))),
                    new CreateModifierReq("bad", "COMBO", null, List.of(part(a.configNo(), "1E+2147483647"))),
                    new CreateModifierReq("bad", "ADDON", null, List.of(part(a.configNo(), "1"))),
                    new CreateModifierReq("bad", "COMBO", null, List.of()))) {
                assertThatThrownBy(() -> s.createModifier("E", "A", "actor", bad, "bad"))
                        .isInstanceOf(BizException.class);
            }
            assertThatThrownBy(() -> s.createModifier("OTHER", "A", "actor", combo, "foreign"))
                    .isInstanceOf(BizException.class);
            f.sql().update("UPDATE fnb_modifier SET status='DISABLED' WHERE modifier_no=?", a.configNo());
            assertThatThrownBy(() -> s.createModifier("E", "A", "actor", combo, "disabled"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_modifier", Integer.class)).isEqualTo(2);
        });
    }

    @Test void updateClearsSkuPreservesPublishedRevisionAndUsesCasAndReplay() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            var a = s.createModifier("E", "A", "actor", addon("SKU"), "a");
            f.sql().update("UPDATE fnb_modifier SET published_revision_no='REV',status='ACTIVE' WHERE modifier_no=?", a.configNo());
            var req = new UpdateModifierReq("椰果", "ADDON", null, List.of(), 0L);
            var changed = s.updateModifier("E", "A", a.configNo(), "actor", req, "update");
            assertThat(changed.version()).isEqualTo(1);
            assertThat(s.updateModifier("E", "A", a.configNo(), "actor", req, "update")).isEqualTo(changed);
            var view = s.listModifiers("E", "A", null).records().getFirst();
            assertThat(view.data().salesSkuNo()).isNull();
            assertThat(view.data().publishedRevisionNo()).isEqualTo("REV");
            assertThat(view.status()).isEqualTo("DRAFT");
            code(() -> s.updateModifier("E", "A", a.configNo(), "actor", req, "stale"), ErrorCode.CONFLICT);
            code(() -> s.updateModifier("OTHER", "A", a.configNo(), "actor", req, "foreign"), ErrorCode.NOT_FOUND);
            code(() -> s.updateModifier("E", "A", a.configNo(), "actor",
                    new UpdateModifierReq("嵌套", "COMBO", null, List.of(part(a.configNo(), "1")), 1L), "kind"),
                    ErrorCode.CONFLICT);
        });
    }

    @Test void explicitModesAreValidatedAndComponentWritesRemainClosed() {
        run((c, f) -> {
            var service = c.getBean(ModifierService.class);
            var leaf = service.createModifier("E", "A", "actor", addon("SKU"), "mode-leaf");
            var parts = List.of(part(leaf.configNo(), "1"));
            var combo = service.createModifier("E", "A", "actor",
                    new CreateModifierReq("combo", "COMBO", "SKU", parts, "SALES_SKU"), "sales-mode");
            assertThat(service.listModifiers("E", "A", null).records().stream()
                    .filter(item -> combo.configNo().equals(item.configNo())).findFirst().orElseThrow()
                    .data().inventoryMode()).isEqualTo("SALES_SKU");
            assertThatThrownBy(() -> service.createModifier("E", "A", "actor",
                    new CreateModifierReq("combo", "COMBO", "SKU", parts, "COMPONENTS"), "blocked-mode"))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> service.updateModifier("E", "A", combo.configNo(), "actor",
                    new UpdateModifierReq("combo", "COMBO", null, parts, 0L), "clear-required-sku"))
                    .isInstanceOf(BizException.class);
        });
    }

    @Test void legacyUpdatePreservesAnExistingExplicitInventoryMode() {
        run((c, f) -> {
            var service = c.getBean(ModifierService.class);
            var leaf = service.createModifier("E", "A", "actor", addon("SKU"), "leaf-mode");
            var parts = List.of(part(leaf.configNo(), "1"));
            var combo = service.createModifier("E", "A", "actor",
                    new CreateModifierReq("combo", "COMBO", "SKU", parts), "combo-mode");
            f.sql().update("UPDATE fnb_modifier SET inventory_mode='COMPONENTS' WHERE modifier_no=?",
                    combo.configNo());
            service.updateModifier("E", "A", combo.configNo(), "actor",
                    new UpdateModifierReq("renamed", "COMBO", "SKU", parts, 0L), "old-client");
            var view = service.listModifiers("E", "A", null).records().stream()
                    .filter(item -> combo.configNo().equals(item.configNo())).findFirst().orElseThrow();
            assertThat(view.data().inventoryMode()).isEqualTo("COMPONENTS");
            assertThat(view.data().name()).isEqualTo("renamed");
        });
    }

    @Test void skuAuthorityIsOutsideLocalTransactionAndForeignFactsFailClosed() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            for (String sku : List.of("FOREIGN", "MISSING", "WRONG_STORE", "WRONG_SKU")) {
                assertThatThrownBy(() -> s.createModifier("E", "A", "actor", addon(sku), "bad"))
                        .isInstanceOf(RuntimeException.class);
            }
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_modifier", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        });
    }

    @Test void publisherFailureRollsBackWorkingChildrenHeadAuditAndReplay() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            var a = s.createModifier("E", "A", "actor", addon(null), "a");
            var combo = s.createModifier("E", "A", "actor", new CreateModifierReq("组合", "COMBO", null,
                    List.of(part(a.configNo(), "1"))), "combo");
            c.getBean(Fail.class).enabled.set(true);
            assertThatThrownBy(() -> s.updateModifier("E", "A", combo.configNo(), "actor",
                    new UpdateModifierReq("修改", "COMBO", null, List.of(part(a.configNo(), "2")), 0L), "fail"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT quantity FROM fnb_modifier_component", BigDecimal.class))
                    .isEqualByComparingTo("1");
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_modifier WHERE modifier_no=?",
                    Long.class, combo.configNo())).isZero();
            for (String table : List.of("fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isEqualTo(2);
            }
        });
    }

    @Test void twoAddonComboNormalizesMemberOrderForReplayAndRejectsOuterTransaction() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            var a = s.createModifier("E", "A", "actor", addon(null), "a");
            var b = s.createModifier("E", "A", "actor", addon(null), "b");
            var first = s.createModifier("E", "A", "actor", new CreateModifierReq("双拼", "COMBO", null,
                    List.of(part(a.configNo(), "1.0"), part(b.configNo(), "2.00"))), "combo");
            assertThat(s.createModifier("E", "A", "actor", new CreateModifierReq("双拼", "COMBO", null,
                    List.of(part(b.configNo(), "2"), part(a.configNo(), "1"))), "combo")).isEqualTo(first);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_modifier_component", Integer.class))
                    .isEqualTo(2);
            var tx = new TransactionTemplate(
                    c.getBean(PlatformTransactionManager.class));
            assertThatThrownBy(() -> tx.execute(status -> s.createModifier("E", "A", "actor", addon(null), "outer")))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test void listCountsOnlyEntityRowsAndValidatesPage() {
        run((c,f) -> {
            var s = c.getBean(ModifierService.class);
            for (int i = 0; i < 3; i++) s.createModifier("E", "A", "actor", addon(null), "a" + i);
            s.createModifier("OTHER", "A", "actor", addon(null), "foreign");
            var page = s.listModifiers("E", "A", new ListModifiersQuery(2, 1));
            assertThat(page.total()).isEqualTo(3);
            assertThat(page.page()).isEqualTo(2);
            assertThat(page.records()).hasSize(1);
            assertThat(s.listModifiers("E", "A", new ListModifiersQuery(10, 1)).records()).isEmpty();
            code(() -> s.listModifiers("E", "A", new ListModifiersQuery(1, 101)), ErrorCode.BAD_REQUEST);
        });
    }

    private static void code(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    static class Fail { final AtomicBoolean enabled = new AtomicBoolean(); }
    @Configuration(proxyBeanMethods = false)
    static class Modifiers {
        @Bean Fail modifierFailure() { return new Fail(); }
        @Bean MapperFactoryBean<ModifierComponentMapper> modifierComponents(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate t) {
            var m = new MapperFactoryBean<>(ModifierComponentMapper.class); m.setSqlSessionTemplate(t); return m;
        }
        @Bean ModifierService modifiers(ModifierMapper heads, ModifierComponentMapper components,
                ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
                PlatformTransactionManager manager, Fail fail) {
            var catalog = mock(GoodsCatalogQueryPort.class);
            when(catalog.getSku(anyString(),
                    anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                String sku = i.getArgument(2);
                if ("MISSING".equals(sku)) throw BizException.of(ErrorCode.NOT_FOUND);
                return new GoodsCatalogQueryPort.SkuIdentityFact("WRONG_SKU".equals(sku) ? "X" : sku, "G",
                        "FOREIGN".equals(sku) ? "OTHER" : i.getArgument(0),
                        "WRONG_STORE".equals(sku) ? "OTHER" : i.getArgument(1), 0);
            });
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (fail.enabled.get()) throw new IllegalStateException("after-modifier-publish");
                return receipt;
            };
            return new ModifierServiceImpl(heads, components, actions, idem, checked, json, manager, catalog);
        }
    }
}
