package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.WxLogisticsTypes;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 微信发货信息录入的上报编排。
 *
 * <h2>放在 paybridge：它同时要够着两边</h2>
 * 上报要的是<b>支付单</b>上的东西（商户单号、付款人 openid）与<b>订单</b>上的东西
 * （履约方式、商品描述）。哪一侧都不该去查另一侧，而这一层同时够得着。
 *
 * <h2>顺序：先落库，再调用</h2>
 * 反过来做的话，调用成功而落库失败的那一次<b>没有任何痕迹</b> ——
 * 下一轮补报会再报一次（微信回 10060002，我们当成功，所以不会出错），
 * 但「这笔到底报过没有」这个问题永远答不上来。
 * 更糟的是调用超时那一次：报没报出去本来就不知道，再没有台账就彻底断了线索。
 */
@Service
public class WxShippingUploadService {

    private static final Logger log = LoggerFactory.getLogger(WxShippingUploadService.class);

    /**
     * 重试上限。到顶转 FAILED 交给人 —— <b>不是永远重试</b>：
     * 可重试的失败一直占着队列，真正该人工看的单就没人看
     * （与 {@code AbstractPayGateway} 里那条同一个道理）。
     */
    private static final int MAX_ATTEMPTS = 8;

    private final TradeMappers.ShippingUploadMapper mapper;
    private final WxShippingPort shipping;

    public WxShippingUploadService(TradeMappers.ShippingUploadMapper mapper, WxShippingPort shipping) {
        this.mapper = mapper;
        this.shipping = shipping;
    }

    /**
     * 记下「这笔单该上报了」。<b>只落库，不调用</b>。
     *
     * <p>由状态迁移调用（支付成功 / 货到自提点 / 商家发货），真正的上报交给补报任务 ——
     * 这样一次网络抖动不会让用户的那个动作失败，而该报的事一件不少。
     *
     * <p><b>幂等</b>：一笔订单一行。重复调不产生第二行，也不把已成功的改回待上报。
     *
     * @param logisticsType 见 {@link WxLogisticsTypes}。<b>0 表示认不出履约方式</b> ——
     *                      此时不落库并告警，绝不兜一个默认值
     */
    @Transactional
    public void enqueue(String orderNo, String outTradeNo, int logisticsType) {
        if (logisticsType == 0) {
            /*
             * 认不出来就不报。兜 1（快递）会让这类单缺运单号被微信拒（至少看得见）；
             * 兜 3（虚拟）是报上去但语义错 —— 微信不拒，没有任何地方会说一句。
             */
            log.error("[wxship] 订单 {} 的履约方式认不出来，**不上报**（钱会结不出来，要人来看）", orderNo);
            return;
        }
        if (outTradeNo == null || outTradeNo.isBlank()) {
            log.error("[wxship] 订单 {} 没有支付单号，无法上报 —— 上报靠它定位微信那笔单", orderNo);
            return;
        }
        TrdShippingUpload exist = byOrder(orderNo);
        if (exist != null) {
            /*
             * **一单里履约方式不止一种时，这里会静默丢掉后到的那一种。**
             *
             * 台账是一笔订单一行（uk_shipping_order），而一张多商家的单
             * 完全可能一张子单走快递、另一张走自提。先动的那张定了 logistics_type，
             * 后动的那张在这里被当成「重复」返回 —— 报上去的语义就是错的，
             * 而微信不会拒，没有任何地方会说一句。
             *
             * 真正的解法是 upload_combined_shipping_info（合单发货），
             * 那要改表的唯一键。在那之前**至少让它可见**：
             * 不静默，记 ERROR，让人能从日志里数出这种单有多少。
             */
            if (exist.getLogisticsType() != null && exist.getLogisticsType() != logisticsType) {
                log.error("[wxship] 订单 {} 混合履约：台账已是 type={}，本次 type={} 被丢弃"
                        + " —— **上报语义会是错的**，需合单发货（见 V323 注释）",
                        orderNo, exist.getLogisticsType(), logisticsType);
            }
            return;   // 一笔一行；已成功的更不该被改回待上报
        }
        TrdShippingUpload row = new TrdShippingUpload();
        row.setOrderNo(orderNo);
        row.setOutTradeNo(outTradeNo);
        row.setLogisticsType(logisticsType);
        row.setStatus(TrdShippingUpload.PENDING);
        row.setAttempts(0);
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
        log.info("[wxship] 订单 {} 已进上报队列（type={}）", orderNo, logisticsType);
    }

    /**
     * 真正发一次。由补报任务驱动。
     *
     * @return true = 这一行已终态（成功或不可重试的失败），不用再来
     */
    @Transactional
    public boolean upload(TrdShippingUpload row, String itemDesc,
                          String trackingNo, String expressCompany, String payerOpenid) {
        WxShippingPort.Result r = shipping.upload(new WxShippingPort.Command(
                row.getOutTradeNo(), row.getLogisticsType(), itemDesc,
                trackingNo, expressCompany, payerOpenid));

        Outcome o = outcomeOf(nz(row.getAttempts()), r);
        TrdShippingUpload patch = new TrdShippingUpload();
        patch.setId(row.getId());
        patch.setAttempts(o.attempts());
        if (r.success()) {
            patch.setStatus(TrdShippingUpload.SUCCESS);
            patch.setUploadedAt(LocalDateTime.now());
            DataScopeContext.executeWithoutScope(() -> mapper.updateById(patch));
            return true;
        }
        patch.setErrCode(r.code());
        patch.setErrMsg(trim(r.message(), 500));
        boolean giveUp = o.giveUp();
        if (giveUp) {
            patch.setStatus(TrdShippingUpload.FAILED);
            /*
             * ERROR 而不是 WARN：这一条说的是**这笔订单的钱结不出来**，
             * 而它在日志里必须能一眼扎出来 —— WARN 在这套系统的日志里是背景噪音。
             */
            log.error("[wxship] 订单 {} 上报最终失败（errcode={} {}）—— **这笔钱会结不出来**，需人工处理",
                    row.getOrderNo(), r.code(), r.message());
        }
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(patch));
        return giveUp;
    }

    /**
     * 这一行<b>材料不齐，这次发不出去</b>：把原因写进台账，状态留 PENDING。
     *
     * <h2>为什么不计入重试次数</h2>
     * 缺件不是「调用失败」——重试一万次也还是缺。计进去的话 8 次之后它变成 FAILED，
     * 而等我们把缺的那一列补上时，<b>这些单已经不在补报任务的视野里了</b>，
     * 要人去一笔笔翻回来。留在 PENDING，材料齐的那天下一轮自己就报上去了。
     *
     * <h2>为什么还是要写进 err_msg</h2>
     * 不写的话台账上这些行看起来和「刚入队还没轮到」一模一样 ——
     * 「为什么这批单一直没报出去」就没有任何地方答得上来。
     */
    @Transactional
    public void blocked(TrdShippingUpload row, String reason) {
        TrdShippingUpload patch = new TrdShippingUpload();
        patch.setId(row.getId());
        patch.setErrCode(0);
        patch.setErrMsg(trim(reason, 500));
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(patch));
    }

    /**
     * 「这一次之后该落成什么状态」。<b>抽成纯函数是为了能不起 Spring 就测</b> ——
     * 起一个新的上下文来测三个分支，代价是测试上下文缓存被挤掉重建，
     * 而重建会把 H2 的初始化脚本再跑一遍、种子撞主键（实测踩过）。
     *
     * @param attemptsBefore 这一次之前已经试过几次
     */
    public static Outcome outcomeOf(int attemptsBefore, WxShippingPort.Result r) {
        int attempts = attemptsBefore + 1;
        if (r.success()) {
            return new Outcome(attempts, true, TrdShippingUpload.SUCCESS);
        }
        // 到顶转 FAILED 交给人 —— 不是永远重试：可重试的失败一直占着队列，
        // 真正该人工看的单就没人看
        boolean giveUp = !r.retryable() || attempts >= MAX_ATTEMPTS;
        return new Outcome(attempts, giveUp,
                giveUp ? TrdShippingUpload.FAILED : TrdShippingUpload.PENDING);
    }

    /** @param giveUp 这一行已终态，补报任务不用再来 */
    public record Outcome(int attempts, boolean giveUp, String status) {
    }

    /** 待上报的（按创建时间，老的先来）。补报任务用 */
    public java.util.List<TrdShippingUpload> pending(int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(
                Wrappers.<TrdShippingUpload>lambdaQuery()
                        .eq(TrdShippingUpload::getStatus, TrdShippingUpload.PENDING)
                        .orderByAsc(TrdShippingUpload::getId)
                        .last("LIMIT " + limit)));
    }

    /**
     * 这一行现在是什么状态。补报任务用来把「已终态」再分成成功与失败 ——
     * {@link #upload} 只回答「还要不要再来」，而那个布尔值对成功和
     * 不可重试的失败是同一个值。
     */
    public String statusOf(Long id) {
        TrdShippingUpload row = DataScopeContext.executeWithoutScope(() -> mapper.selectById(id));
        return row == null ? null : row.getStatus();
    }

    private TrdShippingUpload byOrder(String orderNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<TrdShippingUpload>lambdaQuery()
                        .eq(TrdShippingUpload::getOrderNo, orderNo).last("LIMIT 1")));
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String trim(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
