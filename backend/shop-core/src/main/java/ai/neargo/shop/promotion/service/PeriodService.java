package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.PeriodVOs.Decision;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodDetailVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodVO;
import ai.neargo.shop.promotion.dto.PeriodVOs.PurchaseLineVO;

import java.util.List;

/**
 * 社区集单的一期（TDD-营销-活动统一模型与集单 §2.3 状态机）。
 *
 * <p>B 端四个动作 + 定时任务两个推进。<b>退款不在事务里做</b>：逐张各自一个事务，
 * 一张分账回退失败不能把同一期其余买家的退款一起回滚。
 */
public interface PeriodService {

    List<PeriodVO> list(String entityNo, String status);

    PeriodDetailVO detail(String entityNo, String periodNo);

    /** 提前截单。只能从 OPEN */
    PeriodVO cutoffNow(String entityNo, String periodNo, String operatorNo);

    /** 未达起订量时商家的选择。只能从 SHORT */
    PeriodVO decide(String entityNo, String periodNo, Decision action, String operatorNo);

    List<PurchaseLineVO> purchaseLines(String entityNo, String periodNo);

    /** C 端商品详情的集单块（s26）。只读、不建期；不是集单商品时为空 */
    java.util.Optional<ai.neargo.shop.spi.marketing.PeriodPort.BatchView> batchOfGoods(String goodsNo);

    /** 任务：到点的 OPEN → CONFIRMED / SHORT。返回推进了几期 */
    int advanceDue(long now);

    /**
     * 任务：SHORT 过了处理时限 → CANCELLED 并退款；再给近几天已取消的期补一遍退款
     *（截单后才付款的单会落在那里）。返回新发起退款的子单数
     */
    int cancelUndecided(long now);
}
