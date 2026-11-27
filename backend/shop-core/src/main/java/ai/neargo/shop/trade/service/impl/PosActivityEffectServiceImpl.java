package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Line;
import ai.neargo.shop.trade.service.PosActivityEffectService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosActivityEffectServiceImpl implements PosActivityEffectService {
    private final PosActivitySaleMapper facts;
    private final MerchantScopeFactsPort merchants;

    public PosActivityEffectServiceImpl(PosActivitySaleMapper facts, MerchantScopeFactsPort merchants) {
        this.facts=facts; this.merchants=merchants;
    }

    @Transactional(readOnly=true)
    public Effect effect(Scope actor,String store,String activity,LocalDate from,LocalDate through,ZoneId zone) {
        if (through.isBefore(from) || through.isAfter(from.plusDays(365))) throw BizException.of(ErrorCode.BAD_REQUEST);
        if (actor.allowedStores()!=null && !actor.allowedStores().contains(store)
                || !merchants.storesOwned(actor.tenantNo(),actor.entityNo(),Set.of(store))) throw BizException.of(ErrorCode.NOT_FOUND);
        var end=through.plusDays(1).atStartOfDay(zone).toInstant();
        long start=from.atStartOfDay(zone).toInstant().toEpochMilli();
        return DataScopeContext.executeWithoutScope(() -> {
            var coverage=facts.coverage(actor.tenantNo(),actor.entityNo(),store,end.toEpochMilli());
            var lines=facts.lines(actor.tenantNo(),actor.entityNo(),store,activity,start,end.toEpochMilli(),
                    LocalDateTime.ofInstant(end,ZoneOffset.UTC));
            return aggregate(from,through,zone,coverage,lines);
        });
    }

    static Effect aggregate(LocalDate from,LocalDate through,ZoneId zone,List<PosActivitySaleMapper.Coverage> coverage,List<Line> lines) {
        var buckets=new ArrayList<Bucket>();
        for (var source: coverage.stream().sorted(Comparator.comparing(PosActivitySaleMapper.Coverage::currency)).toList()) {
            var days=new LinkedHashMap<LocalDate,Totals>();
            for (var date=from; !date.isAfter(through); date=date.plusDays(1)) {
                var t=new Totals();
                t.covered=source.firstPaid()!=null && !date.isBefore(Instant.ofEpochMilli(source.firstPaid()).atZone(zone).toLocalDate());
                days.put(date,t);
            }
            var orders=lines.stream().filter(l -> source.currency().equals(l.currency())).collect(Collectors.groupingBy(Line::subNo));
            for (var order:orders.values()) {
                var first=order.getFirst();
                var day=days.get(Instant.ofEpochMilli(first.paidAt()).atZone(zone).toLocalDate());
                if (first.memberAtPayment()==null) {day.covered=false;continue;}
                if (order.stream().noneMatch(l -> l.discount()>0)) continue;
                if (first.missingReturn()) {day.covered=false;continue;}
                if (first.cashReturned()) continue;
                long paid=0,discount=0;
                boolean retained=false;
                for (var line:order) {
                    if (line.refundQty()>line.qty() || line.refundMinor()>line.paid()) {day.covered=false;break;}
                    paid=Math.addExact(paid,line.paid()-line.refundMinor());
                    if (line.discount()>0 && line.refundQty()<line.qty()) {
                        retained=true;
                        long reversed=BigInteger.valueOf(line.discount()).multiply(BigInteger.valueOf(line.refundQty()))
                                .divide(BigInteger.valueOf(line.qty())).longValueExact();
                        discount=Math.addExact(discount,line.discount()-reversed);
                    }
                }
                if (retained) {
                    day.orders++; day.paid=Math.addExact(day.paid,paid); day.discount=Math.addExact(day.discount,discount);
                    if (first.memberAtPayment()) day.members++;
                }
            }
            var total=new Totals();
            for(var day:days.values()) total.add(day);
            buckets.add(new Bucket(source.currency(),days.entrySet().stream().map(e->e.getValue().metrics(e.getKey())).toList(),total.metrics(null)));
        }
        return new Effect(from,through,zone.getId(),List.copyOf(buckets));
    }

    private static final class Totals {
        boolean covered=true;
        long orders,paid,discount,members;
        void add(Totals day) {
            covered &= day.covered;
            orders+=day.orders; paid=Math.addExact(paid,day.paid); discount=Math.addExact(discount,day.discount); members+=day.members;
        }
        Metrics metrics(LocalDate date) {
            if (!covered) return new Metrics(date,false,null,null,null,null,null,null,null,null);
            return new Metrics(date,true,orders,paid,discount,members,ratio(paid,orders,1),ratio(discount,orders,1),
                    ratio(members,orders,100),ratio(discount,paid,100));
        }
        static BigDecimal ratio(long numerator,long denominator,int scale) {
            return denominator==0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(scale))
                    .divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP);
        }
    }
}
