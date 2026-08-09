package ai.neargo.shop.settle;

import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.MerchantPointsRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointAccountVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointRecordVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsDeductibleVO;
import ai.neargo.shop.settle.dto.PointsVOs.PointsOverviewVO;

import java.util.List;

/**
 * 积分域读侧服务。设计见 docs/technical/积分域-完整方案.md。
 *
 * <p><b>模型是预付费</b>：商家发放积分的那一刻就从他的货款里扣走服务费进积分池；
 * 用户在任意一家花分时，由平台调通道的补差接口把差额补进那家的二级商户账户。
 * 发放之后这批分与发放商家<b>再无关系</b>。
 *
 * <p>本接口只有读。写侧（发放 / 抵扣 / 兑付成立 / 到期 / 退款扣回）依赖
 * 支付通道的补差与回退能力，落在交易与结算的事务里，不从这里暴露 ——
 * 单独开一个「发积分」的入口，迟早会有人绕过订单直接调它。
 */
public interface PointsService {

    /** 我的积分账户。可用与待生效分开返回。 */
    PointAccountVO account(String userNo);

    /** 我的积分流水。 */
    List<PointRecordVO> records(String userNo, int page, int size);

    /**
     * 结算页试算：本单最多能抵多少。
     *
     * <p>判据顺序与下单时<b>完全一致</b>：四级开关 → 抵扣上限 → 账户余额，三者取小。
     * 顺序或口径不一致的话，用户会看到「结算页说能抵 30，下单后只抵了 25」。
     */
    PointsDeductibleVO deductible(String userNo, String merchantNo, long payableMinor);

    /** 商家的积分成本视图：本期发分服务费 + 开关状态。 */
    MerchantPointAccountVO merchantAccount(String merchantNo);

    /** 商家的发分服务费明细：一单一条。 */
    List<MerchantPointsRecordVO> merchantRecords(String merchantNo, String period, int page, int size);

    /**
     * 开/关本店积分。
     *
     * <p><b>关闭只影响将来</b> —— 已发出的分仍有效、已扣的服务费不退，
     * 否则关一次开关就是一次资金事故。
     */
    MerchantPointAccountVO toggleMerchant(String merchantNo, boolean enabled);

    /** 平台总览：流通中的积分与池子余额摆在一起 —— 恒等式 2 的两边。 */
    PointsOverviewVO overview(String market);
}
