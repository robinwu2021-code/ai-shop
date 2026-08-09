package ai.neargo.shop.settle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分账通道的**桩实现**（S4 接微信支付分账前的临时物，整体会被替换）。
 *
 * <p>{@link #failNext} 是给测试用的注入点。之所以放在这里而不是生产服务里：
 * 这个类注定要被真实网关整体替换，钩子会跟着一起消失；
 * 放进 {@code SettleServiceImpl} 的话，那个测试后门会永久留在核心资金代码里。
 */
@Component
public class StubSplitGateway implements SplitGateway {

    private static final Logger log = LoggerFactory.getLogger(StubSplitGateway.class);

    /** 下一次对这些子单的调用会失败。**仅测试使用**。 */
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public Result split(String subOrderNo, long amountMinor, String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] split failed");
        }
        log.info("[STUB] split {} amount={} req={}", subOrderNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }

    @Override
    public Result reverse(String subOrderNo, long amountMinor, String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] reverse failed");
        }
        log.info("[STUB] reverse {} amount={} req={}", subOrderNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }

    /** 仅测试：让下一次对该子单的调用失败。 */
    public void failNext(String subOrderNo) {
        failing.add(subOrderNo);
    }
}
