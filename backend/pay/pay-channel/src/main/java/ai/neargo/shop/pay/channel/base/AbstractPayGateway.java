package ai.neargo.shop.pay.channel.base;

import ai.neargo.shop.pay.channel.ChannelClient;
import ai.neargo.shop.pay.channel.ChannelClient.ChannelException;
import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.spi.platform.MasterDataPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 交易网关的骨架。**加一个通道 = 写一个子类填空**，流程不用重写。
 *
 * <p><b>为什么要有这一层</b>：此前每个实现各自重复「调用 → 判成败 → 归一错误码 →
 * 捕获 ChannelException 转 Result」。再加两个通道就是再抄两遍，
 * 而抄漏一处的表现是<b>某个通道的错误码没归一，上层把失败当成功</b> ——
 * 那不是报错，是一笔钱以为发出去了。
 *
 * <p><b>更要紧的是这一层补了两件此前没人做的事</b>：
 * <ul>
 *   <li><b>能力位检查</b>：{@code sys_pay_channel.supports_subsidy} 这类开关此前只在
 *       结算侧看过一次。不支持补差的通道如果从别的路径进来，请求会真发出去；</li>
 *   <li><b>资金动作留痕</b>：每次补差/分账/退款调用都打一行结构化日志。
 *       此前只有失败时才有信息，成功的资金动作在日志里<b>一行都没有</b>。</li>
 * </ul>
 *
 * <p><b>模板方法一律 final。</b>「能力位 → 构造 → 调用 → 解析 → 留痕」这个顺序不是风格：
 * 少了第一步，不支持的操作会真发给通道；少了最后一步，资金动作没有痕迹。
 * 允许子类覆写整个方法，等于允许它跳过这两步 —— 而跳过不报错，要到对账那天才看得出来。
 */
public abstract class AbstractPayGateway implements PayGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractPayGateway.class);

    /** 五个资金动作。能力位与留痕都按它分。 */
    public enum Op {
        SUBSIDY("补差"), SUBSIDY_RETURN("补差回退"),
        SPLIT("分账"), SPLIT_REVERSE("分账回退"),
        REFUND("退款");

        private final String label;

        Op(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一次通道调用的三要素。
     *
     * @param api     接口坐标（微信是路径，支付宝是接口名）
     * @param body    业务参数，**不含密钥**
     * @param idField 回执里哪个字段是通道单号
     */
    public record Call(String api, Map<String, Object> body, String idField) {
    }

    protected final ChannelClient client;
    private final MasterDataPort masterData;

    protected AbstractPayGateway(ChannelClient client, MasterDataPort masterData) {
        this.client = client;
        this.masterData = masterData;
    }

    // ------------------------------------------------------------ 模板方法

    @Override
    public final Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        return exec(Op.SUBSIDY, ctx, amountMinor, requestNo,
                () -> buildSubsidy(ctx, amountMinor, requestNo, description));
    }

    @Override
    public final Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        return exec(Op.SUBSIDY_RETURN, ctx, amountMinor, requestNo,
                () -> buildSubsidyReturn(ctx, amountMinor, requestNo, description));
    }

    @Override
    public final Result split(TxContext ctx, long amountMinor, String requestNo) {
        return exec(Op.SPLIT, ctx, amountMinor, requestNo,
                () -> buildSplit(ctx, amountMinor, requestNo));
    }

    @Override
    public final Result splitReverse(TxContext ctx, long amountMinor, String requestNo) {
        return exec(Op.SPLIT_REVERSE, ctx, amountMinor, requestNo,
                () -> buildSplitReverse(ctx, amountMinor, requestNo));
    }

    @Override
    public final Result refund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        return exec(Op.REFUND, ctx, amountMinor, requestNo,
                () -> buildRefund(ctx, amountMinor, requestNo, reason));
    }

    // ------------------------------------------------------------ 子类填空

    protected abstract Call buildSubsidy(TxContext ctx, long amountMinor, String requestNo, String description);

    protected abstract Call buildSubsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description);

    protected abstract Call buildSplit(TxContext ctx, long amountMinor, String requestNo);

    protected abstract Call buildSplitReverse(TxContext ctx, long amountMinor, String requestNo);

    protected abstract Call buildRefund(TxContext ctx, long amountMinor, String requestNo, String reason);

    /**
     * 判这次回执是成功还是失败。**各家的成功判据不同**：
     * 微信看 {@code result=SUCCESS}，支付宝看 {@code code=10000}。
     *
     * @return 失败时返回失败原因；成功返回 null
     */
    protected abstract String failureOf(String api, Map<String, Object> resp);

    /**
     * 回执里的通道单号。默认取 {@link Call#idField()}。
     *
     * <p>支付宝要覆写：它的分账/退款回执有时给 {@code trade_no}、有时只给
     * {@code out_request_no}，是**一个字段带一个兜底**而不是一个字段。
     * 做成钩子而不是把 idField 改成列表 —— 后者会让「取哪个」这件事
     * 从代码里搬到数据里，读的人要跳两次才知道答案。
     */
    protected Object idOf(Call c, Map<String, Object> resp) {
        return resp.get(c.idField());
    }

    // ------------------------------------------------------------ 骨架

    private Result exec(Op op, TxContext ctx, long amountMinor, String requestNo, java.util.function.Supplier<Call> builder) {
        String deny = capabilityDenial(op);
        if (deny != null) {
            // 能力位拦下来的**不可重试** —— 重试一万次通道也不会长出这个能力
            log.warn("[pay] {} {} 被能力位拦下：{}（单号 {}）", payChannel(), op.label(), deny, requestNo);
            return Result.fatal(deny);
        }
        Call c;
        try {
            c = builder.get();
        } catch (PreconditionFailed e) {
            log.warn("[pay] {} {} 前置不满足：{}（单号 {}）", payChannel(), op.label(), e.getMessage(), requestNo);
            return Result.fatal(e.getMessage());
        }
        try {
            Map<String, Object> resp = client.post(c.api(), c.body());
            String failure = failureOf(c.api(), resp);
            if (failure != null) {
                log.warn("[pay] {} {} 失败：{}（单号 {}，金额 {} 分）",
                        payChannel(), op.label(), failure, requestNo, amountMinor);
                return Result.fatal(failure);
            }
            Object id = idOf(c, resp);
            if (id == null) {
                String msg = c.api() + " 未返回 " + c.idField();
                log.warn("[pay] {} {} 回执缺字段：{}（单号 {}）", payChannel(), op.label(), msg, requestNo);
                return Result.fatal(msg);
            }
            /*
             * **成功也要留一行。** 此前只有失败才有日志，于是「这笔分账到底发出去没有」
             * 在日志里查不到 —— 而那正是对账对不上时第一个要问的问题。
             * 只记单号与金额，不记 body（里面有二级商户号与交易号，够定位了）。
             */
            log.info("[pay] {} {} 成功：单号 {}，金额 {} 分，通道单号 {}",
                    payChannel(), op.label(), requestNo, amountMinor, id);
            return Result.ok(String.valueOf(id));
        } catch (ChannelException e) {
            log.warn("[pay] {} {} 异常：{}（单号 {}，可重试 {}）",
                    payChannel(), op.label(), e.getMessage(), requestNo, e.isRetryable());
            return e.isRetryable() ? Result.retry(e.getMessage()) : Result.fatal(e.getMessage());
        }
    }

    /**
     * 能力位不允许时返回原因，允许返回 null。
     *
     * <p>只拦补差 —— {@code supports_subsidy} 是注册表里唯一一个「不支持就<b>绝不能发</b>」的位
     * （它为 0 时该通道整个不开积分抵扣）。分账与退款的位更像声明，
     * 且存量数据里都是 1，拦它们只会在没有真通道时先炸自己人。
     * <b>要拦更多的时候，加在这里，不要加在子类里。</b>
     */
    private String capabilityDenial(Op op) {
        if (op == Op.SUBSIDY && !masterData.supportsSubsidy(payChannel())) {
            return payChannel() + " 不支持补差（sys_pay_channel.supports_subsidy=0）";
        }
        return null;
    }

    /**
     * 构造阶段发现「这次根本不该发出去」时用它。
     *
     * <p>典型是配置缺项（支付宝补差没有出资方账号）。**这类失败不可重试** ——
     * 重试一万次配置也不会自己长出来。做成异常而不是让 build 返回 Result：
     * 返回值一变成联合类型，每个子类都要记得判一次，而<b>漏判的表现是照常发出去</b>。
     */
    protected static Call reject(String reason) {
        throw new PreconditionFailed(reason);
    }

    /** 见 {@link #reject(String)}。只在本类内部被捕获，不外泄。 */
    protected static final class PreconditionFailed extends RuntimeException {
        PreconditionFailed(String message) {
            super(message);
        }
    }

    /** 通道对描述字段都有长度上限，超了直接报参数错。两家都要，放这儿。 */
    protected static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
