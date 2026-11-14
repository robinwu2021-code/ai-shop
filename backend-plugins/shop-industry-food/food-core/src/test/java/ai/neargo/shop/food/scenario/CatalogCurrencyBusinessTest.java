package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.food.dto.CreateModifierReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuSourceContracts.Kind;
import ai.neargo.shop.food.dto.MenuSourceContracts.Mode;
import ai.neargo.shop.food.dto.MenuSourceContracts.Query;
import ai.neargo.shop.food.dto.ModifierComponentInput;
import ai.neargo.shop.food.dto.PublishConfigReq;
import ai.neargo.shop.food.dto.RepublishConfigReq;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierComponentMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ModifierMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupItemMapper;
import ai.neargo.shop.food.mapper.FoodMappers.OptionGroupMapper;
import ai.neargo.shop.food.policy.CatalogCurrencySession;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.policy.PublicationMarketPolicy;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.impl.CatalogCurrencyServiceImpl;
import ai.neargo.shop.food.service.ConfigRevisionService;
import ai.neargo.shop.food.service.ModifierService;
import ai.neargo.shop.food.service.impl.ConfigRevisionServiceImpl;
import ai.neargo.shop.food.service.impl.ModifierServiceImpl;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.CatalogMarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourceFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.Market;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketFacts;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Original foreign identities must reach real command SQL, frozen publications and rollback guards. */
class CatalogCurrencyBusinessTest {
    static final Instant AT = ConfigRevisionServiceTest.NOW.toInstant();

    static final class MovingClock extends Clock {
        Instant at = AT;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }

    static final class Sources {
        final MovingClock clock = new MovingClock();
        final GoodsCatalogQueryPort goods = mock(GoodsCatalogQueryPort.class);
        final CatalogPriceSourcePort owner = mock(CatalogPriceSourcePort.class);
        Market market = new Market("AE", "AED", 2);
        boolean offline;
        boolean corrupt;
        Runnable remote = () -> { };
        final CatalogCurrencyServiceImpl currencies = new CatalogCurrencyServiceImpl(goods, () -> owner, true, clock);
        Sources() {
            when(owner.market(any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (offline) throw new IllegalStateException("offline");
                remote.run();
                return proof(i.getArgument(0));
            });
            when(goods.getCatalogGoods(any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var q = (GoodsQuery) i.getArgument(0);
                var fact = new GoodsFact(q.goodsNo(), q.entityNo(), q.storeNo(), "Goods", null, true,
                        "APPROVED", 0, List.of(new SkuFact("SKU", "Regular", 0)));
                return CatalogMarketFacts.seal(new GoodsFacts(1, "CATALOG_GOODS", q,
                        proof(new MarketScope(q.entityNo(), q.storeNo())), fact, clock.instant(),
                        clock.instant().plusSeconds(30), null));
            });
            when(goods.getCatalogSku(any())).thenAnswer(i -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var q = (SkuQuery) i.getArgument(0);
                var sku = new SkuIdentityFact(q.skuNo(), "G", corrupt ? "OTHER" : q.entityNo(), q.storeNo(), 0);
                return CatalogMarketFacts.seal(new SkuFacts(1, "CATALOG_SKU", q,
                        proof(new MarketScope(q.entityNo(), q.storeNo())), sku, 0L, clock.instant(),
                        clock.instant().plusSeconds(30), null));
            });
        }
        MarketFacts proof(MarketScope scope) {
            return CatalogPriceSourceFacts.sealMarket(new MarketFacts(1, "CATALOG_MARKET", scope, market,
                    clock.instant(), clock.instant().plusSeconds(30), null));
        }
    }

    static ModifierServiceImpl modifiers(AssertableApplicationContext c, Sources source, OutboxPublisher outbox) {
        return new ModifierServiceImpl(c.getBean(ModifierMapper.class), c.getBean(ModifierComponentMapper.class),
                c.getBean(ActionLogMapper.class), c.getBean(IdempotencyService.class), outbox,
                c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class), source.goods,
                source.currencies);
    }

    static ConfigRevisionServiceImpl publications(AssertableApplicationContext c, Sources source) {
        return new ConfigRevisionServiceImpl(c.getBean(ConfigRevisionMapper.class), c.getBean(ModifierMapper.class),
                c.getBean(ModifierComponentMapper.class), c.getBean(OptionGroupMapper.class),
                c.getBean(OptionGroupItemMapper.class), c.getBean(ActionLogMapper.class),
                c.getBean(IdempotencyService.class), c.getBean("foodOutboxPublisher", OutboxPublisher.class),
                c.getBean(ObjectMapper.class), c.getBean(PlatformTransactionManager.class), source.goods,
                (e, ids) -> List.of(), source.clock, null, null, null, null, null, () -> null, null,
                source.currencies);
    }

    static CreateModifierReq addon() { return new CreateModifierReq("Milk", "ADDON", "SKU", List.of()); }
    static PublishConfigReq publish(long version) {
        return new PublishConfigReq(version, ConfigRevisionServiceTest.NOW, null);
    }

    static String foreignMenu(ObjectMapper json, Sources source) {
        var old = json.readValue(MenuSourceVerificationTest.document(json, "EXPLICIT"),
                MenuPublicationSnapshot.class);
        var c = old.menu();
        var d = c.definition();
        var config = new MenuConfigResultVO(d.config(), d.schedule(), d.composition(),
                List.of(new MenuContextInput("CHANNEL", "DELIVERY", "BIND", "MENU_PUBLISHED_PRICES_V1")));
        var captured = new MenuPublicationSnapshot.Captured("E", "A", config, c.dishes(), c.itemOptions(),
                c.dependencies(), List.of(new MenuChannelBindingVO("BIND", "M", "A", "keeta", "ACCOUNT",
                        "EXTERNAL", "ACTIVE", 0L)));
        var context = PublicationMarketPolicy.capture(new MarketScope("E", "A"),
                source.proof(new MarketScope("E", "A")), source.clock.instant());
        return PublicationDocuments.freeze(json, new MenuPublicationSnapshot(6, "MENU", "M", 0, captured,
                old.skuFacts(), old.goodsFacts(), null, context));
    }

    @Test void currentMenuRequalifiesMarketWhileHistoricalReadNeedsNoOwner() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources();
            var json = c.getBean(ObjectMapper.class);
            var document = foreignMenu(json, source);
            MenuSourceVerificationTest.insert(f.sql(), json, "R1", 1,
                    ConfigRevisionServiceTest.NOW.minusDays(1), document);
            f.sql().update("UPDATE fnb_config_revision SET content_schema=6 WHERE revision_no='R1'");
            var service = publications(c, source);
            var query = new Query("E", "A", "M", "R1", Kind.MENU, "M", null, null, Mode.CURRENT);
            var proof = service.verifyMenuResource(query);
            MenuSourcePolicy.validateProof(json, query, proof, ConfigRevisionServiceTest.NOW);
            source.market = new Market("TW", "TWD", 2);
            assertThatThrownBy(() -> service.verifyMenuResource(query)).isInstanceOf(BizException.class);
            source.offline = true;
            assertThat(service.verifyMenuResource(new Query("E", "A", "M", "R1", Kind.MENU,
                    "M", null, null, Mode.HISTORICAL)).publication().definitionSnapshot()).isEqualTo(document);
        });
    }

    @Test void foreignCurrentContextCannotReinterpretLegacyCnMenu() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources();
            var json = c.getBean(ObjectMapper.class);
            MenuSourceVerificationTest.insert(f.sql(), json, "R1", 1,
                    ConfigRevisionServiceTest.NOW.minusDays(1), MenuSourceVerificationTest.document(json, "EXPLICIT"));
            assertThatThrownBy(() -> publications(c, source).verifyMenuResource(new Query("E", "A", "M", "R1",
                    Kind.MENU, "M", null, null, Mode.CURRENT))).isInstanceOf(BizException.class);
        });
    }

    @Test void standaloneForeignAddonAndComboPublishWithoutAnExistingMenuAndHistorySurvivesOwnerOutage() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources();
            var service = modifiers(c, source, c.getBean("foodOutboxPublisher", OutboxPublisher.class));
            var pubs = publications(c, source);
            var child = service.createModifier("E", "A", "actor", addon(), "child");
            var childPublication = pubs.publishModifier("E", "A", child.configNo(), "actor", publish(0), "pc");
            var combo = service.createModifier("E", "A", "actor", new CreateModifierReq("Combo", "COMBO", "SKU",
                    List.of(new ModifierComponentInput(child.configNo(), BigDecimal.ONE))), "combo");
            var parent = pubs.publishModifier("E", "A", combo.configNo(), "actor", publish(0), "pp");
            var content = pubs.getRevision("E", "A", parent.publication().revisionNo());
            assertThat(content.definitionSnapshot()).contains("\"contentSchema\":6", "\"currency\":\"AED\"");
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision WHERE content_schema=6",
                    Integer.class)).isEqualTo(2);
            source.offline = true;
            assertThat(pubs.getRevision("E", "A", parent.publication().revisionNo())).isEqualTo(content);
            assertThat(pubs.publishModifier("E", "A", child.configNo(), "actor", publish(0), "pc"))
                    .isEqualTo(childPublication);
            assertThatThrownBy(() -> pubs.republishRevision("E", "A", parent.publication().revisionNo(), "actor",
                    new RepublishConfigReq(1L, ConfigRevisionServiceTest.NOW, "restore", null), "restore"))
                    .isInstanceOf(IllegalStateException.class);
            verify(source.goods, never()).getSku(any(), any(), any());
        });
    }

    @Test void foreignParentCannotBorrowAnImplicitCnDependency() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var child = c.getBean(ModifierService.class).createModifier("E", "A", "actor", addon(), "legacy-child");
            c.getBean(ConfigRevisionService.class).publishModifier("E", "A", child.configNo(), "actor",
                    publish(0), "legacy-publication");
            var source = new Sources();
            var service = modifiers(c, source, c.getBean("foodOutboxPublisher", OutboxPublisher.class));
            var combo = service.createModifier("E", "A", "actor", new CreateModifierReq("Combo", "COMBO", "SKU",
                    List.of(new ModifierComponentInput(child.configNo(), BigDecimal.ONE))), "foreign-parent");
            assertThatThrownBy(() -> publications(c, source).publishModifier("E", "A", combo.configNo(), "actor",
                    publish(0), "foreign-publication")).isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isOne();
        });
    }

    @Test void marketChangeBlocksRepublishWithoutChangingOriginalBytesOrAppendCount() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources();
            var mod = modifiers(c, source, c.getBean("foodOutboxPublisher", OutboxPublisher.class));
            var pub = publications(c, source);
            var number = mod.createModifier("E", "A", "actor", addon(), "create").configNo();
            var first = pub.publishModifier("E", "A", number, "actor", publish(0), "publish");
            var original = pub.getRevision("E", "A", first.publication().revisionNo());
            var req = new RepublishConfigReq(1L, ConfigRevisionServiceTest.NOW, "restore", null);
            var restored = pub.republishRevision("E", "A", first.publication().revisionNo(), "actor", req, "good");
            assertThat(pub.getRevision("E", "A", restored.publication().revisionNo()).definitionSnapshot())
                    .isEqualTo(original.definitionSnapshot());
            source.market = new Market("TW", "TWD", 2);
            assertThatThrownBy(() -> pub.republishRevision("E", "A", first.publication().revisionNo(), "actor",
                    new RepublishConfigReq(2L, ConfigRevisionServiceTest.NOW, "restore", null), "bad"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_config_revision", Integer.class)).isEqualTo(2);
        });
    }

    @Test void corruptForeignIdentityFailsBeforeWritingAnything() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources(); source.corrupt = true;
            var service = modifiers(c, source, c.getBean("foodOutboxPublisher", OutboxPublisher.class));
            assertThatThrownBy(() -> service.createModifier("E", "A", "actor", addon(), "bad"))
                    .isInstanceOf(BizException.class);
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM fnb_modifier", Integer.class)).isZero();
            assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM sys_idempotent", Integer.class)).isZero();
        });
    }

    @Test void expiryDuringOutboxWorkRollsBackBusinessAuditIdempotencyAndOutbox() {
        new ConfigRevisionServiceTest().run((c, f) -> {
            var source = new Sources();
            var outbox = c.getBean("foodOutboxPublisher", OutboxPublisher.class);
            var service = modifiers(c, source, (event, options) -> {
                var receipt = outbox.publish(event, options);
                source.clock.at = AT.plusSeconds(31);
                return receipt;
            });
            assertThatThrownBy(() -> service.createModifier("E", "A", "actor", addon(), "expired"))
                    .isInstanceOf(BizException.class);
            for (String table : List.of("fnb_modifier", "fnb_action_log", "sys_idempotent", "sys_outbox")) {
                assertThat(f.sql().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
            }
        });
    }

    @Test void disabledSelectionNeverResolvesMarketOwnerAndCnKeepsOriginalSkuMethod() {
        var source = new Sources();
        var disabled = new CatalogCurrencyServiceImpl(source.goods, () -> { throw new AssertionError(); }, false,
                source.clock);
        assertThat(disabled.open("E", "A").managed()).isFalse();
        source.market = new Market("CN", "CNY", 2);
        when(source.goods.getSku("E", "A", "SKU")).thenReturn(new SkuIdentityFact("SKU", "G", "E", "A", 0));
        var session = source.currencies.open("E", "A");
        assertThat(session.sku("SKU").skuNo()).isEqualTo("SKU");
        assertThat(session.frozen()).isNull();
        verify(source.goods, never()).getCatalogSku(any());
    }

    @Test void marketRefreshAndDependencyContextCannotSilentlySwitchCurrency() {
        var source = new Sources();
        CatalogCurrencySession session = source.currencies.open("E", "A");
        var original = session.frozen();
        source.market = new Market("TW", "TWD", 2);
        assertThatThrownBy(session::refresh).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> source.currencies.open("E", "A").requireFrozen(original))
                .isInstanceOf(BizException.class);
    }
}
