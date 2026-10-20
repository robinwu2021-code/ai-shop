package ai.neargo.shop.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.mapper.PmtEffectProjectionMapper;
import ai.neargo.shop.promotion.mapper.PmtEffectProjectionMapper.EffectTotal;
import ai.neargo.shop.promotion.service.impl.ActivityMarketingSummaryServiceImpl;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActivityMarketingSummaryServiceTest {
    private final PmtEffectProjectionMapper effects = mock(PmtEffectProjectionMapper.class);
    private final MerchantScopeFactsPort merchants = mock(MerchantScopeFactsPort.class);
    private final ActivityMarketingSummaryService service = new ActivityMarketingSummaryServiceImpl(effects, merchants);
    private final Instant from = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-10-20T00:00:00Z");

    @Test void preservesCurrencyBucketsAndRestrictedStoreScope() {
        var actor = new ActivityActor("T1", "M1", "STAFF", Set.of("S1"));
        when(effects.summary(eq("T1"), eq("M1"), eq(List.of("S1")), any(), any())).thenReturn(List.of(
                new EffectTotal("CNY", new BigDecimal("200"), 2L),
                new EffectTotal("USD", new BigDecimal("30"), 1L)));
        var result = service.summarize(actor, null, from, to);
        assertThat(result.metrics()).containsExactly(
                new ActivityMarketingSummaryService.Metric("ORDER_DISCOUNT", "CNY", 200, 2),
                new ActivityMarketingSummaryService.Metric("ORDER_DISCOUNT", "USD", 30, 1));
    }

    @Test void noStorePermissionReturnsNoMerchantWideTotals() {
        assertThat(service.summarize(new ActivityActor("T1", "M1", "STAFF", Set.of()), null, from, to)
                .metrics()).isEmpty();
        verify(effects, never()).summary(any(), any(), any(), any(), any());
    }

    @Test void rejectsOtherStoreAndInvalidTimeRange() {
        var actor = new ActivityActor("T1", "M1", "STAFF", Set.of("S1"));
        assertThatThrownBy(() -> service.summarize(actor, "S2", from, to)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.summarize(actor, null, to, from)).isInstanceOf(BizException.class);
        verify(effects, never()).summary(any(), any(), any(), any(), any());
    }
}
