package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.domain.support.CanonicalHash;
import ai.neargo.inventory.domain.support.MaterialRules;
import ai.neargo.inventory.domain.support.Quantities;
import ai.neargo.inventory.domain.support.SecretHasher;
import ai.neargo.shop.auth.PasswordHasher;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.inventory.InventoryDemandPlanFacts;
import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 进销存搬出 ai-shop 时**复制**了四条规则（ADR-046：不许依赖 shop-base）。
 * 复制品与原件必须逐字节一致：库里存着按原件算出的摘要与哈希，
 * 改了任何一边而没改另一边，历史记录就「对不上」了，且没有任何报错。
 */
class DomainRuleParityTest {

    @Test
    void canonicalHashMatchesPricingSnapshotHash() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("z", List.of(1, new BigDecimal("2.500"), "三"));
        sample.put("a", Map.of("qty", new BigDecimal("10.0"), "item", "I-1"));
        sample.put("m", new BigDecimal("0.000100"));
        for (Object value : List.of(sample, List.of(), Map.of("k", "v"), "text", new BigDecimal("1E+3"))) {
            assertThat(CanonicalHash.of(value)).isEqualTo(PricingSnapshotHash.of(value));
        }
    }

    @Test
    void secretHasherAcceptsShopHashesAndViceVersa() {
        PasswordHasher shop = new PasswordHasher();
        SecretHasher inventory = new SecretHasher();
        assertThat(inventory.matches("s3cret", shop.encode("s3cret"))).isTrue();
        assertThat(shop.matches("s3cret", inventory.encode("s3cret"))).isTrue();
        assertThat(inventory.matches("wrong", shop.encode("s3cret"))).isFalse();
        // 凭证一律由 encode 写入，商城那边的 encode 产物必须是 BCrypt —— 否则进销存这份校验不了存量
        assertThat(shop.encode("x")).startsWith("$2");
    }

    @Test
    void exactQuantityRuleMatches() {
        for (String v : List.of("1", "1.500000", "0.0000001", "1234567890123456789", "0", "-1")) {
            BigDecimal value = new BigDecimal(v);
            for (boolean zero : new boolean[] {true, false}) {
                Object expected = outcome(() -> InventoryDemandPlanFacts.quantity(value, zero));
                assertThat(outcome(() -> Quantities.exact(value, zero))).as(v + "/" + zero).isEqualTo(expected);
            }
        }
    }

    @Test
    void materialRulesMatch() {
        assertThat(MaterialRules.validKey(" a", 32)).isEqualTo(MaterialCatalogFacts.validKey(" a", 32));
        assertThat(MaterialRules.validKey("a", 32)).isEqualTo(MaterialCatalogFacts.validKey("a", 32));
        assertThat(MaterialRules.validDefinition("I1", "E1", "米", "kg", 1, "OWN"))
                .isEqualTo(MaterialCatalogFacts.validDefinition("I1", "E1", "米", "kg", 1, "OWN")).isTrue();
        assertThat(MaterialRules.validDefinition("I1", "E1", "米", "kg", 2, "OWN"))
                .isEqualTo(MaterialCatalogFacts.validDefinition("I1", "E1", "米", "kg", 2, "OWN")).isFalse();
        assertThatThrownBy(() -> MaterialRules.validateLookup("E1", List.of("A", "A")))
                .isInstanceOf(RuntimeException.class);
    }

    private static Object outcome(Supplier<BigDecimal> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            return "rejected";
        }
    }
}
