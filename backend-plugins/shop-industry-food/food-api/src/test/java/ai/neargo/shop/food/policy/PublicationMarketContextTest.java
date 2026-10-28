package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.PublicationMarketContext;
import ai.neargo.shop.spi.product.CatalogPriceSourcePort.MarketScope;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PublicationMarketContextTest {
    @Test void freshOriginalMarketScopeSeedsStableContextButReceiptsRemainAuditEvidence() {
        var first = CurrencyMarketFixtures.facts("AE", "AED", 2, CurrencyMarketFixtures.NOW);
        var later = CurrencyMarketFixtures.facts("AE", "AED", 2, CurrencyMarketFixtures.NOW.plusSeconds(1));
        var a = PublicationMarketPolicy.capture(CurrencyMarketFixtures.SCOPE, first, CurrencyMarketFixtures.NOW);
        var b = PublicationMarketPolicy.capture(CurrencyMarketFixtures.SCOPE, later,
                CurrencyMarketFixtures.NOW.plusSeconds(1));
        assertThat(a.contextHash()).isEqualTo(b.contextHash());
        assertThat(a.marketReceiptNo()).isNotEqualTo(b.marketReceiptNo());
        PublicationMarketPolicy.requireSame(a, b);
    }

    @Test void scopeAndFreshnessMustBeVerifiedAtCreationButHistoryNeverUsesLiveExpiry() {
        var facts = CurrencyMarketFixtures.facts("AE", "AED", 2, CurrencyMarketFixtures.NOW);
        var context = PublicationMarketPolicy.capture(CurrencyMarketFixtures.SCOPE, facts, CurrencyMarketFixtures.NOW);
        assertThatThrownBy(() -> PublicationMarketPolicy.capture(new MarketScope("OTHER", "STORE"), facts,
                CurrencyMarketFixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicationMarketPolicy.capture(CurrencyMarketFixtures.SCOPE, facts,
                CurrencyMarketFixtures.NOW.plusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        PublicationMarketPolicy.validate(context);
        assertThat(new ObjectMapper().writeValueAsString(context)).doesNotContain("validUntil", "verifiedAt");
    }

    @Test void marketScopeCurrencyScaleAndDigestCannotBeMixedAcrossDependencies() {
        var a = CurrencyMarketFixtures.context("AE", "AED", 2);
        var tw = CurrencyMarketFixtures.context("TW", "TWD", 2);
        assertThatThrownBy(() -> PublicationMarketPolicy.requireSame(a, tw)).isInstanceOf(IllegalStateException.class);
        var bad = new PublicationMarketContext(1, a.entityNo(), "OTHER", a.market(), a.currency(), a.currencyScale(),
                a.contextHash(), a.marketReceiptNo());
        assertThatThrownBy(() -> PublicationMarketPolicy.validate(bad)).isInstanceOf(IllegalStateException.class);
        var badScale = new PublicationMarketContext(1, a.entityNo(), a.storeNo(), a.market(), a.currency(), 3,
                a.contextHash(), a.marketReceiptNo());
        assertThatThrownBy(() -> PublicationMarketPolicy.validate(badScale)).isInstanceOf(RuntimeException.class);
    }

    @Test void strictContextCodecRejectsUnknownCoercedFractionalAndMissingFields() {
        var json = new ObjectMapper();
        var a = CurrencyMarketFixtures.context("JP", "JPY", 0);
        String body = json.writeValueAsString(a);
        assertThat(json.readValue(body, PublicationMarketContext.class)).isEqualTo(a);
        for (String invalid : List.of(body.replace("\"currencyScale\":0", "\"currencyScale\":0.5"),
                body.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
                body.substring(0, body.length() - 1) + ",\"success\":true}")) {
            assertThat(invalid).isNotEqualTo(body);
            assertThatThrownBy(() -> json.readValue(invalid, PublicationMarketContext.class))
                    .isInstanceOf(RuntimeException.class);
        }
        var incomplete = json.readValue("{}", PublicationMarketContext.class);
        assertThatThrownBy(() -> PublicationMarketPolicy.validate(incomplete))
                .isInstanceOf(IllegalStateException.class);
    }
}
