package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.callback.ChannelInboundCallbackController;
import ai.neargo.shop.commerce.service.ChannelInboundService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Callbacks are an explicit independent capability, without enabling the public merchant BFF. */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(name = "shop.commerce.callbacks.enabled", havingValue = "true")
@ConditionalOnBean(ChannelInboundService.class)
@Import(ChannelInboundCallbackController.class)
public class CommerceInboundAutoConfiguration {}
