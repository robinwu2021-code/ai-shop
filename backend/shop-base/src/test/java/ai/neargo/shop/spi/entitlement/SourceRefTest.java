package ai.neargo.shop.spi.entitlement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 来源键必须防止订单改版重发，并区分同一件内多个权益。 */
class SourceRefTest {
    @Test
    void grantIdentitySurvivesSourceRevisionButUseIntentDoesNot() {
        SourceRef before = new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 1, 1, 0);
        SourceRef after = new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 1, 1, 3);
        assertThat(before.grantKey()).hasSize(64).isEqualTo(after.grantKey());
        assertThat(before.useIntentKey()).isNotEqualTo(after.useIntentKey());
        assertThat(before.settledKey()).isEqualTo(after.settledKey());
        assertThat(before.grantKey()).isNotEqualTo(before.settledKey());
    }

    @Test
    void everySoldUnitAndEntitlementWithinUnitHasItsOwnGrant() {
        SourceRef first = new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 1, 1, 0);
        assertThat(first.grantKey()).isNotEqualTo(
                new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 2, 1, 0).grantKey());
        assertThat(first.grantKey()).isNotEqualTo(
                new SourceRef("CORE", "PAID_ORDER", "o1", "l1", 1, 2, 0).grantKey());
    }

    @Test
    void delimitersAndAbsentValuesCannotAliasAnotherSource() {
        assertThat(new SourceRef("A:B", "C", "o", null, null, null, 0).grantKey())
                .isNotEqualTo(new SourceRef("A", "B:C", "o", null, null, null, 0).grantKey());
        assertThat(new SourceRef("CORE", "CLAIM", "o", null, null, null, 0).grantKey())
                .isEqualTo(new SourceRef("CORE", "CLAIM", "o", null, null, null, 0).grantKey());
    }

    @Test
    void rejectsInvalidOrAmbiguousSourceIdentity() {
        assertThatThrownBy(() -> new SourceRef(" ", "ORDER", "o", null, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef("CORE", "ORDER", "o", "", null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef("CORE", "ORDER", "o", null, 1, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef("CORE", "ORDER", "o", "l", 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRef("CORE", "ORDER", "o", null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
