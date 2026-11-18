package ai.neargo.shop.gateway;

import ai.neargo.shop.gateway.biz.BizGatewayEnd;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.security.GatewayCorsConfiguration;
import ai.neargo.shop.gateway.core.security.PublicGatewayChain;
import ai.neargo.shop.gateway.core.security.SpringSecurityAdapter;
import ai.neargo.shop.gateway.mp.MpGatewayEnd;
import ai.neargo.shop.gateway.ops.OpsGatewayEnd;
import ai.neargo.shop.gateway.ops.PayOpsGatewayEnd;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * 网关层认证链的装配入口：三端、支付运营面与公共地址的链都从这里进。
 *
 * <p>被引入的类故意不带 {@code @Configuration} / {@code @Component}：它们只经这里装配，
 * 于是下面这一个「只在 Web 应用里装」的条件对它们全部生效，不用每个类各写一遍。
 * 只装安全链的切片测试列出本类即可。
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity   // 开 @PreAuthorize("@perm.can('码')")
/*
 * ⚠️ **只在 Web 应用里装**。
 *
 * worker profile 把 web-application-type 设成 none（批量任务与 API 抢线程池时，
 * 结算跑一轮能把下单拖到超时）。没有这个条件的话，Spring 仍会去建 consumerChain，
 * 而 HttpSecurity 在非 Web 上下文里根本不存在 —— **worker 直接起不来**，
 * 报的是「No qualifying bean of type HttpSecurity」，一个字都不提 profile。
 *
 * 这个缺陷躺了很久没被发现，因为 **worker 从来没被部署过**：
 * 生产跑 api,ops，而 14 个定时任务全挂在 worker 上。
 * 「默认关着的那一半没人测」——而关着的那一半恰恰是要启用的那一半。
 */
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GatewayProperties.class)
@Import({GatewayCorsConfiguration.class, SpringSecurityAdapter.class, PublicGatewayChain.class,
        MpGatewayEnd.class, BizGatewayEnd.class, OpsGatewayEnd.class, PayOpsGatewayEnd.class})
public class GatewaySecurityConfiguration {
}
