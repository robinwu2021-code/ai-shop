package ai.neargo.shop.retail.commerce.service;

import ai.neargo.shop.spi.commerce.ChannelOrderIngestPort;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort;

/**
 * The original retail owner bridges CIM imports to actual core channel orders without an ordinary
 * checkout or Food basket.
 */
public interface RetailChannelOrderService
        extends ChannelOrderIngestPort, IndustryChannelOrderSourcePort {}
