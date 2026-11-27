package ai.neargo.shop.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public interface PosActivityEffectService {
    record Scope(String tenantNo,String entityNo,java.util.Set<String> allowedStores) {}
    record Metrics(LocalDate date, boolean covered, Long orders, Long netPaidMinor, Long discountMinor, Long memberOrders,
            BigDecimal averagePaidMinor, BigDecimal averageDiscountMinor, BigDecimal memberPercent, BigDecimal discountPercent) {}
    record Bucket(String currency,List<Metrics> days,Metrics total) {}
    record Effect(LocalDate from,LocalDate through,String zone,List<Bucket> currencies) {}
    Effect effect(Scope actor,String store,String activity,LocalDate from,LocalDate through,ZoneId zone);
}
