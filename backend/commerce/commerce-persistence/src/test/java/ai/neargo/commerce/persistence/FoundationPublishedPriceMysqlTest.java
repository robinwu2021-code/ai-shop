package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.foundation.*;
import ai.neargo.commerce.core.foundation.FoundationQueryService;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class FoundationPublishedPriceMysqlTest {
    @Test
    void publishedPlanSkuKeepsItsActualCurrencyPriceAndTermUntilStopped() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new JdbcCatalogFixture(db);
            f.setup();
            f.plans();
            f.category("subscriptions");
            f.facade.saveProduct(
                    f.ops,
                    new ProductDraft(
                            "product",
                            "subscriptions",
                            ProductKind.PLAN,
                            "单 Plan",
                            "",
                            List.of("RETAIL"),
                            1,
                            0,
                            "fixture"));
            f.facade.saveSku(f.ops, f.sku());
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 0));
            var published = f.facade.catalog(f.ops, "RETAIL");
            f.facade.saveBusinessType(
                    f.ops,
                    new BusinessTypeDraft(
                            "RETAIL", "零售", 1, true, published.businessType().version(), "enable"));
            var actor = new ActorContext("tenant-a", SubjectKind.ACCOUNT, "member", "session");
            var host =
                    new FoundationAuthorityPort() {
                        public Instant account(ActorContext actual, Instant now) {
                            assertEquals(actor, actual);
                            return now.plusSeconds(60);
                        }

                        public Member member(ActorContext a, StoreRef s, Instant now) {
                            throw new AssertionError("catalog needs no store");
                        }
                    };
            var query =
                    new FoundationQueryService(
                            f.repo,
                            new JdbcSubscriptionRepository(db.ds, f.ids),
                            f.uow,
                            host,
                            Clock.systemUTC());
            var card = query.plans(actor, "RETAIL").plans().get(1);
            assertEquals(PlanTier.PRO, card.tier());
            assertEquals("DISPLAY_ONLY", card.priceStatus());
            assertFalse(card.purchaseAvailable());
            var price = card.priceOptions().getFirst();
            assertEquals(new Money("CNY", 9000), price.price());
            assertEquals(new Money("CNY", 10000), price.listPrice());
            assertEquals(365, price.periodDays());
            assertEquals(1, price.unitCount());
            f.facade.stopSale(
                    f.ops,
                    new StopSaleCommand(
                            null,
                            SaleTarget.SKU,
                            "sku",
                            f.skuDefinitionTokens("RETAIL").get("sku"),
                            "stop"));
            assertEquals("UNCONFIGURED", query.plans(actor, "RETAIL").plans().get(1).priceStatus());
        }
    }
}
