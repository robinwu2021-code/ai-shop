package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("履约进度五档：货还能不能拦下来")
class FulfillStageTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long H = 3600_000L;

    @Test
    @DisplayName("子单已签收 → RECEIVED（优先级最高，不看运单）")
    void signed_is_received() {
        assertThat(FulfillStage.of(OrdSubOrder.COMPLETED, NOW - H, "IN_TRANSIT", NOW - 2 * H, NOW))
                .isEqualTo(FulfillStage.RECEIVED);
    }

    @Test
    @DisplayName("运单已揽收(非 CREATED) → SHIPPED，拦不住")
    void picked_up_is_shipped() {
        assertThat(FulfillStage.of(OrdSubOrder.FULFILLING, null, "PICKED_UP", NOW - H, NOW))
                .isEqualTo(FulfillStage.SHIPPED);
    }

    @Test
    @DisplayName("录了单号还停在 CREATED、宽限期内 → LABELED，货还在仓里")
    void labeled_within_grace() {
        assertThat(FulfillStage.of(OrdSubOrder.FULFILLING, null, "CREATED", NOW - 3 * H, NOW))
                .isEqualTo(FulfillStage.LABELED);
    }

    @Test
    @DisplayName("录了单号停在 CREATED 超 6 小时 → 从严当 SHIPPED（R7）")
    void labeled_overdue_is_shipped() {
        assertThat(FulfillStage.of(OrdSubOrder.FULFILLING, null, "CREATED", NOW - 7 * H, NOW))
                .isEqualTo(FulfillStage.SHIPPED);
    }

    @Test
    @DisplayName("CREATED 但拿不到运单创建时刻 → 按 LABELED（不自动升级）")
    void created_without_createdAt_is_labeled() {
        assertThat(FulfillStage.of(OrdSubOrder.FULFILLING, null, "CREATED", null, NOW))
                .isEqualTo(FulfillStage.LABELED);
    }

    @Test
    @DisplayName("无运单、已备货 → PREPARING")
    void prepared_no_waybill_is_preparing() {
        assertThat(FulfillStage.of(OrdSubOrder.WAIT_FULFILL, NOW - H, null, null, NOW))
                .isEqualTo(FulfillStage.PREPARING);
    }

    @Test
    @DisplayName("无运单、未备货 → NOT_PREPARED")
    void not_prepared() {
        assertThat(FulfillStage.of(OrdSubOrder.WAIT_FULFILL, null, null, null, NOW))
                .isEqualTo(FulfillStage.NOT_PREPARED);
    }

    @Test
    @DisplayName("运单状态空串当无运单处理")
    void blank_waybill_is_no_waybill() {
        assertThat(FulfillStage.of(OrdSubOrder.WAIT_FULFILL, null, "  ", null, NOW))
                .isEqualTo(FulfillStage.NOT_PREPARED);
    }
}
