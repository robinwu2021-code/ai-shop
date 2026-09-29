package ai.neargo.shop.elec.support;

import ai.neargo.shop.elec.gateway.ElecAlerts.RfqAlert;
import ai.neargo.shop.elec.gateway.ElecAlerts.RfqLine;
import ai.neargo.shop.elec.gateway.ElecAlerts.Source;
import ai.neargo.shop.elec.gateway.ElecAlerts.SupplierAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推到企业微信群的正文。第一步<b>群就是运营的工作台</b>，
 * 这条消息要能直接拿来干活：买家要什么、打谁的电话、库里谁有货。
 */
class AlertTextTest {

    @Test
    @DisplayName("★★★ 询价消息：买家完整手机号、每行料号数量、谁有货（公司+电话+数量+价）、没货的标出来")
    void rfqMessageIsActionable() {
        String msg = AlertText.rfq(new RfqAlert("EQ1", "王工", "13800001111", "某某科技",
                "VAT_SPECIAL", "Y2", "深圳", null, List.of(
                new RfqLine("STM32F103C8T6", "ST", 2000, 6_500_000L, List.of(
                        new Source("深圳甲电子", "13900002222", 5000, "2338", 6_200_000L, true))),
                new RfqLine("CH340N", null, 100, null, List.of()))));
        assertThat(msg).as("群里没有别的地方能查到号码，所以不掩码").contains("13800001111");
        assertThat(msg).contains("STM32F103C8T6").contains("× 2000").contains("目标 ¥6.5");
        assertThat(msg).contains("深圳甲电子").contains("13900002222").contains("5000").contains("¥6.2含税");
        assertThat(msg).contains("专票").contains("两年内").contains("深圳");
        assertThat(msg).as("库里没货的行要让运营一眼看出要去找货").contains("库里没货");
    }

    @Test
    @DisplayName("★★ 行数多的 BOM 只列前 12 行，不让消息超过企微 4096 字节的上限")
    void longRfqIsTruncated() {
        List<RfqLine> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(new RfqLine("PART" + i, null, 10, null, List.of()));
        }
        String msg = AlertText.rfq(new RfqAlert("EQ2", null, "13800001111", null, "NONE", "ANY", null, null, lines));
        assertThat(msg).contains("PART11").doesNotContain("PART12").contains("还有 18 行");
        assertThat(msg.getBytes(StandardCharsets.UTF_8).length).isLessThan(4096);
    }

    @Test
    @DisplayName("★★ 供应商消息：点一下就成为供应商时公司名还没有，要写「还没填」而不是 null")
    void supplierWithoutCompany() {
        String msg = AlertText.supplier(new SupplierAlert("ES1", null, "TRADER", null, null, "13700003333"));
        assertThat(msg).contains("还没填").contains("13700003333").doesNotContain("null");
    }
}
