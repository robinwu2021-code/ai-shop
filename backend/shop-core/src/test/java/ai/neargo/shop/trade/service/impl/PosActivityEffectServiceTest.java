package ai.neargo.shop.trade.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Coverage;
import ai.neargo.shop.trade.mapper.PosActivitySaleMapper.Line;
import ai.neargo.shop.trade.service.PosActivityEffectService.Metrics;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PosActivityEffectServiceTest {
    private static final ZoneId ZONE=ZoneId.of("Asia/Taipei");
    private static final LocalDate FIRST=LocalDate.of(2026,11,21), LAST=FIRST.plusDays(6);
    private static long at(int day) { return FIRST.withDayOfMonth(day).atTime(12,0).atZone(ZONE).toInstant().toEpochMilli(); }
    private Line row(String order,String currency,int day,Boolean member,int qty,long paid,long discount,long refundQty,long refund) {
        return new Line(order,currency,at(day),member,qty,paid,discount,refundQty,refund,false,false);
    }

    @Test void sevenDaysAcrossVersionsAndCurrenciesReturnRefundsToThePaidDay() {
        var coverage=List.of(new Coverage("TWD",at(20)),new Coverage("USD",at(20)));
        var rows=List.of(row("O1","TWD",21,true,1,600,100,0,0),row("O1","TWD",21,true,1,250,0,0,0),
                row("O2","TWD",22,false,3,540,60,1,180),row("O3","TWD",22,true,1,450,50,1,450),
                row("O4","TWD",26,false,1,900,100,0,0),row("O5","USD",27,true,1,190,10,0,0));
        var buckets=PosActivityEffectServiceImpl.aggregate(FIRST,LAST,ZONE,coverage,rows).currencies();
        var tw=buckets.getFirst();
        assertThat(tw.days()).extracting(Metrics::orders).containsExactly(1L,1L,0L,0L,0L,1L,0L);
        assertThat(tw.total().orders()).isEqualTo(3);
        assertThat(tw.total().netPaidMinor()).isEqualTo(2110);
        assertThat(tw.total().discountMinor()).isEqualTo(240);
        assertThat(tw.total().averagePaidMinor()).isEqualByComparingTo("703.33");
        assertThat(tw.total().averageDiscountMinor()).isEqualByComparingTo("80");
        assertThat(tw.total().memberPercent()).isEqualByComparingTo("33.33");
        assertThat(tw.total().discountPercent()).isEqualByComparingTo("11.37");
        assertThat(tw.days().get(2).memberPercent()).isNull();
        assertThat(buckets.get(1).total().discountPercent()).isEqualByComparingTo("5.26");
        var other=PosActivityEffectServiceImpl.aggregate(FIRST,LAST,ZONE,coverage,
                List.of(row("O1","TWD",21,true,1,850,50,0,0))).currencies().getFirst().total();
        assertThat(other.discountPercent()).isEqualByComparingTo("5.88");
    }

    @Test void unknownHistoryAndRefundedParticipatingLinesAreNotCountedAsSales() {
        var rows=List.of(row("legacy","TWD",22,null,1,850,0,0,0),
                row("partial","TWD",25,true,1,450,50,1,450),row("partial","TWD",25,true,1,200,0,0,0));
        var result=PosActivityEffectServiceImpl.aggregate(FIRST,LAST,ZONE,List.of(new Coverage("TWD",at(22))),rows)
                .currencies().getFirst();
        assertThat(result.days().get(0).orders()).isNull();
        assertThat(result.days().get(1).orders()).isNull();
        assertThat(result.days().get(4).orders()).isZero();
        assertThat(result.total().covered()).isFalse();
    }

    @Test void rejectsOutOfScopeStoreBeforeReadingTransactions() {
        var mapper=mock(PosActivitySaleMapper.class);
        var service=new PosActivityEffectServiceImpl(mapper,mock(MerchantScopeFactsPort.class));
        assertThatThrownBy(()->service.effect(new ai.neargo.shop.trade.service.PosActivityEffectService.Scope("T","M",Set.of("S1")),"S2","A",FIRST,LAST,ZONE))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(mapper);
    }
}
