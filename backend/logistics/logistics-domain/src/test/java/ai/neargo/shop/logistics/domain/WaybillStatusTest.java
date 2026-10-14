package ai.neargo.shop.logistics.domain;

import ai.neargo.shop.logistics.domain.WaybillStatus.Transition;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 运单状态只进不退（TDD-物流模块 AC4 / AC12）。 */
class WaybillStatusTest {

    @Test
    @DisplayName("★★★ 签收是终态：再来运输中 / 派件 / 异常都不变 —— 线上出过「已签收被打回运输中」")
    void neverGoesBackFromDelivered() {
        for (TraceStatus s : TraceStatus.values()) {
            Transition t = WaybillStatus.advance(WaybillStatus.DELIVERED, s);
            assertThat(t.next()).as("DELIVERED 收到 %s", s).isEqualTo(WaybillStatus.DELIVERED);
            assertThat(t.changed()).isFalse();
        }
    }

    @Test
    @DisplayName("★★★ 只进不退：派件中收到运输中，不变")
    void noDowngrade() {
        assertThat(WaybillStatus.advance(WaybillStatus.DELIVERING, TraceStatus.IN_TRANSIT).next())
                .isEqualTo(WaybillStatus.DELIVERING);
        assertThat(WaybillStatus.advance(WaybillStatus.IN_TRANSIT, TraceStatus.PICKED).changed()).isFalse();
    }

    @Test
    @DisplayName("★★ 首次揽收、首次签收各报一次；跳级直接签收时两个都报")
    void firstFlags() {
        Transition picked = WaybillStatus.advance(WaybillStatus.CREATED, TraceStatus.PICKED);
        assertThat(picked.firstPickedUp()).isTrue();
        assertThat(WaybillStatus.advance(WaybillStatus.PICKED_UP, TraceStatus.IN_TRANSIT).firstPickedUp()).isFalse();
        Transition jump = WaybillStatus.advance(WaybillStatus.CREATED, TraceStatus.SIGNED);
        assertThat(jump.firstPickedUp()).isTrue();
        assertThat(jump.firstDelivered()).isTrue();
    }

    @Test
    @DisplayName("★★ 疑难不是终态：进得去，来了更后的阶段就恢复")
    void exceptionRecovers() {
        assertThat(WaybillStatus.advance(WaybillStatus.IN_TRANSIT, TraceStatus.EXCEPTION).next())
                .isEqualTo(WaybillStatus.EXCEPTION);
        Transition back = WaybillStatus.advance(WaybillStatus.EXCEPTION, TraceStatus.SIGNED);
        assertThat(back.next()).isEqualTo(WaybillStatus.DELIVERED);
        assertThat(back.firstDelivered()).isTrue();
    }

    @Test
    @DisplayName("★ 作废的运单什么都改不了；UNKNOWN 不推进")
    void cancelledAndUnknown() {
        assertThat(WaybillStatus.advance(WaybillStatus.CANCELLED, TraceStatus.SIGNED).changed()).isFalse();
        assertThat(WaybillStatus.advance(WaybillStatus.IN_TRANSIT, TraceStatus.UNKNOWN).changed()).isFalse();
    }
}
