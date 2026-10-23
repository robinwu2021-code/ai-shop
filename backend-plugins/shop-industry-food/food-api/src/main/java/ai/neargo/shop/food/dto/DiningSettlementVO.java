package ai.neargo.shop.food.dto;

import ai.neargo.shop.spi.trade.IndustryCheckoutPort.PaymentVO;

/** Single original-root settlement; paid cash and kitchen acceptance remain separate facts. */
public record DiningSettlementVO(String settlementNo, String storeNo, String batchNo, String checkNo,
        String orderNo, String subOrderNo, String status, String currency, long dueMinor, String scopeHash,
        String confirmationDigest, long version, String batchStatus, String coreOrderStatus,
        PaymentVO payment, boolean readyToAccept) { }
