package ai.neargo.shop.settle.impl;

import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.SplitGateway;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import ai.neargo.shop.settle.entity.StlBill;
import ai.neargo.shop.settle.entity.StlSettleBatch;
import ai.neargo.shop.settle.entity.StlPointsPool;
import ai.neargo.shop.settle.entity.StlFeeRule;
import ai.neargo.shop.settle.entity.StlPurchaseInvoice;
import ai.neargo.shop.settle.dto.PurchaseInvoiceVO;
import ai.neargo.shop.settle.dto.StatementVO;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.settle.entity.StlSplitLog;
import ai.neargo.shop.settle.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.settle.mapper.SettleMappers.SplitLogMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算与分账（ADR-002）。**M7 起取代 {@code SettlePortStub}**。
 *
 * <p>金额口径（三列缺一不可）：
 * <pre>
 *   gross      = 用户实付 + 平台补贴的优惠   ← 平台券的钱最终要给商家
 *   commission = gross × 费率档              ← 自带客流为 0（R16）
 *   net        = gross - commission - 服务费  ← 商家实得
 * </pre>
 * 商家券的钱是商家自己让的利，**不补回**，所以不进 gross。
 *
 * <p>费率**落库快照**：费率是会调的，历史账不能跟着变 —— 否则去年的账今年一看就对不上。
 */
@Service
public class SettleServiceImpl implements SettleService {

    private static final Logger log = LoggerFactory.getLogger(SettleServiceImpl.class);

    /*
     * 费率来自 stl_fee_rule，不再来自 application.yml（P1-4）。
     *
     * 原先是两个 @Value，改一次费率要改配置文件加重启；而费率是最会被反复调的
     * 东西之一。快照那一半原本就做对了 —— stl_bill.commission_rate 逐单落快照，
     * 历史账不跟着变 —— 这次只换取数来源，不动快照。
     */
    private final ai.neargo.shop.settle.service.FeeRuleService feeRuleService;

    /** 积分池入账 —— 池子的记账口径归积分域，结算侧只在发生时调它 */
    private final ai.neargo.shop.settle.PointsService pointsService;
    /** 通道能力（能否补差）—— 判在结算侧，因为下单时通道还没定 */
    private final ai.neargo.shop.spi.platform.MasterDataPort masterDataPort;

    private final BillMapper billMapper;
    private final ai.neargo.shop.settle.mapper.SettleMappers.SettleBatchMapper batchMapper;
    private final SplitLogMapper splitLogMapper;
    private final SettleSourcePort sourcePort;
    private final SplitGateway gateway;
    /** 算履约服务费要知道该自提点谈定的口径（ADR-009） */
    private final PickupQueryPort pickupPort;
    /** 解析「这笔钱打给哪个收款号」—— 门店配的号 ?? 主体默认号，口径只有那一处 */
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort;
    private final ai.neargo.shop.settle.mapper.SettleMappers.PurchaseInvoiceMapper purchaseInvoiceMapper;
    private final ai.neargo.shop.spi.platform.SettingPort settingPort;

    public SettleServiceImpl(BillMapper billMapper, SplitLogMapper splitLogMapper,
                             SettleSourcePort sourcePort, SplitGateway gateway,
                             PickupQueryPort pickupPort,
                             ai.neargo.shop.spi.user.MerchantQueryPort merchantQueryPort,
                             ai.neargo.shop.settle.mapper.SettleMappers.PurchaseInvoiceMapper purchaseInvoiceMapper,
                             ai.neargo.shop.spi.platform.SettingPort settingPort,
                             ai.neargo.shop.settle.service.FeeRuleService feeRuleService,
                             ai.neargo.shop.settle.PointsService pointsService,
                             ai.neargo.shop.spi.platform.MasterDataPort masterDataPort,
                             ai.neargo.shop.settle.mapper.SettleMappers.SettleBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
        this.masterDataPort = masterDataPort;
        this.pointsService = pointsService;
        this.settingPort = settingPort;
        this.feeRuleService = feeRuleService;
        this.purchaseInvoiceMapper = purchaseInvoiceMapper;
        this.billMapper = billMapper;
        this.splitLogMapper = splitLogMapper;
        this.sourcePort = sourcePort;
        this.gateway = gateway;
        this.pickupPort = pickupPort;
        this.merchantQueryPort = merchantQueryPort;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 履约服务费：平台提供的自提点才收，按该点谈定的口径算（ADR-009 / V18）。
     *
     * <p>此前这里恒为 0，注释写着「R15 口径未定」—— 现在定了：自提点分**商家自行解决**
     * 与**平台提供**两种，后者的费率线下逐点协商、由运营平台录入，口径可能是按件也可能是按率。
     *
     * <p>三条边界：
     * <ul>
     *   <li>不经自提点（快递 / 上门）→ 0。没有人替你分拣保管，自然没有这笔钱</li>
     *   <li>查不到该自提点 → 0 而不是抛错。**结算不能因为一条主数据缺失就整批卡住**，
     *       少算的钱可以补，卡住的结算会让所有商家当期都拿不到钱</li>
     *   <li>{@code STORE} / {@code NEIGHBOR} 由 PickupQueryPort 那侧恒返回 {@code NONE}，
     *       这里不重复判断 —— 判两遍等于两处都可能改错</li>
     * </ul>
     *
     * @param gross 成交额（含平台补贴），按率计费时的基数
     */
    private long serviceFeeOf(SettleSourcePort.SettleSource src, long gross) {
        if (src.pickupNo() == null || src.pickupNo().isBlank()) {
            return 0L;
        }
        var pickup = pickupPort.find(src.pickupNo()).orElse(null);
        if (pickup == null) {
            log.warn("结算：自提点 {} 查不到，本单履约服务费按 0 计 subOrder={}",
                    src.pickupNo(), src.subOrderNo());
            return 0L;
        }
        return switch (pickup.feeMode()) {
            case "PER_ITEM" -> pickup.serviceFeePerItemMinor() * Math.max(src.itemCount(), 0);
            case "RATE" -> gross * pickup.serviceFeeRate() / 10000;
            default -> 0L;
        };
    }

    @Override
    @Transactional
    public int generateForOrder(String orderNo) {
        int created = 0;
        /*
         * 费率一次取齐，不逐子单查库：一张订单跨 N 个商家就是 N 张结算单，
         * 而规则表天然很小（四格 × 调整次数）。同一订单内用同一份快照，
         * 也顺带保证了「同一单里不会因为跨过某个生效时刻而两个商家算出不同费率」。
         */
        long at = System.currentTimeMillis();
        var rates = feeRuleService.effectiveRates(at);

        for (SettleSourcePort.SettleSource src : sourcePort.settleSourcesOf(orderNo)) {
            // 一个子单只能有一张结算单：重复生成 = 重复分账 = 给商家多打钱。
            // 靠先查再插 + DB 唯一索引双保险（事件重投时两条路径都可能撞上）
            if (findBySubOrder(src.subOrderNo()) != null) {
                continue;
            }
            /*
             * 经营模式要在算费率之前拿到 —— 费率是「经营模式 × 流量来源」二维的。
             * 它同时还决定这张单走哪条状态机（见下方快照那段）。
             */
            String mode = merchantQueryPort.businessModeOf(src.merchantNo(), src.storeNo());
            int rate = rates.getOrDefault(mode + "|" + normalizedSource(src.trafficSource()), 0);

            /*
             * ★ 结算基数 = 实付 + 平台补贴 + **积分抵扣**。
             *
             * 前两项原本就在。第三项是补上的一个正在发生的错：payAmount 已经把积分
             * 抵扣扣掉了，而 ord_sub_order.points_deduct 的注释写着
             * 「平台内部字段，不下发商家端 —— 商家按订单全额收款」。
             * 不加回来的话，买家用积分抵掉的那部分**从商家的货款里出**。
             *
             * 加回来之后钱还得真的到账：见下面的 subsidyMinor 与 executeSplit 里的补差。
             * 只改基数不补差，等于把商家的账做大而钱没打 —— 那比现在更糟。
             *
             * 商家自己让的利（discountMerchant）不补：那本来就是他出的。
             */
            long gross = src.payAmount() + src.discountPlatform() + src.pointsDeductMinor();
            long serviceFee = serviceFeeOf(src, gross);

            /*
             * ★ 线下（当面）收款：**钱从没进过平台**，所以这张单既不抽佣也不分账。
             *
             * 佣金照原样算出来，只是不收 —— 算式一份，收不收是一个 if。
             * 反过来（线下走一条自己的算法）的话，费率规则每改一次要改两处，
             * 而漏改的那一处**没有任何地方会报错**：报表照出，数目悄悄不对。
             */
            boolean offline = PayModes.OFFLINE.equals(src.payChannel());
            /*
             * ★ 通道手续费要**先算出来**：佣金基数扣的就是它。
             *
             * 《收款与分账-总体逻辑》§六红线 6：佣金基数 = 扣完支付手续费后的金额，
             * 不是订单原价。按原价抽的话每笔多收几分，累积起来对不上账。
             * 线下单的手续费恒为 0，所以这条对它没有影响。
             *
             * <b>只对新单生效</b>（2026-08-30 拍板）：这里是生成时一次性算定并落快照，
             * 存量单不会被重算 —— 历史账保持与当初真的打出去的钱一致。
             */
            ChannelFee fee = channelFeeOf(src, at);
            long ruleCommission = (gross - fee.minor()) * rate / 10000;
            long commission = offline ? 0L : ruleCommission;

            StlBill bill = new StlBill();
            bill.setSettleNo(BizKey.next(BizKey.SETTLE_BILL));
            bill.setSubOrderNo(src.subOrderNo());
            bill.setOrderNo(orderNo);
            bill.setEntityNo(src.merchantNo());
            bill.setGrossMinor(gross);
            bill.setCommissionMinor(commission);
            /*
             * 让掉多少：**只记不扣**。线下单记 ruleCommission，线上单是 0。
             * 它不是应收账款，没有任何地方会去收 —— 存在的理由只有一个：
             * 知道让了多少。否则「线下这部分生意值多少钱」根本算不出来，
             * 将来无论继续免还是重新定价都没有依据。
             */
            bill.setWaivedCommissionMinor(offline ? ruleCommission : 0L);
            bill.setServiceFeeMinor(serviceFee);
            /*
             * 通道与下单端。**payChannel 此前全库为 null** —— 它从没被传进结算域，
             * 而下面入池流水那行已经在读它了（读到的一直是 null）。线下单靠它认，
             * 所以这一步顺带把这个洞补上。
             */
            bill.setPayChannel(src.payChannel());
            bill.setPayScene(src.payScene());
            /*
             * ★ 发分费用金：**单独一列，不并进 serviceFeeMinor**。
             *
             * 商家最常问的是「这个月为什么少了」，而两者的解释路径完全不同 ——
             * 佣金是按率的（他改不了），费用金是按他自己开的积分活动走的（他关掉就没了）。
             * 并成一列，客服每次都得回去翻明细才答得出。
             *
             * 此前这一列全库零写入：B 端「本期积分支出」永远是 0，
             * 而池子只出不进（用户花分时 MERCHANT_PAY 出账，发分时没有对应入账）——
             * 恒等式 2「池子余额 == 流通中积分 × 汇率」会随发放量单调失衡。
             */
            long pointsFee = src.pointsFeeMinor();
            bill.setPointsFeeMinor(pointsFee);
            /*
             * ★ 商家实得要减掉**他自己承担的**那部分通道手续费。
             *
             * 记了金额却不从任何人的钱里扣，等于账上有一笔谁都不出的费用。
             * 判据是进件档案上的 fee_bearer，不是资金路径 ——
             * 资金路径只决定这笔钱**由谁物理扣走**（归集是平台代垫后扣回，
             * 直连是通道直接从二级户扣），而「谁最终承担」是签约时谈定的。
             * 按资金路径判的话，直连商家会被扣两次：通道扣一次、我们再算一次。
             *
             * 承担方为空（还没进件）时**不扣** —— 猜一个方向就是替他做主，
             * 而这个方向上猜错等于少付给他钱。
             */
            bill.setNetMinor(gross - commission - serviceFee - pointsFee - fee.borneByMerchant());
            bill.setChannelFeeMinor(fee.minor());
            bill.setChannelFeeRate(fee.rateBp());
            bill.setChannelFeeSource(fee.source());
            bill.setFeeBearer(fee.bearer());
            bill.setTrafficSource(src.trafficSource());
            bill.setCommissionRate(rate);
            /*
             * 积分抵扣的成本落到哪一列，**取决于资金路径**：
             *
             *   DIRECT     钱在商家二级户 → 抵扣让他少收 → 平台必须补差进去
             *              → subsidy_minor，executeSplit 会真的发起一次划转
             *   AGGREGATED 钱在平台户     → 平台自己少收 → **没有补的动作**
             *              → points_cost_minor，只记账
             *
             * 此前两条路径都写 subsidy_minor，而自营单不走 executeSplit ——
             * 于是自营单上挂着一个「补差额」，那条链路根本没有补差这个动作，
             * 读单据的人会以为平台补过钱。不是 bug，是缺一层语义。
             *
             * 落快照的理由不变：积分规则会变，「这单当初补了多少」必须能原样查回来。
             */
            String fundsMode = merchantQueryPort.fundsModeOf(src.merchantNo());
            bill.setFundsMode(fundsMode);
            if (MerchantQueryPort.FUNDS_DIRECT.equals(fundsMode)) {
                bill.setSubsidyMinor(src.pointsDeductMinor());
                bill.setPointsCostMinor(0L);
            } else {
                bill.setSubsidyMinor(0L);
                bill.setPointsCostMinor(src.pointsDeductMinor());
            }
            /*
             * 两个快照，与 commissionRate 同一个理由：配置会变，历史账不能跟着变。
             *   storeNo       —— 这笔钱是哪家店挣的（统计维度）
             *   payMerchantNo —— 这笔钱打给哪个账户（结算维度）
             * 二者不互相决定：两家店可以共用一个收款号（那就是合并结算），
             * 也可以各配各的（分开结算）。所以要各存各的，不能从一个推另一个。
             *
             * 解析不出收款号（进件还没走完）时留空：**账单照常生成** ——
             * 钱是欠着的，不是不存在。发起打款那一步会再解析一次并挡下来。
             */
            bill.setStoreNo(src.storeNo());
            bill.setPayMerchantNo(
                    merchantQueryPort.payMerchantNoOf(src.merchantNo(), src.storeNo()).orElse(null));
            /*
             * 经营模式快照。它决定这张单走哪条状态机：
             *   自营   PENDING_RECON → CONFIRMED → PAID（对账 → 确认 → 财务付款）
             *   第三方 PENDING → SPLITTABLE → SPLIT
             * 不快照的话，门店改一次模式会把未结的历史流水一起改口径 ——
             * 自营的单要收进项票、第三方的不用，走错分支就是凭证对不上账。
             */
            bill.setBusinessMode(mode);
            boolean selfOperated = MerchantQueryPort.MODE_SELF_OPERATED.equals(mode);
            /*
             * 线下**压过经营模式**决定状态：两条既有链路（自营对账、第三方分账）
             * 描述的都是「平台手里这笔钱怎么流出去」，而线下压根没有这笔钱。
             * 落成终态 OFFLINE_SETTLED，之后不再变。
             *
             * 进项票状态**仍按经营模式**，不跟着线下走：收不收票是税务的事，
             * 与钱怎么收无关。不会因此卡住财务的待收票队列 ——
             * billsAwaitingInvoice 筛的是 status=CONFIRMED，终态单进不去。
             */
            bill.setStatus(offline ? StlBill.OFFLINE_SETTLED
                    : selfOperated ? StlBill.PENDING_RECON : StlBill.PENDING);
            bill.setInvoiceStatus(selfOperated ? StlBill.INV_PENDING : StlBill.INV_NONE);
            bill.setRetryCount(0);
            DataScopeContext.executeWithoutScope(() -> billMapper.insert(bill));

            /*
             * 费用金入池 —— **与货款扣减同一时刻**。
             *
             * 不在发分时入池：那一刻钱还没到手（货款要到结算才扣），
             * 提前入池等于池子里记着一笔还没收到的钱。
             *
             * refNo 用结算单号：对账时要能从池子的一笔回溯到是哪张单收的。
             */
            if (pointsFee > 0) {
                /*
                 * ⚠️ 通道**显式传 null**，不要改成 bill.getPayChannel()。
                 *
                 * 池子按 (market, pay_channel) 分桶记账，而**所有出池都传 null**
                 * （PointsServiceImpl 的 MERCHANT_PAY 与 EXPIRE_INCOME 两处）——
                 * null 落到列默认值 'WECHAT'，于是进出目前都在同一个桶里，是平的。
                 *
                 * 这一行此前读的 bill.getPayChannel() 恒为 null（那一列从没被写过），
                 * 所以碰巧也落 WECHAT。本步开始真的写通道了：若照旧读它，
                 * 线下单的入池会落进 OFFLINE 桶而出池还在 WECHAT 桶 ——
                 * 两个桶一个单调涨一个单调负，而账面总额仍然是平的，
                 * 只有翻「按通道」那张明细才看得出来。
                 *
                 * 要真按通道分桶，得进出两侧一起改，那是独立的一件事。
                 */
                pointsService.recordPoolFlow(StlPointsPool.MERCHANT_RECEIVE, pointsFee,
                        src.merchantNo(), bill.getSettleNo(), null, null);
            }
            created++;
        }
        return created;
    }

    /**
     * 流量来源为空时按平台客流算。
     *
     * <p>倒向「收费」而不是「免费」，与 {@code FeeRuleService.rateOf} 查不到时返回 0
     * 方向相反，且都是有意的：这里判的是**归因缺失**（没记到是谁带来的），
     * 默认按平台带来的算；那里判的是**规则缺失**（运营没配），
     * 凭空按最高档收会真的多扣商家钱。
     */
    private String normalizedSource(String trafficSource) {
        return StlFeeRule.MERCHANT_OWNED.equals(trafficSource)
                ? StlFeeRule.MERCHANT_OWNED : StlFeeRule.PLATFORM;
    }

    // ---------------------------------------------------------------- 分账

    /**
     * ⚠️ <b>只该由通道回执调用。</b> 这是唯一能进 {@code SPLIT_CONFIRMED} 的入口，
     * 而 {@code SPLIT_CONFIRMED} 是「钱真的到了」——
     * 开一个人工入口等于允许在钱没到账时把单子做平。
     */
    @Override
    @Transactional
    public boolean confirmSplit(String settleNo, String channelRef) {
        StlBill bill = require(settleNo);
        if (StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())) {
            // 幂等：回执会重投。**不重写时间戳** —— 改晚了会让对账把一条正常单
            // 算成「发出很久才确认」，而那是分账轴要捞的差异类型
            return false;
        }
        if (!StlBill.SPLIT.equals(bill.getStatus())) {
            /*
             * 没发过分账指令的单收到确认回执 —— 这本身就是一条**该被看见的异常**
             * （回执串了单、或我方漏记了指令）。但这里不抛：回执链路上抛异常会让通道重投，
             * 而重投解决不了串单。留给对账去认领。
             */
            log.warn("收到分账确认回执，但这单没有发出过指令：settleNo={} status={} ref={}",
                    settleNo, bill.getStatus(), channelRef);
            return false;
        }
        bill.setStatus(StlBill.SPLIT_CONFIRMED);
        bill.setSplitConfirmedAt(System.currentTimeMillis());
        update(bill);
        /*
         * 留一条流水。**不走 callProvider** —— 那个方法会真的去调通道，
         * 而我们现在处理的正是通道打回来的回执，再调一次是把因果关系倒过来。
         */
        StlSplitLog entry = new StlSplitLog();
        entry.setSettleNo(bill.getSettleNo());
        entry.setSubOrderNo(bill.getSubOrderNo());
        entry.setSplitAction("SPLIT_CONFIRM");
        entry.setAmountMinor(nz(bill.getSplitAmountMinor()));
        entry.setRequestNo("CFM-" + settleNo);
        entry.setResult("SUCCESS");
        entry.setMessage(channelRef);
        // `at` 是必填列（业务时刻，与 created_at 的落库时刻分开）—— 照抄 callProvider 时漏了它
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> splitLogMapper.insert(entry));
        return true;
    }

    @Override
    @Transactional
    public void executeSplit(String settleNo) {
        StlBill bill = require(settleNo);
        if (StlBill.SPLIT.equals(bill.getStatus())
                || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                || StlBill.REVERSED.equals(bill.getStatus())) {
            // 幂等：重复执行不会重复打款。**SPLIT_CONFIRMED 也要挡** ——
            // 少了它，一笔已确认到账的单被重放时会再发一次分账指令
            return;
        }
        /*
         * 线下单**永远不分账**：钱在商家自己口袋里，向二级商户发起分账
         * 会从他的**其他**收入里划走这笔佣金 —— 而这单的佣金已经说好不收了。
         *
         * 直接返回而不是抛异常：批量分账任务是按状态捞单的，OFFLINE_SETTLED
         * 本来就捞不到；能走到这儿的只有人工重放或将来某个新入口。
         * 那种时候要的是「这单不动」，不是让整批任务红着停下。
         */
        if (StlBill.OFFLINE_SETTLED.equals(bill.getStatus())) {
            return;
        }

        bill.setStatus(StlBill.SPLITTING);
        update(bill);

        /*
         * **先补差，再分账**。顺序不能反：
         * 分账是从二级商户账户里往外拿钱，而补差是往里放钱 ——
         * 先分后补的话，账户余额可能不够扣，分账被通道拒绝，
         * 而那时订单已经付过款了。
         *
         * 补差失败就不分账，整单转重试：只改了结算基数而钱没补进去，
         * 等于把商家的账做大而钱没打，比不改更糟。
         */
        /*
         * **补差只在直连路径上存在。**
         *
         * 归集路径下应付账款已经按全额算过了（gross 里加回了积分抵扣），
         * 再补一次就是**重复付款** —— 100 元的货平台会付出 110。
         *
         * 现在不出这个问题，只是因为自营单不走到这里；那是<b>巧合式的正确</b>，
         * 不是设计。在执行点断言，而不是指望调用方记得。
         */
        if (!MerchantQueryPort.FUNDS_DIRECT.equals(bill.getFundsMode())
                && nz(bill.getSubsidyMinor()) > 0) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        /*
         * **通道要真的支持补差。**
         *
         * `sys_pay_channel.supports_subsidy` 这一列建出来就是为了拦这里，
         * 但此前**零读取** —— 不具备补差能力的通道照样走到这一步，
         * 然后补差调用失败、整单转重试，而根因（通道压根没这个能力）
         * 要翻网关日志才看得出来。
         *
         * 判在这里而不是扣分那一刻：**下单时通道还没定**（markPaid 才有），
         * 那时判等于拿一个还不存在的事实做判断。
         */
        if (nz(bill.getSubsidyMinor()) > 0 && !masterDataPort.supportsSubsidy(bill.getPayChannel())) {
            bill.setStatus(StlBill.MANUAL);
            bill.setLastError("通道不支持积分补差，需人工处理：" + bill.getPayChannel());
            update(bill);
            return;
        }
        long subsidy = nz(bill.getSubsidyMinor());
        if (subsidy > 0 && bill.getSubsidyAt() == null) {
            boolean subsidized = callProvider(StlSplitLog.SUBSIDY, bill, "SUB-" + settleNo);
            if (!subsidized) {
                bill.setStatus(StlBill.RETRYING);
                bill.setRetryCount(nzi(bill.getRetryCount()) + 1);
                bill.setLastError("积分补差失败，未分账");
                update(bill);
                return;
            }
            bill.setSubsidyAt(System.currentTimeMillis());
            update(bill);
        }

        String requestNo = "SPL-" + settleNo;
        boolean ok = callProvider(StlSplitLog.SPLIT, bill, requestNo);
        if (!ok) {
            bill.setStatus(StlBill.RETRYING);
            bill.setRetryCount(nzi(bill.getRetryCount()) + 1);
            update(bill);
            return;
        }
        bill.setStatus(StlBill.SPLIT);
        bill.setSplitAt(System.currentTimeMillis());
        update(bill);

        /*
         * 积分抵扣兑付跟着分账走 —— **同一时点，不另立一套「积分售后期」**。
         * 分账成立意味着钱真的到了商家账户，抵扣的那部分补差也在同一笔里，
         * 此刻才谈得上「平台已付」。
         *
         * 各设一套时点的代价：月末对不平，第一件事要先分辨是账单晚了还是积分晚了。
         */
        pointsService.confirmDeduction(bill.getSubOrderNo());
    }

    // ---------------------------------------------------------------- SettlePort（退款链路）

    @Override
    @Transactional
    public boolean reverseSplit(String subOrderNo) {
        StlBill bill = findBySubOrder(subOrderNo);
        if (bill == null) {
            // 没有结算单（例如未支付即取消）：没什么可回退的，视为成功
            return true;
        }
        if (StlBill.REVERSED.equals(bill.getStatus())) {
            return true;
        }
        /*
         * **两种都算「分过账」**：指令已发出（SPLIT）与已确认到账（SPLIT_CONFIRMED）。
         *
         * 只认 SPLIT_CONFIRMED 的话，一笔「已发出但还没回执」的单退款时会走进下面那个
         * 分支直接置 REVERSED —— 而通道那边可能正要把钱划走，于是钱划出去了而账上写着已回退。
         * 只认 SPLIT 则相反：确认到账的单退不了。
         */
        if (!StlBill.SPLIT.equals(bill.getStatus())
                && !StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())) {
            // **没分过账就不发回退指令** —— 发了只会收到「找不到分账单」的错误，
            // 徒增一条失败日志，还会让排查的人以为真出了问题
            bill.setStatus(StlBill.REVERSED);
            update(bill);
            return true;
        }

        boolean ok = callProvider(StlSplitLog.REVERSE, bill, "REV-" + bill.getSettleNo());
        if (!ok) {
            bill.setStatus(StlBill.MANUAL);
            bill.setLastError("分账回退失败，需人工处理");
            update(bill);
            return false;   // ★ 返回 false，调用方（售后）必须据此**停止退款**
        }
        /*
         * 分账回退成功后再退补差：钱先回到二级商户账户，再从账户里把补贴拿回平台。
         * 反过来做的话，账户里可能还没有那笔钱。
         *
         * 补差回退失败不阻断退款：买家的钱必须能退。这笔补贴留在商家账上是平台的损失，
         * 但它是**可追的**（subsidy_at 有值而单已 REVERSED），
         * 而卡住买家退款是不可接受的。
         */
        if (nz(bill.getSubsidyMinor()) > 0 && bill.getSubsidyAt() != null) {
            if (callProvider(StlSplitLog.SUBSIDY_RETURN, bill, "SUBR-" + bill.getSettleNo())) {
                bill.setSubsidyAt(null);
            } else {
                log.warn("补差回退失败，需人工追回 settleNo={} subsidy={}",
                        bill.getSettleNo(), bill.getSubsidyMinor());
            }
        }

        bill.setStatus(StlBill.REVERSED);
        update(bill);
        return true;
    }

    @Override
    public String refund(String subOrderNo, long amountMinor, String reason) {
        // S4 接真实支付退款；当前记录意图。**顺序保证在调用方**（AfterSaleServiceImpl.doRefund）
        log.info("refund subOrder={} amount={} reason={}", subOrderNo, amountMinor, reason);
        return "REFUND-" + subOrderNo;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public IncomeSummaryVO incomeSummary(String merchantNo, java.util.Collection<String> storeNos) {
        /*
         * **复用 merchantBills**，不另写一份查询。
         *
         * 那个方法里有一段实测出来的归属规则（无门店归属的存量流水要放行，
         * 否则开了两家店的商家结算页会突然变空）。在这里重写一遍，
         * 两处迟早走岔 —— 而走岔的表现是「总览的数和明细加起来对不上」，
         * 那比两处都错更难查。
         */
        long received = 0, inFlight = 0, pending = 0, offline = 0;
        int inFlightCount = 0;
        Long oldest = null;
        for (SettleBillVO b : merchantBills(merchantNo, storeNos)) {
            String st = b.status();
            if (StlBill.SPLIT_CONFIRMED.equals(st) || StlBill.PAID.equals(st)) {
                received += b.netMinor();
            } else if (StlBill.SPLIT.equals(st)) {
                // 已发起、等回执。**此前它混在「已到账」里** —— 而底下是桩，一分钱没动
                inFlight += b.netMinor();
                inFlightCount++;
                if (b.splitAt() != null && (oldest == null || b.splitAt() < oldest)) {
                    oldest = b.splitAt();
                }
            } else if (StlBill.OFFLINE_SETTLED.equals(st)) {
                // 当面收款：**他早就拿到了**，不该混进「待结算」让他以为平台还欠着
                offline += b.netMinor();
            } else if (!StlBill.REVERSED.equals(st)) {
                // 其余都算待结算。REVERSED 排除 —— 那是退款回退，不是收入
                pending += b.netMinor();
            }
        }
        return new IncomeSummaryVO(received, inFlight, pending, offline, inFlightCount, oldest);
    }

    @Override
    public List<SettleBillVO> merchantBills(String merchantNo, java.util.Collection<String> storeNos) {
        /*
         * 收窄的同时**必须放行没有门店归属的行**（store_no 为空 = V14 之前的存量流水）。
         *
         * 只按 IN 筛的代价是实测出来的：一家已经开了两家店的商家，历史流水全是
         * 主体级的，结算页一下子变成空的 —— 而「一条都没有」与「没有结算单」
         * 长得一模一样，商家会以为钱没了。**钱的页面不能让人产生这种误会。**
         *
         * 代价是这些无归属的行在每家店的视角下都会出现一次。两害相权：
         * 重复显示看得见、能解释；凭空消失看不见、只会引出一通电话。
         *
         * 空集合在这里**不等于不过滤**：那是越权陷阱，与订单侧同一个判断。
         */
        boolean scoped = storeNos != null && !storeNos.isEmpty()
                && storeNos.stream().anyMatch(x -> x != null && !x.isBlank());
        List<StlBill> bills = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getEntityNo, merchantNo)
                        .and(scoped, w -> w.in(StlBill::getStoreNo, storeNos)
                                .or().isNull(StlBill::getStoreNo))
                        .orderByDesc(StlBill::getId)));
        // 批次一次查齐再拼：逐单查的话，一屏 20 单就是 20 次往返
        var batches = batchesOf(bills);
        return bills.stream()
                .map(b -> toVO(b, batches.get(b.getBatchNo())))
                .toList();
    }

    @Override
    public SettleBillVO merchantBill(String merchantNo, String settleNo) {
        StlBill bill = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo)
                        .eq(StlBill::getEntityNo, merchantNo)
                        .last("limit 1")));
        if (bill == null) {
            // 属主校验写进查询条件：settleNo 可猜，不能先查出来再比对
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return toVO(bill, batchesOf(List.of(bill)).get(bill.getBatchNo()));
    }

    @Override
    public RateCardVO rateCard() {
        // 商家看到的是第三方那一行 —— 自营的单商家不参与分账，给他看自营费率只会造成误解
        var rates = feeRuleService.effectiveRates(System.currentTimeMillis());
        int owned = rates.getOrDefault(
                MerchantQueryPort.MODE_THIRD_PARTY + "|" + StlFeeRule.MERCHANT_OWNED, 0);
        int platform = rates.getOrDefault(
                MerchantQueryPort.MODE_THIRD_PARTY + "|" + StlFeeRule.PLATFORM, 0);
        return new RateCardVO(owned, platform,
                "自带客流（扫店铺码进店）零佣金；平台客流按 "
                        + (platform / 100.0) + "% 收取。费率以下单时快照为准，调整不影响历史订单。");
    }

    @Override
    public long splitLogCount(String settleNo, String action) {
        return DataScopeContext.executeWithoutScope(() ->
                splitLogMapper.selectCount(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getSettleNo, settleNo)
                        .eq(StlSplitLog::getSplitAction, action)
                        .eq(StlSplitLog::getResult, "SUCCESS")));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 调分账通道并留痕。**幂等在这一层**：靠 {@code uk_request_no} 挡住重复指令，
     * 不依赖支付服务商的幂等实现是否可靠 —— 换真实通道时这段不用改。
     *
     * <p>**失败也写日志**：「发过但失败了」和「压根没发」是完全不同的排查方向。
     */
    private boolean callProvider(String action, StlBill bill, String requestNo) {
        boolean duplicated = DataScopeContext.executeWithoutScope(() ->
                splitLogMapper.selectCount(Wrappers.<StlSplitLog>lambdaQuery()
                        .eq(StlSplitLog::getRequestNo, requestNo)
                        .eq(StlSplitLog::getResult, "SUCCESS"))) > 0;
        if (duplicated) {
            return true;
        }

        /*
         * 补差走的是**补差额**，不是净额。
         *
         * 三个动作的金额口径本来就不同：分账/回退动的是平台应收（netMinor 的对侧），
         * 补差动的是买家用积分抵掉的那部分。用同一个 amount 会把补差金额记成净额，
         * 而两者相差正好是一整笔货款 —— 这种错在日志里也看不出来，
         * 因为两个数都是「一个合理的金额」。
         */
        boolean isSubsidy = StlSplitLog.SUBSIDY.equals(action)
                || StlSplitLog.SUBSIDY_RETURN.equals(action);
        long amount = isSubsidy ? nz(bill.getSubsidyMinor()) : nz(bill.getNetMinor());
        /*
         * 收款号取**账单上的快照**，不是「这家店现在用哪个号」。
         * 商家改号之后还没打的历史流水，仍要打进当初收款的那个账户 ——
         * 退款尤其：从新账户扣，两个账户各错一笔且方向相反。
         *
         * 存量账单没有快照（V14 之前的行），落回主体默认号 —— 与它们生成时的行为一致。
         */
        String payTo = bill.getPayMerchantNo();
        if (payTo == null || payTo.isBlank()) {
            payTo = merchantQueryPort.payMerchantNoOf(bill.getEntityNo(), bill.getStoreNo()).orElse(null);
        }
        SplitGateway.Result result = switch (action) {
            case StlSplitLog.REVERSE -> gateway.reverse(bill.getSubOrderNo(), payTo, amount, requestNo);
            case StlSplitLog.SUBSIDY -> gateway.subsidy(bill.getSubOrderNo(), payTo, amount, requestNo);
            case StlSplitLog.SUBSIDY_RETURN ->
                    gateway.subsidyReturn(bill.getSubOrderNo(), payTo, amount, requestNo);
            default -> gateway.split(bill.getSubOrderNo(), payTo, amount, requestNo);
        };

        StlSplitLog entry = new StlSplitLog();
        entry.setSettleNo(bill.getSettleNo());
        entry.setSubOrderNo(bill.getSubOrderNo());
        entry.setSplitAction(action);
        entry.setAmountMinor(amount);
        // 失败的指令也要有唯一 requestNo，但不能挡住重试 —— 加时间戳后缀
        entry.setRequestNo(result.success() ? requestNo
                : requestNo + "-F" + System.currentTimeMillis());
        entry.setResult(result.success() ? "SUCCESS" : "FAILED");
        entry.setProviderNo(result.providerNo());
        entry.setMessage(result.message());
        entry.setAt(System.currentTimeMillis());
        entry.setTenantNo("MAIN");
        entry.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> splitLogMapper.insert(entry));
        return result.success();
    }

    /**
     * 这一笔的<b>通道手续费</b>：收多少、按哪一版、什么来源、从谁身上收。
     *
     * <p>{@code stl_bill} 的这四列建表时就有，而**此前全库零写入**；运营端能配的
     * {@code sys_pay_channel_rate} 也没有任何消费者 —— 配了费率不影响任何一笔账，
     * 运营却会以为改了就生效了。这个方法把两头接上。
     *
     * <p><b>基数是实付金额，不是结算基数 {@code gross}。</b>
     * 通道按真正流经它的那笔钱收费，而 gross 还加回了平台优惠与积分抵扣 ——
     * 那两笔钱压根没从通道走过。用 gross 会让手续费凭空变大，金额越大差得越多。
     *
     * <p><b>没配过费率就留空，不兜 0。</b>「没配过」与「配了 0%」在库里必须长得不一样：
     * 兜 0 之后，事后没有任何人能回答「这笔手续费当时是免的，还是根本没人配」。
     *
     * <p>线下（当面）收款返回空值：钱从没进过通道，谈不上通道手续费。
     */
    private ChannelFee channelFeeOf(SettleSourcePort.SettleSource src, long at) {
        if (src.payChannel() == null || PayModes.OFFLINE.equals(src.payChannel())) {
            return ChannelFee.none();
        }
        String resolved = merchantQueryPort.feeBearerOf(src.merchantNo(), src.storeNo(), src.payChannel());
        String bearer = resolved == null || resolved.isBlank() ? MchFeeBearer.UNKNOWN : resolved;
        var rate = masterDataPort.channelFeeRate(src.payChannel(), src.payScene(),
                merchantQueryPort.legalFormOf(src.merchantNo()), at);
        if (rate == null) {
            /*
             * 承担方仍然要落：它来自进件档案，与费率配没配无关。
             * 只有它有值而 source 为 null 时，读单据的人才看得出
             * 「知道该谁出，但不知道出多少」—— 而那正是今天的真实状态。
             */
            return new ChannelFee(0L, 0, null, bearer);
        }
        /*
         * 单笔最低手续费：通道普遍有这一档，小额单按率算不足最低值时按最低值收。
         * 漏了它的表现是小额单的手续费系统性偏低，而单看任何一笔都「算得对」。
         */
        long byRate = src.payAmount() * rate.rateBp() / 10000;
        // 今天只有一种来源。真出现优惠费率时该由费率版本自己标明，不在这里猜
        return new ChannelFee(Math.max(byRate, rate.minFeeMinor()), rate.rateBp(),
                StlBill.FEE_STANDARD, bearer);
    }

    /**
     * @param bearer {@code MERCHANT} 时这笔钱由商家出，要从他的实得里扣掉；
     *               {@code PLATFORM} / {@code UNKNOWN} 则不扣 —— <b>不知道不等于商家出</b>，
     *               猜一个方向就是替他做主，而这个方向上猜错等于少付给他钱
     */
    private record ChannelFee(long minor, int rateBp, String source, String bearer) {
        /** 线下单：{@code bearer} 也留 null —— 没有通道，就没有「谁承担通道费」这个问题 */
        static ChannelFee none() {
            return new ChannelFee(0L, 0, null, null);
        }

        /** 从商家实得里扣掉的部分。承担方不是商家（含未知）时为 0 */
        long borneByMerchant() {
            return MchFeeBearer.MERCHANT.equals(bearer) ? minor : 0L;
        }
    }

    /** {@code mch_payment_merchant.fee_bearer} 的取值。 */
    private static final class MchFeeBearer {
        static final String MERCHANT = "MERCHANT";

        /**
         * <b>还没进件，我们不知道谁承担。</b>
         *
         * <p>要有这个显式值，是因为 {@code stl_bill.fee_bearer} 是
         * {@code NOT NULL DEFAULT 'MERCHANT'} —— 传 null 进去被 MyBatis-Plus 跳过，
         * 落库变成建表默认的 {@code MERCHANT}，于是**「不知道」在单据上长得和
         * 「商家承担」一模一样**。写这条用例时正是被它骗过：断言
         * 「承担方是 MERCHANT」通过了，而代码其实一个字都没解析到。
         *
         * <p>与 {@code channel_fee_source} 为 null 是同一条规矩的两处应用：
         * 不知道就要说不知道，兜一个看着合理的默认值是账目上最难查的一类错。
         */
        static final String UNKNOWN = "UNKNOWN";

        private MchFeeBearer() {
        }
    }

    private StlBill findBySubOrder(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSubOrderNo, subOrderNo).last("limit 1")));
    }

    private StlBill require(String settleNo) {
        StlBill bill = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("limit 1")));
        if (bill == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return bill;
    }

    private void update(StlBill bill) {
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(bill));
    }

    // ---------------------------------------------------------------- 自营应付账款（P0-7）

    @Override
    public List<SettleBillVO> opsPayables(String status, String entityNo) {
        /*
         * ★ 接数据域（批④）：这条只有 /ops/payables 调，配了商家域的财务
         * 只该看到自己负责那几家的应付。此前这里 executeWithoutScope ——
         * **配置页显示「已限定」而查询照样全量**，那比不限定更危险。
         *
         * entityNo 是用户给的筛选条件，与数据域是两件事：传一个域外的主体号，
         * 筛选成立而数据域拒绝 → 空列表，正确。
         */
        return billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        // 只看自营：第三方的钱走分账，不存在「应付账款」这件事
                        .eq(StlBill::getBusinessMode, MerchantQueryPort.MODE_SELF_OPERATED)
                        .eq(status != null && !status.isBlank(), StlBill::getStatus, status)
                        .eq(entityNo != null && !entityNo.isBlank(), StlBill::getEntityNo, entityNo)
                        .orderByDesc(StlBill::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<SettleBillVO> opsBills(String status, String entityNo, String businessMode) {
        /*
         * 两条轨道都看。运营要回答的是「这家店的钱到哪一步了」，
         * 而那不该因为经营模式不同就分成两个入口去查 —— 分开的直接后果是
         * 一家同时有自营店和第三方店的商家，运营得在两个页面之间对照才拼得出全貌。
         */
        // ★ 接数据域（批④），理由同 opsPayables
        return billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(status != null && !status.isBlank(), StlBill::getStatus, status)
                        .eq(entityNo != null && !entityNo.isBlank(), StlBill::getEntityNo, entityNo)
                        .eq(businessMode != null && !businessMode.isBlank(),
                                StlBill::getBusinessMode, businessMode)
                        .orderByDesc(StlBill::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<SplitLogVO> opsSplitLogs(String settleNo, String action) {
        /*
         * 失败的指令**也要给**：出问题时要看的恰恰是它们。
         * 只给成功的等于把「为什么这单没分成」这个问题的答案藏起来。
         */
        return DataScopeContext.executeWithoutScope(() ->
                        splitLogMapper.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                                .eq(settleNo != null && !settleNo.isBlank(),
                                        StlSplitLog::getSettleNo, settleNo)
                                .eq(action != null && !action.isBlank(),
                                        StlSplitLog::getSplitAction, action)
                                .orderByDesc(StlSplitLog::getId)))
                .stream()
                .map(l -> new SplitLogVO(l.getSettleNo(), l.getSubOrderNo(), l.getSplitAction(),
                        nz(l.getAmountMinor()), l.getResult(), l.getRequestNo(),
                        l.getProviderNo(), l.getMessage(),
                        l.getCreatedAt() == null ? 0L
                                : l.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                        .toInstant().toEpochMilli()))
                .toList();
    }

    @Override
    @Transactional
    public SettleBillVO confirmRecon(String settleNo, String operatorNo) {
        StlBill b = requireSelfOperated(settleNo);
        if (StlBill.CONFIRMED.equals(b.getStatus()) || StlBill.PAID.equals(b.getStatus())) {
            return toVO(b);   // 幂等：重复确认不报错
        }
        if (!StlBill.PENDING_RECON.equals(b.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        b.setStatus(StlBill.CONFIRMED);
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));
        return toVO(b);
    }

    @Override
    @Transactional
    public SettleBillVO markPaid(String settleNo, String paymentRef, String operatorNo) {
        if (paymentRef == null || paymentRef.isBlank()) {
            // 没有凭证号的「已付」等于没记：事后对不上银行流水，也说不清是谁付的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StlBill b = requireSelfOperated(settleNo);
        if (StlBill.PAID.equals(b.getStatus())) {
            return toVO(b);   // 幂等
        }
        if (!StlBill.CONFIRMED.equals(b.getStatus())) {
            // 未对账就付款 = 付了一个双方还没认的数
            throw BizException.of(ErrorCode.CONFLICT);
        }
        /*
         * **票到付款**。放行两种：票已核验，或已被显式标记为无票供应商。
         *
         * 不放行「待开票 / 已提交待核验 / 已驳回」——先款后票的代价很具体：
         * 钱付完了，供应商开票的动力就没了，而平台没有发票无法列支，
         * 等于付了钱还多缴税。追票的成本远高于账期上让一步。
         */
        String inv = b.getInvoiceStatus();
        if (!StlBill.INV_VERIFIED.equals(inv) && !StlBill.INV_NONE.equals(inv)) {
            throw BizException.of(ErrorCode.INVOICE_REQUIRED);
        }
        b.setStatus(StlBill.PAID);
        b.setPaymentRef(paymentRef);
        b.setPaidAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));

        /*
         * 归集路径的兑付时点在这里，**不是 executeSplit** ——
         * 自营单根本不走分账（钱本来就在平台账户，没有划转动作）。
         * 只把确认挂在分账上的话，归集路径下的 USE 会永远停在 PENDING，
         * 池子只进不出，而且不报任何错。
         */
        pointsService.confirmDeduction(b.getSubOrderNo());
        return toVO(b);
    }

    @Override
    @Transactional
    public SettleBillVO markNoInvoice(String settleNo, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            // 无票是要付出税务代价的（这笔支出不能列支），得说得出为什么
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StlBill b = requireSelfOperated(settleNo);
        if (StlBill.INV_VERIFIED.equals(b.getInvoiceStatus())) {
            // 票都核验过了还标无票，多半是点错了
            throw BizException.of(ErrorCode.CONFLICT);
        }
        b.setInvoiceStatus(StlBill.INV_NONE);
        DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));
        return toVO(b);
    }

    /** 自营专用操作的公共前置：单子存在，且确实是自营的 */
    private StlBill requireSelfOperated(String settleNo) {
        StlBill b = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectOne(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getSettleNo, settleNo).last("limit 1")));
        if (b == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (!MerchantQueryPort.MODE_SELF_OPERATED.equals(b.getBusinessMode())) {
            // 第三方的单走分账，没有「对账/付款」这两步。用 CONFLICT 而不是 NOT_FOUND：
            // 单子是存在的，只是这个操作对它没有意义
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return b;
    }


    // ---------------------------------------------------------------- 进项票（P0-8/10）

    @Override
    @Transactional
    public PurchaseInvoiceVO submitInvoice(String merchantNo, SubmitInvoiceCommand cmd) {
        if (cmd.invoiceNumber() == null || cmd.invoiceNumber().isBlank()
                || cmd.titleName() == null || cmd.titleName().isBlank()
                || cmd.period() == null || cmd.period().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<StlBill> bills = billsAwaitingInvoice(merchantNo);
        if (bills.isEmpty()) {
            // 没有待开票的单还提交发票，多半是周期选错了
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        long payable = bills.stream().mapToLong(b -> nz(b.getNetMinor())).sum();
        if (payable != cmd.amountMinor()) {
            /*
             * 金额对不上就拒收。这是最实际的一条防错：多半是周期选错或漏了几单，
             * 而这种错拖到报税时才发现，票已经开出来了、退回重开要走红字流程。
             */
            throw BizException.of(ErrorCode.INVOICE_AMOUNT_MISMATCH);
        }

        StlPurchaseInvoice inv = new StlPurchaseInvoice();
        inv.setInvoiceNo(BizKey.next(BizKey.SETTLE_BILL) + "I");
        inv.setEntityNo(merchantNo);
        inv.setPeriod(cmd.period());
        inv.setInvoiceCode(cmd.invoiceCode());
        inv.setInvoiceNumber(cmd.invoiceNumber());
        inv.setInvoiceType(cmd.invoiceType() == null ? StlPurchaseInvoice.GENERAL : cmd.invoiceType());
        inv.setTitleName(cmd.titleName());
        inv.setTitleTaxNo(cmd.titleTaxNo());
        inv.setAmountMinor(cmd.amountMinor());
        inv.setTaxAmountMinor(cmd.taxAmountMinor());
        inv.setTaxRate(cmd.taxRate());
        inv.setInvoiceDate(cmd.invoiceDate());
        inv.setImageUrl(cmd.imageUrl());
        inv.setStatus(StlPurchaseInvoice.SUBMITTED);
        DataScopeContext.executeWithoutScope(() -> purchaseInvoiceMapper.insert(inv));

        for (StlBill b : bills) {
            b.setPurchaseInvoiceNo(inv.getInvoiceNo());
            b.setInvoiceStatus(StlBill.INV_SUBMITTED);
            DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));
        }
        return toVO(inv);
    }

    @Override
    public List<PurchaseInvoiceVO> myInvoices(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        purchaseInvoiceMapper.selectList(Wrappers.<StlPurchaseInvoice>lambdaQuery()
                                .eq(StlPurchaseInvoice::getEntityNo, merchantNo)
                                .orderByDesc(StlPurchaseInvoice::getId)))
                .stream().map(this::toVO).toList();
    }

    @Override
    public List<PurchaseInvoiceVO> opsInvoices(String status) {
        /*
         * **不绕过**：运营端的全量进项票队列，数据域该在这里起作用。
         * 与上面的 `myInvoices(merchantNo)` 是一对照 —— 那一条按参数过滤、
         * 跑在 B 端会话（SELF 维度）里，不绕就 fail-closed 变空白，所以它保留绕过。
         * 两条读同一张表，绕不绕的判据是**归属由谁保证**，不是「哪个更方便」。
         */
        return purchaseInvoiceMapper.selectList(Wrappers.<StlPurchaseInvoice>lambdaQuery()
                        .eq(status != null && !status.isBlank(),
                                StlPurchaseInvoice::getStatus, status)
                        .orderByDesc(StlPurchaseInvoice::getId))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public PurchaseInvoiceVO verifyInvoice(String invoiceNo, String operatorNo) {
        StlPurchaseInvoice inv = requireInvoice(invoiceNo);
        if (StlPurchaseInvoice.VERIFIED.equals(inv.getStatus())) {
            return toVO(inv);   // 幂等
        }
        /*
         * 三流一致的**机器可判部分**：开票方名称必须等于供应商主体名。
         * 不一致会被认定虚开风险，而「个体户用法人个人名义开票」这类肉眼很容易放过。
         *
         * ⚠️ 资金流那一环（结算账户户名）比对不了——库里只存账户掩码没存户名。
         * 这里不假装查过它，人工核对仍是必要的一步。
         */
        if (!titleMatched(inv)) {
            throw BizException.of(ErrorCode.INVOICE_TITLE_MISMATCH);
        }
        inv.setStatus(StlPurchaseInvoice.VERIFIED);
        inv.setVerifiedBy(operatorNo);
        inv.setVerifiedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> purchaseInvoiceMapper.updateById(inv));
        updateBillsInvoiceStatus(invoiceNo, StlBill.INV_VERIFIED);
        return toVO(inv);
    }

    @Override
    @Transactional
    public PurchaseInvoiceVO rejectInvoice(String invoiceNo, String reason, String operatorNo) {
        if (reason == null || reason.isBlank()) {
            // 供应商得知道是抬头错了、金额不符还是影像看不清，否则只能反复试
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StlPurchaseInvoice inv = requireInvoice(invoiceNo);
        inv.setStatus(StlPurchaseInvoice.REJECTED);
        inv.setRejectReason(reason);
        inv.setVerifiedBy(operatorNo);
        inv.setVerifiedAt(System.currentTimeMillis());
        DataScopeContext.executeWithoutScope(() -> purchaseInvoiceMapper.updateById(inv));
        // 单子退回待开票，供应商可以重开重传
        updateBillsInvoiceStatus(invoiceNo, StlBill.INV_PENDING);
        return toVO(inv);
    }

    /** 已对账确认、且还没开票的单 —— 一张票覆盖它们全部 */
    private List<StlBill> billsAwaitingInvoice(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getEntityNo, merchantNo)
                        .eq(StlBill::getBusinessMode, MerchantQueryPort.MODE_SELF_OPERATED)
                        .eq(StlBill::getStatus, StlBill.CONFIRMED)
                        .eq(StlBill::getInvoiceStatus, StlBill.INV_PENDING)));
    }

    private void updateBillsInvoiceStatus(String invoiceNo, String invoiceStatus) {
        for (StlBill b : DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getPurchaseInvoiceNo, invoiceNo)))) {
            b.setInvoiceStatus(invoiceStatus);
            if (StlBill.INV_PENDING.equals(invoiceStatus)) {
                b.setPurchaseInvoiceNo(null);   // 驳回后解绑，下次重新关联
            }
            DataScopeContext.executeWithoutScope(() -> billMapper.updateById(b));
        }
    }

    private boolean titleMatched(StlPurchaseInvoice inv) {
        return merchantQueryPort.find(inv.getEntityNo())
                .map(m -> m.merchantName() != null
                        && m.merchantName().trim().equals(inv.getTitleName().trim()))
                .orElse(false);
    }

    private StlPurchaseInvoice requireInvoice(String invoiceNo) {
        StlPurchaseInvoice inv = DataScopeContext.executeWithoutScope(() ->
                purchaseInvoiceMapper.selectOne(Wrappers.<StlPurchaseInvoice>lambdaQuery()
                        .eq(StlPurchaseInvoice::getInvoiceNo, invoiceNo).last("limit 1")));
        if (inv == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return inv;
    }

    private PurchaseInvoiceVO toVO(StlPurchaseInvoice i) {
        List<String> settleNos = DataScopeContext.executeWithoutScope(() ->
                        billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                                .eq(StlBill::getPurchaseInvoiceNo, i.getInvoiceNo())))
                .stream().map(StlBill::getSettleNo).toList();
        return new PurchaseInvoiceVO(i.getInvoiceNo(), i.getEntityNo(), i.getPeriod(),
                i.getInvoiceCode(), i.getInvoiceNumber(), i.getInvoiceType(),
                i.getTitleName(), i.getTitleTaxNo(), nz(i.getAmountMinor()),
                nz(i.getTaxAmountMinor()), nzi(i.getTaxRate()), i.getInvoiceDate(),
                i.getImageUrl(), i.getStatus(), i.getRejectReason(),
                titleMatched(i), settleNos);
    }


    // ---------------------------------------------------------------- 平台开票信息（P0-11）

    private static final String TITLE_KEY = "finance.invoice-title";
    /** 五项都空的默认值：**不编假数据** —— 空着能让人立刻发现「还没配」 */
    private static final String TITLE_DEFAULT =
            "{\"companyName\":\"\",\"taxNo\":\"\",\"address\":\"\",\"phone\":\"\",\"bankAccount\":\"\"}";

    @Override
    public java.util.Map<String, String> platformInvoiceTitle() {
        return parseFlatJson(settingPort.get(TITLE_KEY, TITLE_DEFAULT));
    }

    @Override
    public java.util.Map<String, String> savePlatformInvoiceTitle(
            java.util.Map<String, String> fields, String operatorNo) {
        String company = fields == null ? null : fields.get("companyName");
        String taxNo = fields == null ? null : fields.get("taxNo");
        if (company == null || company.isBlank() || taxNo == null || taxNo.isBlank()) {
            // 缺这两项供应商根本开不出票，存下去只会让人以为已经配好了
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        StringBuilder sb = new StringBuilder("{");
        for (String k : List.of("companyName", "taxNo", "address", "phone", "bankAccount")) {
            String v = fields.getOrDefault(k, "");
            sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":\"")
                    .append(v == null ? "" : v.replace("\"", "")).append('"');
        }
        settingPort.put(TITLE_KEY, sb.append("}").toString(), operatorNo);
        return platformInvoiceTitle();
    }

    /** 只有五个字符串字段，手解比引 JSON 依赖轻；解析失败给空 Map 而不是抛异常 */
    private static java.util.Map<String, String> parseFlatJson(String jsonText) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        var m = java.util.regex.Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").matcher(jsonText);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }


    // ---------------------------------------------------------------- 对账单（P0-12）

    @Override
    public StatementVO statement(String merchantNo, String period) {
        List<StlBill> bills = DataScopeContext.executeWithoutScope(() ->
                billMapper.selectList(Wrappers.<StlBill>lambdaQuery()
                        .eq(StlBill::getEntityNo, merchantNo)
                        .orderByAsc(StlBill::getId))).stream()
                .filter(b -> period == null || period.isBlank() || period.equals(periodOf(b)))
                .toList();

        List<StatementVO.Line> lines = new java.util.ArrayList<>();
        List<String> vouchers = new java.util.ArrayList<>();
        long gross = 0;
        long commission = 0;
        long serviceFee = 0;
        long net = 0;
        String mode = null;
        for (StlBill b : bills) {
            String voucher = voucherOf(b);
            if (voucher != null && !voucher.isBlank()) {
                vouchers.add(voucher);
            }
            lines.add(new StatementVO.Line(b.getSettleNo(), b.getOrderNo(), b.getSubOrderNo(),
                    nz(b.getGrossMinor()), nz(b.getCommissionMinor()), nz(b.getServiceFeeMinor()),
                    nz(b.getNetMinor()), nzi(b.getCommissionRate()),
                    b.getStatus(), b.getInvoiceStatus(),
                    b.getPaidAt() != null ? b.getPaidAt() : b.getSplitAt(), voucher));
            gross += nz(b.getGrossMinor());
            commission += nz(b.getCommissionMinor());
            serviceFee += nz(b.getServiceFeeMinor());
            net += nz(b.getNetMinor());
            mode = b.getBusinessMode();
        }
        return new StatementVO(period, merchantNo, mode, gross, commission, serviceFee, net,
                bills.size(), vouchers, lines);
    }

    /**
     * 该行的凭证号：自营取付款凭证（网银流水），第三方取分账回执（{@code provider_no}）。
     *
     * <p>两者语义不同但**作用相同**——都是与外部账单勾对的锚点，所以对账单上合成一列。
     * 分开两列的话，商家要先知道自己是哪种模式才知道该看哪一列，
     * 而那正是他不需要关心的事。
     */
    private String voucherOf(StlBill b) {
        if (b.getPaymentRef() != null && !b.getPaymentRef().isBlank()) {
            return b.getPaymentRef();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        splitLogMapper.selectList(Wrappers.<StlSplitLog>lambdaQuery()
                                .eq(StlSplitLog::getSettleNo, b.getSettleNo())
                                .eq(StlSplitLog::getResult, "SUCCESS")
                                .orderByDesc(StlSplitLog::getId))).stream()
                .map(StlSplitLog::getProviderNo).filter(x -> x != null && !x.isBlank())
                .findFirst().orElse(null);
    }

    /** 结算单的周期按创建月算 —— 与应付账款的出账周期一致 */
    private String periodOf(StlBill b) {
        return b.getCreatedAt() == null ? "" : b.getCreatedAt().toLocalDate().withDayOfMonth(1)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
    }


    private SettleBillVO toVO(StlBill b) {
        return toVO(b, null);
    }

    /**
     * @param batch 这一单所属批次；<b>由调用方一次查好传进来</b>，不在这里逐单查 ——
     *              列表页一屏 20 单，逐单查就是 20 次往返，而同一批的单往往就那么几个批次
     */
    private SettleBillVO toVO(StlBill b, StlSettleBatch batch) {
        return new SettleBillVO(b.getSettleNo(), b.getSubOrderNo(), b.getOrderNo(), b.getEntityNo(),
                nz(b.getGrossMinor()), nz(b.getCommissionMinor()), nz(b.getServiceFeeMinor()),
                nz(b.getNetMinor()), b.getTrafficSource(), nzi(b.getCommissionRate()),
                b.getStatus(),
                b.getCreatedAt() == null ? 0L
                        : b.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                b.getSplitAt(), b.getStoreNo(), b.getPayMerchantNo(),
                b.getBusinessMode(), b.getInvoiceStatus(), b.getPaymentRef(),
                nz(b.getPointsFeeMinor()),
                b.getSettleableAt(), batch == null ? null : batch.getDueAt(), b.getBatchNo(),
                batch == null ? null : batch.getStatus(),
                batch == null ? null : batch.getBlockedReason());
    }

    /** 一次把这批单涉及的批次查齐，避免列表页逐单查 */
    private java.util.Map<String, StlSettleBatch> batchesOf(java.util.List<StlBill> bills) {
        java.util.Set<String> nos = bills.stream().map(StlBill::getBatchNo)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (nos.isEmpty()) {
            /*
             * ⚠️ 必须是 HashMap，**不能是 Map.of()**。
             * 大多数单还没入批，batchNo 是 null，调用方会 get(null) ——
             * 而不可变 Map 对 null 键直接抛 NPE。
             *
             * 这个坑的表现极具迷惑性：全局信封把异常包成 200 + data:null，
             * 于是「结算单列表」变成一个空列表，**没有任何错误**。
             * 我就是这么让 M7SettleFlowTest 的 15 条一起红的。
             */
            return new java.util.HashMap<>();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        batchMapper.selectList(Wrappers.<StlSettleBatch>lambdaQuery()
                                .in(StlSettleBatch::getBatchNo, nos)))
                .stream()
                .collect(java.util.stream.Collectors.toMap(StlSettleBatch::getBatchNo, x -> x,
                        (a, c) -> a, java.util.HashMap::new));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }
}
