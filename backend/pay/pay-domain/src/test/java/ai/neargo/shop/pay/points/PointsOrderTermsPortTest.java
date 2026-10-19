package ai.neargo.shop.pay.points;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.agreement.AgreementService;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.pay.points.port.PointsOrderTermsPortImpl;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.spi.points.PointsEarnPort.RuleMode;

import org.junit.jupiter.api.Test;

import java.util.*;

class PointsOrderTermsPortTest {
    PointsReadStore read = mock(PointsReadStore.class);
    AgreementService agreements = mock(AgreementService.class);
    PointsAdministrationService admin = mock(PointsAdministrationService.class);

    @Test
    void default_off_does_not_query_the_points_ledger() {
        assertThat(
                        new PointsOrderTermsPortImpl(read, agreements, admin, false)
                                .resolve("CN", "U", "M", "ST", "ONLINE", "H5"))
                .isEmpty();
        verifyNoInteractions(read, agreements, admin);
    }

    @Test
    void freezes_type_rate_agreement_and_fallback_without_conflating_a_zero_rule() {
        when(read.activeType("CN"))
                .thenReturn(
                        Optional.of(
                                new PointType(
                                        "T",
                                        "CN",
                                        "CNY",
                                        2,
                                        new RedeemRate(2, 1),
                                        2000,
                                        30,
                                        PointType.Status.ACTIVE)));
        when(read.enabled(any(PointsReadStore.Availability.class))).thenReturn(true);
        when(admin.fallback("T"))
                .thenReturn(new PointsAdministrationService.FallbackRule(7, RuleMode.FIXED, 0));
        var agreement =
                new AgreementSnapshot(
                        "A",
                        "T",
                        "M",
                        "ST",
                        "OWNER",
                        "V1",
                        "HASH",
                        "TEXT",
                        "PER_ORDER",
                        "DIRECT",
                        "TEST",
                        "ACCOUNT_V1",
                        "ACTIVE");
        when(agreements.active("T", "ST")).thenReturn(agreement);
        var terms =
                new PointsOrderTermsPortImpl(read, agreements, admin, true)
                        .resolve("CN", "U", "M", "ST", "ONLINE", "H5")
                        .orElseThrow();
        assertThat(terms.typeCode()).isEqualTo("T");
        assertThat(terms.ratePoints()).isEqualTo(2);
        assertThat(terms.rateMinor()).isEqualTo(1);
        assertThat(terms.earningAllowed()).isTrue();
        assertThat(terms.redemptionAllowed()).isTrue();
        assertThat(terms.fallback().value()).isZero();
        assertThat(terms.fallbackRevision()).isEqualTo(7);
        assertThat(terms.agreementSnapshot()).contains("ACCOUNT_V1");
        when(agreements.active("T", "ST")).thenReturn(null);
        assertThat(terms.agreementSnapshot()).contains("ACCOUNT_V1");
        assertThat(
                        new PointsOrderTermsPortImpl(read, agreements, admin, true)
                                .resolve("CN", "U", "M", "ST", "ONLINE", "H5")
                                .orElseThrow()
                                .redemptionAllowed())
                .isFalse();
    }
}
