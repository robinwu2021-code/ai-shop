package ai.neargo.shop.paybridge.port;

import ai.neargo.shop.paybridge.PayMerchantComparator;
import ai.neargo.shop.spi.pay.PayMerchantReadSwitch;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@link PayMerchantReadSwitch} 的实现：配置要 {@code pay} 时，启动完成后跑一次全量比对 C07，
 * 未确认差异为 0 才打开门禁；之前、比对失败、有差异都按旧解析（读切是可回退的，开错了的代价是钱打错户）。
 */
@Component
public class PayMerchantReadSwitchImpl implements PayMerchantReadSwitch {

    private static final Logger log = LoggerFactory.getLogger(PayMerchantReadSwitchImpl.class);

    private final PayMerchantComparator comparator;
    private final Set<String> confirmed;
    private volatile String requested;
    private volatile boolean gateOpen;

    public PayMerchantReadSwitchImpl(PayMerchantComparator comparator,
                                     @Value("${shop.pay.merchant.read-from:legacy}") String requested,
                                     @Value("${shop.pay.merchant.compare.confirmed:}") List<String> confirmed) {
        if (!"legacy".equals(requested) && !"pay".equals(requested)) {
            throw new IllegalStateException("shop.pay.merchant.read-from 只能是 legacy / pay，实际是 " + requested);
        }
        this.comparator = comparator;
        this.requested = requested;
        this.confirmed = Set.copyOf(confirmed.stream().filter(k -> !k.isBlank()).toList());
    }

    @Override
    public boolean readFromPay() {
        return "pay".equals(requested) && gateOpen;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if ("pay".equals(requested)) {
            refreshGate();
        }
    }

    /** 重跑全量比对、刷新门禁；返回门禁是否打开 */
    public boolean refreshGate() {
        try {
            PayMerchantComparator.Result r = comparator.compare(List.of(), confirmed);
            gateOpen = r.readSwitchAllowed();
            if (gateOpen) {
                log.info("[pay-merchant-read] 全量比对 {} 个门店 × 渠道无未确认差异，收款商户改由支付新模型解析", r.checked());
            } else {
                log.error("[pay-merchant-read] 配置要读切，但全量比对还有 {} 条未确认差异，仍按旧解析；先跑 pay-merchant-compare",
                        r.unconfirmed());
            }
        } catch (RuntimeException ex) {
            gateOpen = false;
            log.error("[pay-merchant-read] 全量比对失败，仍按旧解析", ex);
        }
        return gateOpen;
    }
}
