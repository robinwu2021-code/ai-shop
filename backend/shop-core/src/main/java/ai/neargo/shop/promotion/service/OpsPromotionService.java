package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsActivityVO;
import ai.neargo.shop.promotion.dto.OpsPromotionVOs.OpsCouponVO;

import java.util.List;

/**
 * 运营侧的券与活动（P8）。
 *
 * <p><b>它要回答的不是「有哪些券」，而是「哪些券会出事」</b>：
 * 没设预算的、不限量的、单张优惠高得离谱的、限量快用完的。
 * 商家自己看不出来 —— 他只看自己那一张；跨商家排在一起才看得见。
 */
public interface OpsPromotionService {

    List<OpsCouponVO> coupons(String entityNo);

    List<OpsActivityVO> activities(String entityNo);

    /**
     * 强制停止一个活动。<b>原因必填且商家可见</b> ——
     * 不给理由的话，商家看到的是「我的活动莫名其妙没了」。
     */
    OpsActivityVO stop(String activityNo, String reason, String operatorNo);
}
