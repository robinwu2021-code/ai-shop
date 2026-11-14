package ai.neargo.shop.numbering;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DocumentNumberFormatterTest {
    @Test void registeredPrefixesAreUniqueAndSequenceExpandsWithoutWrapping() {
        assertThat(Arrays.stream(DocumentType.values()).map(DocumentType::prefix)).doesNotHaveDuplicates();
        assertThat(DocumentNumberFormatter.format(DocumentType.SV_RECHARGE, LocalDate.of(2026,10,20),1))
                .isEqualTo("PR20261020000001");
        assertThat(DocumentNumberFormatter.format(DocumentType.POINTS_VOUCHER,LocalDate.of(2026,10,20),1000000))
                .isEqualTo("PTV202610201000000");
    }
    @Test void rejectsInvalidDatesWrongTypeZeroAndInvalidWidth() {
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,"PR20260230000001")).isFalse();
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,"EU20261020000001")).isFalse();
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,"PR20261020000000")).isFalse();
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,"PR202610201")).isFalse();
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,"PR202610200000001")).isFalse();
        assertThatThrownBy(()->DocumentNumberFormatter.format(DocumentType.SV_RECHARGE,LocalDate.now(),0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptsLongMaxWithoutOverflowAndRejectsUnsupportedYear() {
        assertThat(DocumentNumberFormatter.isValid(DocumentType.SV_RECHARGE,
            DocumentNumberFormatter.format(DocumentType.SV_RECHARGE,LocalDate.of(2026,10,20),Long.MAX_VALUE))).isTrue();
        assertThatThrownBy(()->DocumentNumberFormatter.format(DocumentType.SV_RECHARGE,LocalDate.of(10000,1,1),1)).isInstanceOf(IllegalArgumentException.class);
    }
}
