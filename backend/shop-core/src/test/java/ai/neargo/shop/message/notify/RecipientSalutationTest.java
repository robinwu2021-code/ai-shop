package ai.neargo.shop.message.notify;

import ai.neargo.shop.common.RecipientSalutation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipientSalutationTest {
    @Test
    void formatsExplicitAndMissingSalutationsWithoutInferringGender() {
        assertThat(RecipientSalutation.format("张", "MR")).isEqualTo("张先生");
        assertThat(RecipientSalutation.format("李", "MS")).isEqualTo("李女士");
        assertThat(RecipientSalutation.format("张", null)).isEqualTo("张先生/女士");
        assertThat(RecipientSalutation.format("张", "UNSPECIFIED")).isEqualTo("张先生/女士");
        assertThat(RecipientSalutation.format(null, null)).isEqualTo("收件人先生/女士");
    }

    @Test
    void preservesLegacySuffixUnlessAnExplicitChoiceOverridesIt() {
        assertThat(RecipientSalutation.format("张女士", null)).isEqualTo("张女士");
        assertThat(RecipientSalutation.format("张先生/女士", "MS")).isEqualTo("张女士");
        assertThat(RecipientSalutation.format("张女士", "MR")).isEqualTo("张先生");
    }
}
