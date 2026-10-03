package ai.neargo.shop.unit;

import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.WxLogisticsTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六种履约方式 → 微信四类 logistics_type。
 *
 * <p>TDD 点名这是本方案里**最容易写错的一段**，而写错的两种后果不对称：
 * 类型无效微信会拒（看得见），<b>而语义错微信不会拒</b> —— 报上去了，
 * 「48 小时未发货」的统计与真实履约对不上，没有任何地方会说一句。
 */
class WxLogisticsTypesTest {

    @Test
    @DisplayName("★★★ 六种履约方式逐条对上微信的四类")
    void everyFulfillmentMapsToTheRightType() {
        assertThat(WxLogisticsTypes.of(Fulfillments.EXPRESS)).isEqualTo(1);
        assertThat(WxLogisticsTypes.of(Fulfillments.MERCHANT_DELIVERY)).isEqualTo(2);
        // 自提两种都是 4「用户自提」——语义是「商家已备货、用户可来取」
        assertThat(WxLogisticsTypes.of(Fulfillments.STORE_PICKUP)).isEqualTo(4);
        assertThat(WxLogisticsTypes.of(Fulfillments.NEIGHBOR_PICKUP)).isEqualTo(4);
        // 服务类是 3「虚拟商品」——它们没有物流
        assertThat(WxLogisticsTypes.of(Fulfillments.STORE_VERIFY)).isEqualTo(3);
        assertThat(WxLogisticsTypes.of(Fulfillments.APPOINTMENT)).isEqualTo(3);
    }

    @Test
    @DisplayName("★★★ 认不出来返回 0，绝不兜默认值")
    void unknownFulfillmentIsNotGuessed() {
        assertThat(WxLogisticsTypes.of("SOMETHING_NEW")).isZero();
        assertThat(WxLogisticsTypes.of(null)).isZero();
        /*
         * 兜 1（快递）会让所有这类单缺运单号而被微信拒 —— 至少看得见；
         * 兜 3（虚拟）则是**报上去了但语义错**，微信不会拒，
         * 于是没有任何地方会说一句。两种兜法都比返回 0 差。
         */
    }

    @Test
    @DisplayName("★★★ 服务类要在支付成功那一刻上报 —— 它们没有「发货」按钮可挂")
    void serviceFulfillmentsUploadOnPaid() {
        assertThat(WxLogisticsTypes.uploadOnPaid(Fulfillments.STORE_VERIFY)).isTrue();
        assertThat(WxLogisticsTypes.uploadOnPaid(Fulfillments.APPOINTMENT)).isTrue();
        // 其余四种都有各自的「发出去了」那一刻
        assertThat(WxLogisticsTypes.uploadOnPaid(Fulfillments.EXPRESS)).isFalse();
        assertThat(WxLogisticsTypes.uploadOnPaid(Fulfillments.STORE_PICKUP)).isFalse();
    }

    @Test
    @DisplayName("★★ 只有快递要运单号 —— 给自提填运单号会被微信拒")
    void onlyExpressNeedsTracking() {
        assertThat(WxLogisticsTypes.needsTracking(1)).isTrue();
        assertThat(WxLogisticsTypes.needsTracking(2)).isFalse();
        assertThat(WxLogisticsTypes.needsTracking(3)).isFalse();
        assertThat(WxLogisticsTypes.needsTracking(4)).isFalse();
    }
}
