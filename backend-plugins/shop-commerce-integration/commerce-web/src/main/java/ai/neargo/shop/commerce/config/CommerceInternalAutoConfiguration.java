package ai.neargo.shop.commerce.config;

import ai.neargo.shop.commerce.api.internal.InternalChannelCatalogAuthorityController;
import ai.neargo.shop.commerce.service.ChannelCatalogAuthorityService;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Import;

/** Internal contracts are available in either deployment without opening the public BFF. */
@AutoConfiguration(afterName = "ai.neargo.shop.commerce.config.CommerceCoreAutoConfiguration")
@ConditionalOnProperty(prefix = "shop.commerce", name = "enabled", havingValue = "true")
@ConditionalOnBean(ChannelCatalogAuthorityService.class)
@Import(InternalChannelCatalogAuthorityController.class)
public class CommerceInternalAutoConfiguration {}
