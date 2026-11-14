package ai.neargo.shop.food.commerce;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Account;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Connection;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Grant;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.IdentityEvidence;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Profile;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Provider;
import ai.neargo.shop.commerce.policy.ChannelCatalogAuthorityPolicy;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.commerce.dto.FoodCatalogBindingContracts.Query;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.DishPresentation;
import ai.neargo.shop.food.dto.DishVO;
import ai.neargo.shop.food.dto.MeasurementPolicy;
import ai.neargo.shop.food.dto.MenuCategoryInput;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.MenuCompositionVO;
import ai.neargo.shop.food.dto.MenuConfigResultVO;
import ai.neargo.shop.food.dto.MenuContextInput;
import ai.neargo.shop.food.dto.MenuItemInput;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.Captured;
import ai.neargo.shop.food.dto.MenuPublicationSnapshot.ItemOptions;
import ai.neargo.shop.food.dto.MenuScheduleVO;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.policy.MenuPublicationDocuments;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.GoodsFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuFact;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort.SkuIdentityFact;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

final class FoodCatalogBindingFixtures {
    static final Instant NOW = Instant.parse("2026-10-28T01:00:00Z");
    static final Query QUERY = new Query(
            new MenuSourceContracts.Query("E", "S", "M", "MR", MenuSourceContracts.Kind.MENU,
                    "M", null, null, MenuSourceContracts.Mode.CURRENT),
            new ChannelCatalogAuthorityContracts.Query("E", "S", "CON", "CR"), "B");
    final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    MenuChannelBindingVO binding(String provider, String account, String store) {
        return new MenuChannelBindingVO("B", "M", "S", provider, account, store, "ACTIVE", 0L);
    }

    MenuSourceContracts.Proof menu() {
        return menu("Menu", binding("TEST", "REMOTE_ACCOUNT", "REMOTE_STORE"), true, 25);
    }

    MenuSourceContracts.Proof menu(String name, MenuChannelBindingVO binding, boolean channel, int ttl) {
        return menu(name, binding, channel, ttl, "POLICY");
    }

    MenuSourceContracts.Proof menu(
            String name, MenuChannelBindingVO binding, boolean channel, int ttl, String pricePolicy) {
        var config = new MenuConfigResultVO(
                new MenuVO("M", "S", name, "TIME", "UTC", 0, null, "DRAFT", 0L, 0L),
                new MenuScheduleVO(null, null, List.of(), List.of()),
                new MenuCompositionVO(List.of(new MenuCategoryInput("CAT", "Category", 0,
                        List.of(new MenuItemInput("ITEM", "G", 0, true, "EXPLICIT", List.of("SKU"),
                                "REPLACE", List.of(), List.of()))))),
                List.of(channel ? new MenuContextInput("CHANNEL", "DELIVERY", "B", pricePolicy)
                        : new MenuContextInput("POS", "DINE_IN", null, null)));
        var snapshot = new MenuPublicationSnapshot(4, "MENU", "M", 0,
                new Captured("E", "S", config,
                        List.of(new DishVO("G", "SINGLE", 0,
                                new DishPresentation(null, List.of(), List.of(), List.of(), List.of()),
                                new MeasurementPolicy("EA", "COUNT", 0, null), 0, 0)),
                        List.of(new ItemOptions("ITEM", List.of())), List.of(),
                        channel ? List.of(binding) : List.of()),
                List.of(new SkuIdentityFact("SKU", "G", "E", "S", 0)),
                List.of(new GoodsFact("G", "E", "S", "Goods", null, true, "APPROVED", 0,
                        List.of(new SkuFact("SKU", "One", 0)))), null);
        String document = MenuPublicationDocuments.freeze(json, snapshot);
        var publication = new ConfigRevisionContentVO(
                new ConfigRevisionVO("MR", "MENU", "M", 1, time(NOW.minusSeconds(60)),
                        PublicationDocuments.hash(json, document), "EFFECTIVE"), document);
        var resource = MenuSourcePolicy.project(json, QUERY.menu(), publication);
        var unsigned = new MenuSourceContracts.Proof(1, QUERY.menu(), publication, resource,
                time(NOW), time(NOW.plusSeconds(ttl)), null);
        return new MenuSourceContracts.Proof(1, QUERY.menu(), publication, resource,
                unsigned.verifiedAt(), unsigned.validUntil(), MenuSourcePolicy.receipt(json, unsigned));
    }

    ChannelCatalogAuthorityContracts.Facts authority() {
        return authority("FOOD", "INSTANT", "REMOTE_ACCOUNT", "REMOTE_STORE", 20);
    }

    ChannelCatalogAuthorityContracts.Facts authority(
            String owner, String fulfillment, String account, String store, int ttl) {
        var unsigned = new ChannelCatalogAuthorityContracts.Facts(1, "CATALOG_AUTHORITY", QUERY.connection(),
                new Provider("P", "TEST", 0L),
                new Profile("PP", "DELIVERY", "TW", "TEST", "test:TW", false, 0L),
                new Account("ACC", account, 0L),
                new Connection("CON", "CR", store,
                        ChannelIdentity.storeKey("test:TW", false, account, store), owner, "LOCAL",
                        fulfillment, "a".repeat(64), 0L, 0L, 1L),
                new Grant("AUTH", 0L, 1L, "CATALOG", "b".repeat(64), NOW.plusSeconds(60)),
                null, NOW, NOW.plusSeconds(ttl), null);
        return ChannelCatalogAuthorityPolicy.seal(new ChannelCatalogAuthorityContracts.Facts(
                1, unsigned.purpose(), unsigned.query(), unsigned.provider(), unsigned.profile(),
                unsigned.account(), unsigned.connection(), unsigned.grant(),
                new IdentityEvidence("VERIFIED",
                        PricingSnapshotHash.of(ChannelCatalogAuthorityPolicy.identity(unsigned)),
                        "ORIGINAL", NOW, NOW.plusSeconds(60)), NOW, NOW.plusSeconds(ttl), null));
    }

    static OffsetDateTime time(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static final class MutableClock extends Clock {
        Instant instant = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
