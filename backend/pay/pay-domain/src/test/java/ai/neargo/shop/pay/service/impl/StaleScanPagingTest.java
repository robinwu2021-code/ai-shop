package ai.neargo.shop.pay.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.ReconDiffMapper;
import ai.neargo.shop.pay.service.ReconService;
import ai.neargo.shop.pay.service.recon.ReconAxis;
import ai.neargo.shop.spi.pay.PayQueryPort;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 掉单巡检分页（TDD-支付服务-W3收单补完 E05）：十万行待查时一轮只取上限、按 id 游标续扫、每个渠道一轮有份额 ——
 * 之前是一次 {@code selectList} 全量拉回再逐笔调渠道查单。
 */
class StaleScanPagingTest {

    static {
        // 不起 Spring：手工登记表元数据，lambda 条件才能渲染成 SQL、取到参数
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StlPayment.class);
    }

    PaymentMapper payments = mock(PaymentMapper.class);
    PayQueryPort query = mock(PayQueryPort.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<ReconAxis> axes = mock(ObjectProvider.class);
    ReconServiceImpl service = new ReconServiceImpl(payments, mock(ReconDiffMapper.class), query, axes, 20,
            mock(RefundCapacityLedger.class));

    static List<StlPayment> rows(long fromId, int n, String... channels) {
        List<StlPayment> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StlPayment p = new StlPayment();
            p.setId(fromId + i);
            p.setPaymentNo("P" + (fromId + i));
            p.setOrderNo("O" + (fromId + i));
            p.setDirection(StlPayment.PAY);
            p.setStatus(StlPayment.PENDING);
            p.setPayChannel(channels[i % channels.length]);
            p.setOutTradeNo("T" + (fromId + i));
            p.setAmountMinor(100L);
            p.setCurrency("CNY");
            out.add(p);
        }
        return out;
    }

    @Test
    @DisplayName("★★★ 一轮只查上限条数，游标停在最后查过的那一行；下一轮从游标往后，扫到底再从头")
    void capsEachRunAndResumesFromCursor() {
        when(query.query(anyString(), anyString())).thenReturn(null);
        when(payments.selectList(any())).thenReturn(rows(1, ReconServiceImpl.STALE_PAGE, "WECHAT"),
                rows(1 + ReconServiceImpl.STALE_PAGE, 30, "WECHAT"));

        var first = service.checkStalePayments(System.currentTimeMillis(), new ReconService.ScanLimits(150, 1000));
        assertThat(first).hasSize(150);
        assertThat(service.staleCursor()).isEqualTo(150L);
        verify(query, times(150)).query(anyString(), anyString());

        when(payments.selectList(any())).thenReturn(rows(151, 50, "WECHAT"), List.of());
        var second = service.checkStalePayments(System.currentTimeMillis(), new ReconService.ScanLimits(150, 1000));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<StlPayment>> page = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(payments, times(2)).selectList(page.capture());
        AbstractWrapper<?, ?, ?> last = (AbstractWrapper<?, ?, ?>) page.getValue();
        last.getSqlSegment();
        assertThat(last.getParamNameValuePairs().values())
                .as("第二轮的查询从上次停下的 id 往后").contains(150L);
        assertThat(second).extracting(ReconService.Finding::paymentNo).first().isEqualTo("P151");
        assertThat(service.staleCursor()).as("扫到底了，下一轮从头").isZero();
    }

    @Test
    @DisplayName("★★ 每个渠道一轮有份额：一个渠道积压不会把别的渠道饿死，也不会一轮把它的查单接口打满")
    void perChannelBudget() {
        when(query.query(anyString(), anyString())).thenReturn(null);
        when(payments.selectList(any())).thenReturn(rows(1, 120, "WECHAT", "WECHAT", "WECHAT", "ALIPAY"), List.of());
        var found = service.checkStalePayments(System.currentTimeMillis(), new ReconService.ScanLimits(1000, 20));
        Map<String, Long> byChannel = found.stream().collect(Collectors.groupingBy(ReconService.Finding::payChannel,
                Collectors.counting()));
        assertThat(byChannel).containsEntry("WECHAT", 20L).containsEntry("ALIPAY", 20L);
    }
}
