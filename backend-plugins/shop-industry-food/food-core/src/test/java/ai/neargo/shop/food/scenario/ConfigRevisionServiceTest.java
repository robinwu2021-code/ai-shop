package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.UpdateModifierReq;
import ai.neargo.shop.food.dto.CreateOptionGroupReq;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.OptionItemInput;
import ai.neargo.shop.food.dto.ReplaceOptionGroupItemsReq;
import ai.neargo.shop.food.dto.SelectionRules;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.ListConfigRevisionsQuery;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.OptionGroupService;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.SpecValueCatalogQueryPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import tools.jackson.databind.ObjectMapper;

/** Frozen contents, not working rows, remain the only historical interpretation. */
class ConfigRevisionServiceTest extends FoodWriteGuardFixture {
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T08:00:00Z");
    Fixture fixture(AssertableApplicationContext c) {
        var f = new ModifierServiceTest().fixture(c);
        f.sql().execute("CREATE TABLE fnb_config_revision (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,"
                + "deleted INT DEFAULT 0,revision_no VARCHAR(32) UNIQUE,config_kind VARCHAR(24),config_no VARCHAR(32),"
                + "revision BIGINT,content_schema INT,definition_snapshot TEXT,content_hash VARCHAR(64),"
                + "effective_at DATETIME(3),status VARCHAR(16),previous_revision_no VARCHAR(32),scope_store_nos TEXT,"
                + "UNIQUE(entity_no,config_kind,config_no,revision))");
        f.sql().execute("CREATE TABLE fnb_dish_option_group (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tenant_no VARCHAR(32) DEFAULT 'MAIN',entity_no VARCHAR(32),created_at DATETIME(3),"
                + "created_by VARCHAR(32),updated_at DATETIME(3),updated_by VARCHAR(32),version BIGINT DEFAULT 0,"
                + "deleted INT DEFAULT 0,goods_no VARCHAR(32),group_no VARCHAR(32),sort_no INT)");
        c.getBean(UnifiedOutboxFacade.class).setArchiveOnly(Set.of("PLATFORM:FOOD_MODIFIER_CHANGED",
                "PLATFORM:FOOD_OPTION_GROUP_CHANGED", "PLATFORM:FOOD_CONFIG_PUBLISHED"));
        return f;
    }
    void run(java.util.function.BiConsumer<AssertableApplicationContext,Fixture> action) {
        runner().withUserConfiguration(StorePolicyPublicationTest.Publication.class,
                OptionGroupServiceTest.Groups.class, ModifierServiceTest.Modifiers.class, Revisions.class)
                .run(c -> action.accept(c, fixture(c)));
    }
    String addon(AssertableApplicationContext c, String key) {
        return c.getBean(ModifierService.class).createModifier("E", "A", "actor",
                new CreateModifierReq("珍珠", "ADDON", "SKU", List.of()), key).configNo();
    }
    PublishConfigReq request(long version) { return new PublishConfigReq(version, NOW, null); }

    @Test void componentModeCannotBePublishedBeforeOrderPathsAreReady() {
        run((c, f) -> {
            var leaf = addon(c, "leaf-component-gate");
            var combo = c.getBean(ModifierService.class).createModifier("E", "A", "actor",
                    new CreateModifierReq("combo", "COMBO", "SKU", List.of(
                            new ModifierComponentInput(leaf, BigDecimal.ONE))), "combo-component-gate");
            f.sql().update("UPDATE fnb_modifier SET inventory_mode='COMPONENTS' WHERE modifier_no=?",
                    combo.configNo());
            assertThatThrownBy(() -> c.getBean(ConfigRevisionService.class).publishModifier(
                    "E", "A", combo.configNo(), "actor", request(0), "blocked-component-gate"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class))
                    .isZero();
        });
    }

    @Test void publicationIsAppendOnlyAndReplayKeepsOriginalReceiptAfterDraftEdits() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            var first = s.publishModifier("E", "A", number, "actor", request(0), "publish").publication();
            assertThat(first.revision()).isEqualTo(1);
            String bytes = s.getRevision("E", "A", first.revisionNo()).definitionSnapshot();
            assertThat(bytes).contains("珍珠", "SKU");
            c.getBean(ModifierService.class).updateModifier("E", "A", number, "actor",
                    new UpdateModifierReq("新珍珠", "ADDON", "SKU", List.of(), 1L), "edit");
            assertThat(s.publishModifier("E", "A", number, "actor", request(0), "publish").publication())
                    .isEqualTo(first);
            assertThat(s.getRevision("E", "A", first.revisionNo()).definitionSnapshot()).isEqualTo(bytes);
            var next = s.publishModifier("E", "A", number, "actor", request(2), "next").publication();
            assertThat(next.revision()).isEqualTo(2);
            assertThat(s.resolveRevision("E", "A", "MODIFIER", number, NOW).publication()).isEqualTo(next);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isEqualTo(2);
        });
    }

    @Test void scheduledRevisionsResolveByStoreTimeWithoutMutatingFrozenRows() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            var first = s.publishModifier("E", "A", number, "actor", request(0), "now").publication();
            var future = s.publishModifier("E", "A", number, "actor",
                    new PublishConfigReq(1L, NOW.plusHours(1), null), "future").publication();
            assertThat(future.status()).isEqualTo("SCHEDULED");
            assertThat(s.resolveRevision("E", "A", "MODIFIER", number, NOW).publication().revisionNo())
                    .isEqualTo(first.revisionNo());
            assertThat(s.resolveRevision("E", "A", "MODIFIER", number, NOW.plusHours(1))
                    .publication().revisionNo()).isEqualTo(future.revisionNo());
            assertThat(f.sql().queryForObject("SELECT status FROM fnb_config_revision WHERE revision_no=?",
                    String.class, future.revisionNo())).isEqualTo("SCHEDULED");
            code(() -> s.resolveRevision("E", "B", "MODIFIER", number, NOW.plusHours(1)), ErrorCode.NOT_FOUND);
            code(() -> s.getRevision("OTHER", "A", first.revisionNo()), ErrorCode.NOT_FOUND);
            assertThat(s.listRevisions("E", "B", new ListConfigRevisionsQuery("MODIFIER", number, 1, 20))
                    .records()).isEmpty();
        });
    }

    @Test void comboFreezesPublishedMemberInsteadOfLaterWorkingDefinition() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String member = addon(c, "a");
            String combo = c.getBean(ModifierService.class).createModifier("E", "A", "actor",
                    new CreateModifierReq("双拼", "COMBO", "SKU",
                            List.of(new ModifierComponentInput(member, BigDecimal.ONE))), "combo").configNo();
            code(() -> s.publishModifier("E", "A", combo, "actor", request(0), "missing"), ErrorCode.BAD_REQUEST);
            var child = s.publishModifier("E", "A", member, "actor", request(0), "child").publication();
            c.getBean(ModifierService.class).updateModifier("E", "A", member, "actor",
                    new UpdateModifierReq("未发布珍珠", "ADDON", "SKU", List.of(), 1L), "edit");
            var parent = s.publishModifier("E", "A", combo, "actor", request(0), "parent").publication();
            var snapshot = c.getBean(ObjectMapper.class).readTree(
                    s.getRevision("E", "A", parent.revisionNo()).definitionSnapshot());
            assertThat(snapshot.get("dependencies").get(0).get("revisionNo").asString()).isEqualTo(child.revisionNo());
            assertThat(snapshot.toString()).doesNotContain("未发布珍珠");
        });
    }

    @Test void optionGroupFreezesSpec64AndRejectsImpossibleMutexCapacity() {
        run((c,f) -> {
            var options = c.getBean(OptionGroupService.class); var s = c.getBean(ConfigRevisionService.class);
            String group = options.createOptionGroup("E", "A", "actor",
                    new CreateOptionGroupReq("糖度", BigDecimal.ONE, new BigDecimal("2")), "group").configNo();
            var i1 = new OptionItemInput("I1", "SPEC_VALUE", "V".repeat(64), null,
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 0);
            var i2 = new OptionItemInput("I2", "SPEC_VALUE", "OTHER", null,
                    BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 1);
            options.replaceOptionGroupItems("E", "A", group, "actor", new ReplaceOptionGroupItemsReq(0L,
                    List.of(i1,i2), List.of(), new SelectionRules(List.of(List.of("I1","I2")), List.of())), "items");
            var result = s.publishOptionGroup("E", "A", group, "actor", request(1), "publish").publication();
            assertThat(s.getRevision("E", "A", result.revisionNo()).definitionSnapshot())
                    .contains("V".repeat(64), "valueVersion");
            f.sql().update("UPDATE fnb_option_group SET min_qty=2,status='DRAFT',version=3 WHERE group_no=?", group);
            code(() -> s.publishOptionGroup("E", "A", group, "actor", request(3), "impossible"), ErrorCode.BAD_REQUEST);
        });
    }

    @Test void preflightCannotBeAppliedToAChangedHeadAndFutureOrStaleCommandsFailClosed() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            c.getBean(Controls.class).duringSku = () -> f.sql().update(
                    "UPDATE fnb_modifier SET name='changed' WHERE modifier_no=?", number);
            code(() -> s.publishModifier("E", "A", number, "actor", request(0), "race"), ErrorCode.CONFLICT);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
            c.getBean(Controls.class).duringSku = () -> { };
            code(() -> s.publishModifier("E", "A", number, "actor",
                    new PublishConfigReq(1L, NOW.minusDays(1), null), "backdate"), ErrorCode.BAD_REQUEST);
            c.getBean(Controls.class).badSku = true;
            assertThatThrownBy(() -> s.publishModifier("E", "A", number, "actor", request(1), "foreign"))
                    .isInstanceOf(RuntimeException.class);
        });
    }

    @Test void databaseJsonNormalizationKeepsHashButChangedContentIsRejected() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            var first = s.publishModifier("E", "A", number, "actor", request(0), "publish").publication();
            String original = s.getRevision("E", "A", first.revisionNo()).definitionSnapshot();
            var json = c.getBean(ObjectMapper.class);
            Map<String,Object> tree = json.readValue(original,
                    json.getTypeFactory().constructMapType(TreeMap.class, String.class, Object.class));
            String normalized = json.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
            assertThat(normalized).isNotEqualTo(original);
            f.sql().update("UPDATE fnb_config_revision SET definition_snapshot=?", normalized);
            assertThat(s.getRevision("E", "A", first.revisionNo()).publication().contentHash()).isEqualTo(first.contentHash());
            f.sql().update("UPDATE fnb_config_revision SET definition_snapshot=?", normalized.replace("珍珠", "changed"));
            assertThatThrownBy(() -> s.getRevision("E", "A", first.revisionNo()))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test void fullPrecisionQuantitiesUseDecimalStringsInFrozenDocuments() {
        run((c,f) -> {
            var options = c.getBean(OptionGroupService.class); var s = c.getBean(ConfigRevisionService.class);
            var maximum = new BigDecimal("999999999999.999999");
            String group = options.createOptionGroup("E", "A", "actor",
                    new CreateOptionGroupReq("大批量", BigDecimal.ZERO, maximum), "group").configNo();
            options.replaceOptionGroupItems("E", "A", group, "actor", new ReplaceOptionGroupItemsReq(0L,
                    List.of(new OptionItemInput("I", "SPEC_VALUE", "V", null, BigDecimal.ZERO, maximum,
                            BigDecimal.ZERO, 0)), List.of(), new SelectionRules(List.of(), List.of())), "items");
            var publication = s.publishOptionGroup("E", "A", group, "actor", request(1), "publish").publication();
            var body = c.getBean(ObjectMapper.class).readTree(
                    s.getRevision("E", "A", publication.revisionNo()).definitionSnapshot());
            assertThat(body.get("optionGroup").get("data").get("maxQty").isString()).isTrue();
            assertThat(body.get("optionGroup").get("data").get("maxQty").asString()).isEqualTo(maximum.toPlainString());
        });
    }

    @Test void completedPublicationReplaysAfterDraftBecomesInvalidAndKeepsIdentityChecks() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            var first = s.publishModifier("E", "A", number, "actor", request(0), "publish");
            c.getBean(ModifierService.class).updateModifier("E", "A", number, "actor",
                    new UpdateModifierReq("未完成草稿", "ADDON", null, List.of(), 1L), "clear");
            assertThat(s.publishModifier("E", "A", number, "actor", request(0), "publish")).isEqualTo(first);
            code(() -> s.publishModifier("E", "A", number, "actor", request(2), "publish"), ErrorCode.CONFLICT);
            code(() -> s.publishModifier("E", "A", number, "another", request(0), "publish"), ErrorCode.NOT_FOUND);
            c.getBean(ModifierService.class).updateModifier("E", "A", number, "actor",
                    new UpdateModifierReq("草稿", "ADDON", "SKU", List.of(), 2L), "restore");
            c.getBean(Controls.class).badSku = true;
            assertThat(s.publishModifier("E", "A", number, "actor", request(0), "publish")).isEqualTo(first);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isOne();
        });
    }

    @Test void cancelledRevisionRemainsAuditableButCannotBecomeEffective() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            var first = s.publishModifier("E", "A", number, "actor", request(0), "now").publication();
            var future = s.publishModifier("E", "A", number, "actor",
                    new PublishConfigReq(1L, NOW.plusHours(1), null), "future").publication();
            f.sql().update("UPDATE fnb_config_revision SET status='CANCELLED' WHERE revision_no=?", future.revisionNo());
            assertThat(s.listRevisions("E", "A", new ListConfigRevisionsQuery("MODIFIER", number, 1, 20)).total())
                    .isEqualTo(2);
            assertThat(s.getRevision("E", "A", future.revisionNo()).publication().status()).isEqualTo("CANCELLED");
            assertThat(s.resolveRevision("E", "A", "MODIFIER", number, NOW.plusHours(1))
                    .publication().revisionNo()).isEqualTo(first.revisionNo());
            code(() -> s.getRevision("E", "B", future.revisionNo()), ErrorCode.NOT_FOUND);
        });
    }

    @Test void publisherFailureRollsBackRevisionHeadAuditAndIdempotency() {
        run((c,f) -> {
            var s = c.getBean(ConfigRevisionService.class); String number = addon(c, "a");
            c.getBean(Controls.class).fail.set(true);
            assertThatThrownBy(() -> s.publishModifier("E", "A", number, "actor", request(0), "fail"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT version FROM fnb_modifier", Long.class)).isZero();
            for (String table : List.of("fnb_action_log", "sys_outbox", "sys_idempotent")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isOne();
            }
        });
    }
    private static void code(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
    static class Controls {
        final AtomicBoolean fail = new AtomicBoolean();
        Runnable duringSku = () -> { };
        boolean badSku;
    }
    @Configuration(proxyBeanMethods=false)
    static class Revisions {
        @Bean Controls controls() { return new Controls(); }
        @Bean MapperFactoryBean<ConfigRevisionMapper> revisions(
                @Qualifier("foodSqlSessionTemplate") SqlSessionTemplate template) {
            var f = new MapperFactoryBean<>(ConfigRevisionMapper.class); f.setSqlSessionTemplate(template); return f;
        }
        @Bean ConfigRevisionService revisionsService(ConfigRevisionMapper revisions, ModifierMapper modifiers,
                ModifierComponentMapper components, OptionGroupMapper groups, OptionGroupItemMapper items,
                ActionLogMapper actions, IdempotencyService idem,
                @Qualifier("foodOutboxPublisher") OutboxPublisher publisher, ObjectMapper json,
                PlatformTransactionManager manager, OptionGroupServiceTest.Sources specs, Controls control) {
            var goods = mock(GoodsCatalogQueryPort.class);
            when(goods.getSku(anyString(), anyString(), anyString())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                control.duringSku.run();
                return new GoodsCatalogQueryPort.SkuIdentityFact(i.getArgument(2), "G",
                        control.badSku ? "OTHER" : i.getArgument(0), i.getArgument(1), 0);
            });
            OutboxPublisher checked = (event, options) -> {
                var receipt = publisher.publish(event, options);
                if (control.fail.get()) throw new IllegalStateException("after-publication");
                return receipt;
            };
            return new ConfigRevisionServiceImpl(revisions, modifiers, components, groups, items, actions,
                    idem, checked, json, manager, goods, specs,
                    Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC));
        }
    }
}
