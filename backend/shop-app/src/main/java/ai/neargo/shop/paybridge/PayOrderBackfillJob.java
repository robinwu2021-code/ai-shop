package ai.neargo.shop.paybridge;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.job.api.JobHandler;
import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.JobResult;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 回填 B6 / B7 的调度壳（TDD-支付服务-I3支付单退款流水 S9）：<b>只手动触发</b>，上线后跑到「没有待回填」为止。
 * 支付域读不到交易表，订单属于商城还是餐饮、门店是哪家在这里查好交过去。
 */
@Component
@ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
public class PayOrderBackfillJob implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(PayOrderBackfillJob.class);

    @Value("${shop.job.pay-order-backfill.batch:500}")
    private int batch;

    private final PayOrderBackfill backfill;
    private final MerchantQueryPort merchants;
    private final JdbcTemplate jdbc;

    public PayOrderBackfillJob(PayOrderBackfill backfill, MerchantQueryPort merchants, JdbcTemplate jdbc) {
        this.backfill = backfill;
        this.merchants = merchants;
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "pay-order-backfill";
    }

    @Bean
    public JobDeclaration payOrderBackfillDeclaration() {
        return new JobDeclaration("pay-order-backfill", "支付单回填（B6 / B7）",
                "把 I3 之前的收款尝试按订单号归到支付单下，并按资金路径回放流水期初。幂等，可重复跑；只手动触发",
                "shop-app", "0 0 4 1 1 ?", false, 3600, 3600, true, true);
    }

    @Override
    public JobResult run(JobInvocation invocation) {
        int done = 0;
        int skipped = 0;
        List<String> orderNos = backfill.pendingOrderNos(batch);
        for (String orderNo : orderNos) {
            if (backfillOne(orderNo)) {
                done++;
            } else {
                skipped++;
            }
        }
        String detail = "回填 %d 单｜解析不出门店跳过 %d 单（本批 %d）".formatted(done, skipped, orderNos.size());
        return skipped > 0 ? JobResult.failed(detail, "跳过的要人工看：子单没有门店或门店已删") : JobResult.ok(detail);
    }

    /** 订单的门店 = 首个子单的门店；行业单（餐饮）有来源绑定行 */
    boolean backfillOne(String orderNo) {
        List<String> stores = jdbc.queryForList("SELECT store_no FROM ord_sub_order WHERE order_no=? ORDER BY sub_order_no",
                String.class, orderNo);
        var store = stores.isEmpty() ? Optional.<MerchantQueryPort.PayStore>empty()
                : merchants.payStoreOf(stores.get(0));
        if (store.isEmpty()) {
            log.warn("[pay-order-backfill] 订单 {} 解析不出门店，跳过", orderNo);
            return false;
        }
        Integer industry = jdbc.queryForObject("SELECT COUNT(*) FROM ord_industry_order_source WHERE order_no=?",
                Integer.class, orderNo);
        var s = store.get();
        backfill.backfill(orderNo, industry != null && industry > 0 ? PaymentPort.FOOD_CHECK : PaymentPort.MALL_ORDER,
                s.storeId(), s.storeName(), s.entityName(), s.market(), s.fundsPath());
        return true;
    }
}
