package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.biz.PayBizFacade.QrView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 独立部署时 B 端收款码由 near-pay 返回、ai-shop 用 {@link QrView} 反序列化后转给 App。
 * 2026-11-07 用户「加，App 展示立牌链接」：near-pay 加了 storeCodeUrl，这里少了字段就会被静默丢掉。
 */
class QrViewStoreCodeUrlTest {

    @Test
    @DisplayName("★★ near-pay 返回的立牌链接 storeCodeUrl 透传到 App，不被丢掉")
    void storeCodeUrlSurvivesTheHop() {
        var mapper = JsonMapper.builder().build();
        String fromNearPay = "{\"qrCode\":\"PiGozb\",\"kind\":\"STORE\",\"currency\":\"TWD\",\"singleLimitMinor\":500000,"
                + "\"dailyLimitMinor\":5000000,\"status\":\"ACTIVE\",\"url\":\"https://twpay.umeshop.ai/q/PiGozb\","
                + "\"storeCodeUrl\":\"https://twpay.umeshop.ai/h5/s/taipei-breakfast\"}";
        QrView v = mapper.readValue(fromNearPay, QrView.class);
        assertThat(v.storeCodeUrl()).isEqualTo("https://twpay.umeshop.ai/h5/s/taipei-breakfast");
        assertThat(mapper.writeValueAsString(v)).contains("\"storeCodeUrl\":\"https://twpay.umeshop.ai/h5/s/taipei-breakfast\"");
    }
}
