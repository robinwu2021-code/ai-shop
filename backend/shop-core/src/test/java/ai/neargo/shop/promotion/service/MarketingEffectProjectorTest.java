package ai.neargo.shop.promotion.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtEffectProjection;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ApplyMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PmtEffectProjectionMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MarketingEffectProjectorTest {
    private final PmtEffectProjectionMapper effects = mock(PmtEffectProjectionMapper.class);
    private final ApplyMapper applies = mock(ApplyMapper.class);
    private final MarketingEffectProjector projector = new MarketingEffectProjector(effects, applies,
            mock(ActivityMapper.class));
    private final Instant at = Instant.parse("2026-10-19T00:00:00Z");

    @Test void exactReplayAcceptedButChangedPersistedAmountRejected() {
        var source = source();
        when(applies.selectOne(any())).thenReturn(source);
        var original = new PmtEffectProjection(); original.setEntityNo("M1"); original.setStoreNo("S1");
        original.setAmountMinor(100L); original.setCurrency("CNY");
        original.setOccurredAt(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
        when(effects.lock("T1", "AP1", "A1")).thenReturn(original);
        projector.recordOrderDiscount("T1", "A1", "AP1", "M1", "S1", 100, "CNY", at);
        original.setAmountMinor(99L);
        assertThatThrownBy(() -> projector.recordOrderDiscount("T1", "A1", "AP1", "M1", "S1", 100, "CNY", at))
                .isInstanceOf(BizException.class);
    }

    @Test void rejectsFabricatedOrRevertedOrderEffect() {
        var source = source(); when(applies.selectOne(any())).thenReturn(source);
        assertThatThrownBy(() -> projector.recordOrderDiscount("T1", "A1", "AP1", "M1", "S2", 100, "CNY", at))
                .isInstanceOf(BizException.class);
        source.setRevertedAt(at.toEpochMilli());
        assertThatThrownBy(() -> projector.recordOrderDiscount("T1", "A1", "AP1", "M1", "S1", 100, "CNY", at))
                .isInstanceOf(BizException.class);
    }
    private PmtApply source() {
        var row = new PmtApply(); row.setAmountMinor(100L); row.setStoreNo("S1"); row.setAppliedAt(at.toEpochMilli());
        return row;
    }
}
