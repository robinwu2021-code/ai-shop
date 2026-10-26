package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.paybridge.PayMerchantBackfillRunner;
import ai.neargo.shop.spi.pay.PayMerchantMirrorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link PayMerchantMirrorPort} 的实现：开关开着就按主体重放一遍回填规则（{@link PayMerchantBackfillRunner#sync}）。
 * 一个主体几行旧数据，整体重放比逐个写点翻译简单，也不会漏掉某个写点。
 */
@Component
public class PayMerchantMirrorPortImpl implements PayMerchantMirrorPort {

    private static final Logger log = LoggerFactory.getLogger(PayMerchantMirrorPortImpl.class);

    private volatile boolean enabled;
    private PayMerchantBackfillRunner runner;

    public PayMerchantMirrorPortImpl(PayMerchantBackfillRunner runner,
                                     @Value("${shop.pay.merchant.dual-write:false}") boolean enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    @Override
    public void legacyChanged(String entityNo) {
        if (!enabled) {
            return;
        }
        PayMerchantBackfillRunner.Report r = runner.sync(entityNo);
        r.conflicts().forEach(c -> log.warn("[pay-merchant-dual-write] {} {}", entityNo, c));
    }
}
