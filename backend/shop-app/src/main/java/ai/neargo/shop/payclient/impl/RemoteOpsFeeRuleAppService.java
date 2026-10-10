package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.dto.FeeRuleVO;
import ai.neargo.shop.payclient.OpsFeeRuleAppService;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 费率的<b>远程</b>实现 —— 支付域独立形态（{@code shop.pay.deployment=standalone}）。
 *
 * <h2>这是分布式调用切换的第一刀</h2>
 * 选费率打头，因为它是<b>唯一一个反向依赖为 0 的只读能力</b>：
 * {@code FeeRuleServiceImpl} 一个业务侧 Port 都不依赖，
 * 切过去之后支付域不需要回调主应用。
 * 相比之下 {@code SettleServiceImpl} 依赖 5 个 Port、{@code PointsServiceImpl} 6 个 ——
 * 那些要等反向 Port 有远程实现之后才切得动。
 *
 * <p>接口与 {@link OpsFeeRuleAppService} 完全一致，controller 不知道自己调的是谁。
 * <b>这正是那一层 app service 的用处</b>：换形态只换这里注入的实现。
 *
 * <h2>远程调用失败怎么办：宁可报错，不要空列表</h2>
 * 调不通时抛 {@code BizException}，<b>不返回空列表</b>。
 * 空列表在费率这件事上是一句谎话 —— 运营看到「没有配置任何费率」，
 * 而实际是支付域没应答。他会照着那个页面去<b>新增一版费率</b>，
 * 而库里其实已经有了。
 *
 * <p>{@code effectiveRates} 更严重：空 Map 的下游是「这一格费率查不到，按 0 算」，
 * 也就是<b>零佣金</b>。一次网络抖动变成一批单少收佣金，而且不报错。
 */
@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOpsFeeRuleAppService implements OpsFeeRuleAppService {

    private static final Logger log = LoggerFactory.getLogger(RemoteOpsFeeRuleAppService.class);

    private final PayInternalApi pay;

    public RemoteOpsFeeRuleAppService(PayInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public List<FeeRuleVO> rules() {
        return read(pay::feeRules);
    }

    @Override
    public Map<String, Integer> effectiveRates(Long at) {
        /*
         * **时刻由这边决定，不让对面取 now。**
         * 两边各取一次「现在」的话，两个时刻之间跨过一次费率生效，
         * 算出来就是两套数 —— 而那种差异只在调费率的那一刻出现，
         * 事后完全复现不了。
         */
        long millis = at == null ? System.currentTimeMillis() : at;
        return read(() -> pay.effectiveRates(millis));
    }

    @Override
    public FeeRuleVO add(String businessMode, String trafficSource, Integer rateBp,
                         Long effectiveFrom, String remark) {
        /*
         * **写操作还没切**，这是有意的。
         *
         * 远程写有个进程内没有的问题：超时之后不知道成没成。
         * 而费率是「只增不改」—— 重试会多出一个版本，
         * 意味着一段时间的账按错的费率算，且两版都在历史里，事后分不清哪次是重试。
         *
         * 所以写要带幂等键，那是独立的一步。在它做完之前，
         * 独立形态下这个方法明确拒绝，而不是悄悄走一条没有幂等保护的远程写。
         */
        throw new UnsupportedOperationException(
                "费率新增在独立形态下还没有远程实现 —— 远程写要先有幂等键，"
                + "否则超时重试会多出一个费率版本。见本类注释。");
    }

    /** 只读调用。调不通一律抛 —— 不返回空集合，理由见类注释 */
    private <R> R read(Supplier<R> call) {
        try {
            return ServiceCalls.call(ServiceName.PAY, call);
        } catch (ServiceCallException e) {
            /*
             * 三种失败在日志里分开（改配置 / 等对方 / 看对方日志），
             * 但对运营都是同一句「暂时取不到」—— 页面上区分它们没有意义，
             * 而日志里不区分会让排查从第一步就走错方向。
             */
            log.error("[pay-remote] 取费率失败 outcome={} msg={}", e.outcome(), e.getMessage());
            throw BizException.of(ErrorCode.INTERNAL_ERROR);
        }
    }
}
