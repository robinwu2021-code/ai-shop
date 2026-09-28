package ai.neargo.shop.channel.express.port;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.ExpressCompanies;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 快递100 的签名与报文。期望的签名值是用 Python hashlib 独立算出来的，不是拿被测代码自己算一遍再比。
 */
@DisplayName("快递100 网关：签名、回调解析、单位换算、公司码")
class Kuaidi100PickupGatewayTest {

    private static final String CB = "{\"kuaidicom\":\"zhongtong\",\"kuaidinum\":\"ZT123\",\"status\":\"200\","
            + "\"message\":\"成功\",\"data\":{\"orderId\":\"O1\",\"status\":10,\"courierName\":\"王大\","
            + "\"courierMobile\":\"13800138000\",\"weight\":\"2.5\",\"defPrice\":\"15.0\",\"freight\":\"5.1\"}}";

    @Test
    @DisplayName("★★ 请求签名 = MD5(param + t + key + secret) 大写")
    void requestSign() {
        assertThat(Kuaidi100PickupGateway.requestSign("{\"kuaidicom\":\"zhongtong\"}", "1700000000000", "KEY", "SECRET"))
                .isEqualTo("2CBF8E5C0B184D21AC4DE2C632D4D38A");
    }

    @Test
    @DisplayName("★★★ 回调：签名对才解析；状态取 data.status，运单号、运费、计费重量都换成我们的单位")
    void callbackParsed() {
        var cb = Kuaidi100PickupGateway.parse("TASK1", "7F5668A04E442C73C42DEC314AF17008", CB, "SALT").orElseThrow();
        assertThat(cb.providerStatus()).as("外层 status 是推送结果码（200），状态码在 data 里").isEqualTo(10);
        assertThat(cb.trackingNo()).isEqualTo("ZT123");
        assertThat(cb.freightMinor()).isEqualTo(510L);
        assertThat(cb.listPriceMinor()).isEqualTo(1500L);
        assertThat(cb.chargedWeightG()).isEqualTo(2500);
        assertThat(cb.courierName()).isEqualTo("王大");
        assertThat(cb.taskId()).isEqualTo("TASK1");
    }

    @Test
    @DisplayName("★★★ 回调签名不对、盐不对、报文被改一个字：一律不认")
    void callbackRejected() {
        assertThat(Kuaidi100PickupGateway.parse("T", "7F5668A04E442C73C42DEC314AF17008", CB, "OTHER")).isEmpty();
        assertThat(Kuaidi100PickupGateway.parse("T", "7F5668A04E442C73C42DEC314AF17008",
                CB.replace("5.1", "0.1"), "SALT")).isEmpty();
        assertThat(Kuaidi100PickupGateway.parse("T", null, CB, "SALT")).isEmpty();
    }

    @Test
    @DisplayName("★ 元→分、公斤→克：空与坏值是「没给」，不当成 0")
    void units() {
        assertThat(Kuaidi100PickupGateway.minor("12.345")).isEqualTo(1235L);
        assertThat(Kuaidi100PickupGateway.minor("")).isNull();
        assertThat(Kuaidi100PickupGateway.minor("abc")).isNull();
        assertThat(Kuaidi100PickupGateway.grams("0.1")).isEqualTo(100);
        assertThat(Kuaidi100PickupGateway.kg(2500)).isEqualTo("2.5");
        assertThat(Kuaidi100PickupGateway.kg(3000)).isEqualTo("3");
    }

    @Test
    @DisplayName("★★ 公司码：键都是微信认得的码；不放顺丰（微信上报要收件人手机号，还没接）")
    void carrierCodes() {
        assertThat(Kuaidi100PickupGateway.CODES.keySet()).allMatch(ExpressCompanies::isValid);
        assertThat(Kuaidi100PickupGateway.CODES).doesNotContainKey("SF");
    }
}
