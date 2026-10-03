package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.dto.PaymentApplymentVO;

import java.util.List;

/**
 * 收款进件（ADR-002）：把主体在支付通道开成二级商户，直到**真的能收钱**。
 *
 * <p><b>它与入驻审核是两条独立的链路</b>：
 * <ul>
 *   <li>入驻审核 —— 平台批，依据平台规则（行业、社区），不过就开不了店</li>
 *   <li>收款进件 —— <b>通道批</b>，依据通道规则（执照、法人、结算账户），
 *       不过则店照开、货照上架，但<b>收不了钱</b></li>
 * </ul>
 * 把两者合成一步，「平台批了但通道拒了」这个状态就没有地方承载 ——
 * 而它是真实世界里最常见的一种。
 *
 * <p>主体激活时已建好一条 APPLYING 的占位记录（否则第一笔订单来了没有收款方），
 * 本服务负责把它从占位推到 ACTIVE。
 */
public interface MerchantPaymentService {

    /**
     * 查本主体的进件状态。
     *
     * <p>返回**每个通道一条**：微信过了、支付宝还没过是正常状态，
     * 合并成一个「进件中」会让商家以为微信也没好。
     */
    List<PaymentApplymentVO> list(String merchantNo);

    /**
     * 本主体**能开的全部通道**，含还没开的。
     *
     * <p>与 {@link #list(String)} 的区别是那一条只回答「已经开了的怎么样了」——
     * 而商家问的第一个问题往往是<b>「我还能开什么」</b>。
     * 只给已开通的列表，页面就永远长不出「去开通支付宝」那个入口，
     * 而后端明明支持多通道。
     *
     * <p>没开过的通道返回 {@code applyStatus=NONE} 的一行，
     * <b>不是不返回</b> —— 页面按同一套状态机渲染，不用为「没有这一行」另写一支。
     */
    List<PaymentApplymentVO> availableChannels(String merchantNo);

    /**
     * 补交资料并提交进件。
     *
     * <p><b>结算账号明文只在本次调用中存在</b>：传给通道，库里只留掩码
     * （{@code settle_account_masked}）。回显给任何端的都是掩码 —— 包括商家自己，
     * 因为 B 端也可能被别人拿到（ADR-002 §5）。
     *
     * <p>已经 ACTIVE 的通道再提交会被拒：重复开户在通道侧会得到一个新的二级商户号，
     * 而历史订单的分账仍指向旧号 —— 那是对不上账的开始。
     */
    PaymentApplymentVO submit(String merchantNo, SubmitCommand cmd);

    /**
     * 向通道回查进件结果并落库。
     *
     * <p><b>查询是权威，回调只是触发器</b>：回调会丢、会重、会乱序，
     * 只信回调的系统在通道重推历史消息时会把已开好的户改回「审核中」。
     * 所以回调进来也调这个方法，而不是直接拿回调体写库。
     */
    PaymentApplymentVO refresh(String merchantNo, String payChannel, String storeNo);

    /**
     * 扫一轮<b>还在审核中</b>的进件，主动去通道问结果。
     *
     * <p><b>为什么必须有它</b>：今天进件状态只有商家自己点「刷新」才会推进 ——
     * 没有回调、没有轮询。商家不点，单子就一直显示「审核中」，
     * 而通道那边可能三天前就批了。这不是体验问题：<b>他会以为平台没在办</b>。
     *
     * @param limit      一轮最多查多少条，防止第一次上线时把通道打满
     * @param staleAfter 超过这个毫秒数还没出结果的，<b>单独计数并告警</b> ——
     *                   通道审核一般一两天，卡更久说明这一单需要人去问，
     *                   而不是继续等下一轮
     */
    PollResult pollApplying(int limit, long staleAfter);

    /**
     * @param scanned  本轮查了几条
     * @param settled  查出结果的（ACTIVE 或 REJECTED）
     * @param failed   查询本身失败的。<b>与「还没结果」分开计</b> ——
     *                 前者是我方或通道出了问题，后者是正常等待
     * @param stale    超期仍无结果的，要有人去问
     */
    record PollResult(int scanned, int settled, int failed, int stale) {
    }

    /**
     * 为某家门店<b>新开一次进件</b>，拿一个独立的收款号。
     *
     * <p>这是「分开结算」的入口。微信侧一个商户号只能绑一个结算账户 ——
     * 要两家店各收各的钱，只能进件两次。
     *
     * <p>不新开就是合并结算：门店不配号，走主体默认号。<b>两种模式都是配置的结果，
     * 没有开关</b> —— 存一个 settleMode 枚举会立刻与实际配置打架
     * （配置说分、开关说合，听谁的都错）。
     *
     * @return 新建的占位记录（APPLYING）；商家补完资料后走 {@link #submit} 推进
     * @throws ai.neargo.shop.common.BizException 门店不属于本主体，或该店已经有进件记录
     */
    PaymentApplymentVO openForStore(String merchantNo, String storeNo, String payChannel);

    /**
     * @param payChannel        目标通道，如 WECHAT
     * @param settleAccountType PERSONAL_BANK_CARD / MERCHANT_ID；为空时按法律形态取默认
     * @param settleAccount     结算账号明文，**不落库**
     * @param licenses          资质图地址
     * @param storeNo           为哪家门店进件；<b>为空 = 主体级默认号</b>（单店永远走这条）
     */
    record SubmitCommand(String payChannel, String settleAccountType, String settleAccount,
                         List<String> licenses, String contactName, String contactPhone,
                         String storeNo) {
    }
}
