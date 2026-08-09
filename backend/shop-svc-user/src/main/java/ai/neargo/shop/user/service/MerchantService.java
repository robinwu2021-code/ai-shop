package ai.neargo.shop.user.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.user.dto.MerchantVO;

/** 商家展示（[API 清单 §2.11]）。游客可访问。 */
public interface MerchantService {

    PageData<MerchantVO> search(String keyword, String communityNo, long page, long size);

    MerchantVO detail(String merchantNo);

    /**
     * 推荐门店（运营位）。用途是<b>新店冷启动</b> ——
     * 一家刚入驻的店没有订单、没有评分，在任何按销量/评分排的列表里都永远垫底，
     * 靠自然流量起不来。所以这个位子<b>刻意不看历史成绩</b>。
     *
     * <p>一期无运营后台，用「本社区可达 + 入驻晚」兜底，正好对上这个用途。
     */
    java.util.List<MerchantVO> promoted(String communityNo, Integer size);

    /** 评分与依据（C-MC-05）。评分不写明依据，用户只会觉得是平台随便给的。 */
    ai.neargo.shop.user.dto.MerchantScoreVO score(String merchantNo);

    /** 我买过的商家（C-MC-01）。数据来自 trade 域，经 PurchaseHistoryPort。 */
    java.util.List<ai.neargo.shop.user.dto.VisitedMerchantVO> visited();

    /**
     * 商家账号视图（B 端 {@code /biz/merchant/profile}）。
     *
     * <p>与 {@link #detail(String)} 分开是刻意的：detail 是给买家看的店（评分、销量），
     * 这里是给店主看的自己（主体、状态）。<b>不做 ACTIVE 过滤</b> ——
     * 被封禁的店主更需要看到自己被封了。
     *
     * @return 商家不存在时返回 null（还没通过审核的申请人就是这种情况）
     */
    ai.neargo.shop.user.dto.MerchantAccountVO account(String merchantNo);
}
