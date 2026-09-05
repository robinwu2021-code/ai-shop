package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.PlatformOrderService;
import ai.neargo.shop.trade.service.ProxyLimitService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformOrderServiceImpl implements PlatformOrderService {

    /**
     * 代客下单能用的履约方式：<b>「到点自取」那几种</b>。
     *
     * <p>判据是**要不要收货地址**：快递 / 商家自送 / 上门服务都要
     * （{@code requireReceiverWhenShipped} 还会校验地址属于这个用户），
     * 而客服替顾客新建地址既是碰个人信息、也没法当面核对。
     * 顾客想要送货上门，得他自己在 App 里下 —— 那时地址是他自己选的。
     */
    private static final java.util.Set<String> PROXY_FULFILLMENTS = java.util.Set.of(
            Fulfillments.STORE_PICKUP, Fulfillments.NEIGHBOR_PICKUP, Fulfillments.STORE_VERIFY);

    private final SubOrderMapper subOrderMapper;
    private final StatusLogMapper statusLogMapper;
    private final OrderService orderService;
    private final GoodsQueryPort goodsPort;
    private final UserQueryPort userPort;
    private final UserProvisionPort userProvisionPort;
    private final ProxyLimitService proxyLimitService;

    /**
     * 代客单的支付时限：<b>30 分钟</b>（2026-09-03 产品决定）。
     *
     * <p>平台通用时限（默认 15 分钟）是给「人正看着屏幕」那条路配的 ——
     * 而电话下单的人要先挂电话、打开小程序、找到订单才付得上。
     * 用通用值的话，他多半在还没找到那张单的时候就被关掉了。
     *
     * <p>写成常量而不是加一条配置：这是一个产品定下来的数，
     * 而关单策略那一页配的是「顾客自己下的单」。真要可配再并进去，
     * 那时它得是一行有名字的配置，不是一个多出来的数字框。
     */
    private static final int PROXY_PAY_MINUTES = 30;

    public PlatformOrderServiceImpl(SubOrderMapper subOrderMapper, StatusLogMapper statusLogMapper,
                                    OrderService orderService, GoodsQueryPort goodsPort,
                                    UserQueryPort userPort, UserProvisionPort userProvisionPort,
                                    ProxyLimitService proxyLimitService) {
        this.subOrderMapper = subOrderMapper;
        this.statusLogMapper = statusLogMapper;
        this.orderService = orderService;
        this.goodsPort = goodsPort;
        this.userPort = userPort;
        this.userProvisionPort = userProvisionPort;
        this.proxyLimitService = proxyLimitService;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public OrderVO createProxyOrder(ProxyOrderCommand cmd, String operatorNo, String idempotencyKey) {
        String reason = cmd.reason() == null ? "" : cmd.reason().trim();
        if (reason.isEmpty() || cmd.items() == null || cmd.items().isEmpty()
                || cmd.merchantNo() == null || cmd.merchantNo().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 顾客：优先用人档里已有的 userNo；没有就按手机号建一个。
         *
         * **建号走的是登录那条路**（UserProvisionPort → AuthService#findOrCreate），
         * 所以他日后用同一个手机号登录命中的是同一个账号 —— 「认领」不需要任何动作。
         * 自己另写一套建户逻辑的话，客服建的号与他登出来的号会是两个人，
         * 而那张单他永远看不到。
         */
        String userNo = cmd.userNo();
        boolean provisioned = false;
        if (userNo == null || userNo.isBlank()) {
            if (cmd.phone() == null || !cmd.phone().matches("\\d{11}")) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            userNo = userProvisionPort.ensureUserByPhone(cmd.phone());
            provisioned = true;
        } else if (userPort.find(userNo).isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!PROXY_FULFILLMENTS.contains(cmd.fulfillment())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String payMode = cmd.payMode() == null || cmd.payMode().isBlank()
                ? PayModes.OFFLINE : cmd.payMode();
        if (!PayModes.isValid(payMode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * 货号由**服务端从 skuNo 解出来**，不收端上传的 goodsNo ——
         * 两者对不上时下出来的单，商品与价格会分属两件货。
         * 顺带把「跨商家」挡在这里：全站按商家拆单（E3），一次一个商家。
         */
        java.util.List<String> skuNos = cmd.items().stream()
                .map(ProxyOrderCommand.Item::skuNo).toList();
        var snapshots = goodsPort.snapshot(skuNos);
        java.util.List<OrderService.CreateOrderCommand.Item> items = new ArrayList<>();
        for (var it : cmd.items()) {
            var snap = snapshots.get(it.skuNo());
            if (snap == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (!cmd.merchantNo().equals(snap.merchantNo())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (it.qty() <= 0) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            items.add(new OrderService.CreateOrderCommand.Item(snap.goodsNo(), it.skuNo(), it.qty()));
        }

        /*
         * <b>每日笔数闸</b>（M6）。放在建单**之前**：超了就不该占号、不该锁库存。
         * 按这个客服今天已经代下的单数算 —— 数的是订单时间线上那行「代客下单」，
         * 它本来就是留痕，不另建一张计数表（两处计数迟早对不上）。
         */
        var limit = proxyLimitService.get();
        long today = proxyCountToday(operatorNo);
        if (today >= limit.maxPerDay()) {
            throw BizException.of(ErrorCode.PROXY_ORDER_DAILY_LIMIT);
        }

        /*
         * couponNo=null、usePoints=0：**不代用顾客的资产**，命令里也没有这两个字段。
         * payScene=null：这一单没有「下单端」—— 它不是从任何一个端来的。
         * 存量端上本来就不传这个头，null 是既有的合法状态。
         */
        OrderVO vo = orderService.createFor(userNo,
                new OrderService.CreateOrderCommand(items, cmd.fulfillment(), cmd.pickupNo(),
                        null, null, 0L, "代客下单：" + reason,
                        null, payMode, null, null),
                // 幂等键由运营端在打开表单时生成：同一张表单连点两次只会有一单
                idempotencyKey,
                // 线上付给 30 分钟；线下付不看这个数（它不走超时关单）
                PROXY_PAY_MINUTES);

        /*
         * <b>单笔金额闸</b>（M6）。按订单**实际应付额**判，不按商品估算 ——
         * 有运费与优惠时估算说不清，而这条闸拦住的是「这一单大得该有人看一眼」。
         *
         * <p>放在建单之后、整段还在同一个事务里：抛出去连同刚建的单与锁掉的库存
         * 一起回滚。先估算再建单的话，估算与真实差一点点就会出现
         * 「明明拦住了却留下一张单」。
         */
        long payable = vo.amount() == null ? 0L : vo.amount().payableMinor();
        if (payable > limit.maxAmountMinor()) {
            throw BizException.of(ErrorCode.PROXY_ORDER_AMOUNT_LIMIT);
        }

        /*
         * 订单时间线上留一行。**不能只写审计日志** —— 那张表只有运营看得到，
         * 而「这单是客服代下的、为什么」正是顾客打电话来问、商家备货时要看到的第一句话。
         * 与代客取消落在同一处（OrdStatusLog，operatorType=PLATFORM）。
         */
        for (var sub : subOrdersOf(vo)) {
            OrdStatusLog log = new OrdStatusLog();
            log.setSubOrderNo(sub);
            log.setStatus(OrdSubOrder.WAIT_PAY);
            // 建了号也写进去：顾客问「我什么时候有的账号」时，答案在他自己的订单上
            log.setLabel("代客下单：" + reason + (provisioned ? "（并为该手机号新建了账号）" : ""));
            log.setOperatorType(OrdStatusLog.BY_PLATFORM);
            log.setOperatorNo(operatorNo);
            log.setAt(System.currentTimeMillis());
            log.setTenantNo("MAIN");
            log.setCreatedAt(java.time.LocalDateTime.now());
            statusLogMapper.insert(log);
        }
        return vo;
    }

    /**
     * 这个客服**今天**已经代下了几单。
     *
     * <p>数的是订单时间线上那行「代客下单」（`OrdStatusLog`，operatorType=PLATFORM）——
     * 它本来就是留痕，不另建计数表：两处计数迟早对不上，而对不上的那天
     * 没人知道该信哪个。
     */
    private long proxyCountToday(String operatorNo) {
        long dayStart = java.time.LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return DataScopeContext.executeWithoutScope(() ->
                statusLogMapper.selectCount(Wrappers.<OrdStatusLog>lambdaQuery()
                        .eq(OrdStatusLog::getOperatorType, OrdStatusLog.BY_PLATFORM)
                        .eq(OrdStatusLog::getOperatorNo, operatorNo)
                        .ge(OrdStatusLog::getAt, dayStart)
                        .likeRight(OrdStatusLog::getLabel, "代客下单")));
    }

    /** 代客单只会有一个子单（一次一个商家），但仍按列表取：拆单规则变了这里不用改。 */
    private List<String> subOrdersOf(OrderVO vo) {
        List<String> nos = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getOrderNo, vo.orderNo()))
                        .stream().map(OrdSubOrder::getSubOrderNo).toList());
        return nos.isEmpty() ? List.of(vo.orderNo()) : nos;
    }

    @Override
    public PageData<OrderVO> search(String status, long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        }
        w.orderByDesc(OrdSubOrder::getId);

        /*
         * **走数据域**（2026-08-14，运营端数据域接入 批①）。
         *
         * 这里原先是 `executeWithoutScope`，理由写的是「平台视角看全量」——
         * 而那句话只对没配数据域的账号成立。配了「只看某商家 / 某片区」的运营，
         * 他的 `DataScopeSpec` 一路带到这里就被丢掉了：
         * 配置页显示「已限定」，他照样看到全平台的单。
         *
         * 没配数据域的账号仍然是 `DataScopeSpec.ALL`（空 = 不限定），
         * 超管恒 ALL —— 所以对存量账号零变化。
         */
        Page<OrdSubOrder> p = subOrderMapper.selectPage(Page.of(page, size), w);

        List<OrderVO> records = p.getRecords().stream()
                .map(s -> new OrderVO(s.getSubOrderNo(), s.getOrderNo(), s.getStatus(),
                        s.getFulfillment(), s.getEntityNo(), s.getEntityName(),
                        List.of(),
                        OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                                nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                        s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                        // 平台侧也要看得到快递单号：客服处理「货到哪了」全靠它
                        // 收件人先不下发：平台端列表是「查单」不是「送货」，
                        // 真要给也该是另一档口径，别顺着商家那套走
                        null, 0L, null, s.getExpressNo(), s.getTrafficSource(),
                        s.getAppointmentAt(), null, List.of(), null,
                        // 买家昵称：平台端列表是「查单」，认人靠订单号与手机号尾号
                        null,
                        // 平台端列表不查这三样：那是买家视角的东西，且列表逐条查就是 N+1
                        false, null, 1))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
