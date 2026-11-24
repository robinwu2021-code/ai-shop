package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.gateway.biz.PayBizPassThrough;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 开关默认关（TDD-支付三端经网关透传与权限归属 §3.1、§5 阶段 1）：不装透传，{@code /biz/pay/v2/**} 仍由现有的转发入口处理。
 * 用默认测试上下文，不另起容器。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("网关 · B 端支付接口透传默认关")
class PayBizPassThroughOffTest {

    @Autowired
    ApplicationContext beans;

    @Test
    @DisplayName("开关关：没有透传 bean")
    void offByDefault() {
        assertThat(beans.getBeanProvider(PayBizPassThrough.class).getIfAvailable()).isNull();
    }
}
