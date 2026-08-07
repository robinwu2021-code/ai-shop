package ai.neargo.shop.user.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.user.dto.MerchantVO;

/** 商家展示（[API 清单 §2.11]）。游客可访问。 */
public interface MerchantService {

    PageData<MerchantVO> search(String keyword, long page, long size);

    MerchantVO detail(String merchantNo);

    /** 评分与依据（C-MC-05）。评分不写明依据，用户只会觉得是平台随便给的。 */
    ai.neargo.shop.user.dto.MerchantScoreVO score(String merchantNo);

    /** 我买过的商家（C-MC-01）。数据来自 trade 域，经 PurchaseHistoryPort。 */
    java.util.List<ai.neargo.shop.user.dto.VisitedMerchantVO> visited();
}
