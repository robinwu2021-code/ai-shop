package ai.neargo.shop.food.dto;

import java.time.LocalDate;

/** Restaurant placement outcome; UNKNOWN is not order acceptance or payment success. */
public record DiningOrderBatchVO(String batchNo, String storeNo, String checkNo, String cartNo,
        long cartVersion, String quoteNo, String status, String payMode, LocalDate businessDate,
        String orderNo, String subOrderNo, String coreStatus, String currency, Long amountMinor, long version) { }
