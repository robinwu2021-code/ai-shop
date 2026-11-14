package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.spi.pay.PayApplymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * F12 · Review R13：打开支付宝开关，应用照样起得来（TDD-支付服务-I1存量缺陷）。
 *
 * <p>两个支付宝网关依赖 {@code alipayChannelClient}，而这个 bean 全仓没有定义 ——
 * 此前一开 {@code shop.pay.alipay.enabled} 整个应用就因找不到 bean 起不来。
 * 客户端实现之前（开发计划 I8），支付宝按「未接通」处理：不装网关，进件 / 支付都明确报渠道不可用。
 */
@SpringBootTest(properties = {
        "shop.pay.alipay.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:alipay-switch;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class AlipaySwitchBootTest {

    @Autowired
    private ApplicationContext ctx;

    @Test
    @DisplayName("★★★ F12 支付宝开关打开，上下文照样装得起来；客户端没实现时不装支付宝网关")
    void bootsWithAlipaySwitchOn() {
        assertThat(ctx.getBeansOfType(PayGateway.class).values())
                .extracting(PayGateway::payChannel).doesNotContain("ALIPAY");
        assertThat(ctx.getBeansOfType(PayApplymentGateway.class).values())
                .extracting(PayApplymentGateway::payChannel).doesNotContain("ALIPAY");
    }
}
