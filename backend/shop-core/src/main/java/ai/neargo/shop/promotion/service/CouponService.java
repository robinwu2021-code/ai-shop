package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.CouponVOs.CouponIssueVO;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponVO;
import ai.neargo.shop.promotion.dto.CouponVOs.MyCouponVO;

import java.util.List;

/**
 * 商家自己的券（P4，新模型 {@code pmt_*}）。
 *
 * <p>与老的 {@code marketing.coupon.CouponService} 是<b>替换关系</b>：
 * 那一套是平台侧建券 + C 端领券中心，P9 退场。名字相同是故意的 ——
 * 到那时把老包删掉，这一个就是唯一的 CouponService。
 *
 * <p><b>敞口堵在建券这一步</b>（沿用营销预算前置的结论）：折扣券必须封顶、
 * 发行量必须有数、预算非零时必须兜得住 发行量 × 单张最大优惠。
 * 堵在这里之后，运行时那条「还剩多少预算」的追查根本不需要存在。
 */
public interface CouponService {

    List<CouponVO> list(String entityNo, boolean includeEnded);

    CouponVO detail(String entityNo, String couponNo);

    CouponVO save(String entityNo, CouponSaveCmd cmd, String operatorNo);

    /** 暂停发放 / 恢复 / 结束。<b>不动已经发到用户手上的券</b>——那是他已有的权益 */
    CouponVO setStatus(String entityNo, String couponNo, String status);

    /**
     * 按人群定向发券。
     *
     * <p><b>不静默少发</b>：跳过多少、为什么跳过，都记进批次并返回给界面。
     * <p><b>预算是硬闸门且不部分发放</b>：本批最大敞口超出剩余预算时整批拒绝 ——
     * 部分发放会留下一个谁也说不清的中间态（发到第几个人？没发的怎么办？）。
     */
    CouponIssueVO issue(String entityNo, String couponNo, String segmentNo, String operatorNo);

    List<CouponIssueVO> issues(String entityNo, String couponNo);

    /**
     * 买家自己的券包（C 端）。
     *
     * <p><b>过期的也返回</b>，由端上折叠显示：券包里突然少一张，用户的第一反应是
     * 「平台把我的券吞了」——那是券功能第二大客诉。让它带着「已过期」留在那儿。
     */
    List<MyCouponVO> myCoupons(String userNo);
}
