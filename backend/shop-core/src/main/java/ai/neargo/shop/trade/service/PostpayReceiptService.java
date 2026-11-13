package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.trade.PostpayReceiptPort.Confirm;
import ai.neargo.shop.spi.trade.PostpayReceiptPort.Receipt;

/** 行业后付单的人工实收确认，合同见 shop-base 的 PostpayReceiptPort。 */
public interface PostpayReceiptService {
    Receipt confirm(Confirm command);
}
