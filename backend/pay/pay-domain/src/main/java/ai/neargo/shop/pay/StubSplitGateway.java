package ai.neargo.shop.pay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分账通道的**桩实现**（S4 接微信支付分账前的临时物，整体会被替换）。
 *
 * <p>⚠️ <b>它模拟的是「发指令」，不是「钱到账」。</b>
 * 所以 {@link #split} 返回成功只表示<b>受理成功</b>，结算单因此落在
 * {@code SPLIT}（指令已发出）而<b>永远不会进 {@code SPLIT_CONFIRMED}</b> ——
 * 后者只能由通道回执经 {@code SettleService.confirmSplit} 产生。
 *
 * <p>这一点是刻意的：桩时代的每一笔「已分账」实际上一分钱都没动过。
 * 让它自己把单子推到终态，等于用一个桩把整本账做平 ——
 * 之后再也分不清哪些钱真的到了。接真通道时，替换的是本类，
 * 而 {@code confirmSplit} 那条路本来就在等着回执。
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
    public Result split(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] split failed");
        }
        // 收款号打进日志：接真通道之前，这是唯一能验证「钱打给谁」路由对不对的地方
        log.info("[STUB] split {} to={} amount={} req={}", subOrderNo, payMerchantNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }

    @Override
    public Result reverse(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] reverse failed");
        }
        log.info("[STUB] reverse {} from={} amount={} req={}", subOrderNo, payMerchantNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }

    /** 仅测试：让下一次对该子单的调用失败。 */
    public void failNext(String subOrderNo) {
        failing.add(subOrderNo);
    }

    @Override
    public Result subsidy(String subOrderNo, String payMerchantNo, long amountMinor, String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] subsidy failed");
        }
        log.info("[STUB] subsidy {} to={} amount={} req={}", subOrderNo, payMerchantNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }

    @Override
    public Result subsidyReturn(String subOrderNo, String payMerchantNo, long amountMinor,
                                String requestNo) {
        if (failing.remove(subOrderNo)) {
            return Result.fail("[STUB] subsidyReturn failed");
        }
        log.info("[STUB] subsidyReturn {} from={} amount={} req={}",
                subOrderNo, payMerchantNo, amountMinor, requestNo);
        return Result.ok("STUB-" + requestNo);
    }
}
