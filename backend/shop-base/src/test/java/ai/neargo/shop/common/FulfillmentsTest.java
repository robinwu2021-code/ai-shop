package ai.neargo.shop.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 履约方式判断：可空列上的 null 不能抛 —— 批量任务里一行空值会让整批不处理（自动确认收货曾因此整批停摆）。 */
class FulfillmentsTest {

    @Test
    @DisplayName("★★ isPickup(null) 返回 false 而不是 NPE")
    void nullIsNotPickup() {
        assertThat(Fulfillments.isPickup(null)).isFalse();
        assertThat(Fulfillments.isPickup(Fulfillments.STORE_PICKUP)).isTrue();
    }
}
