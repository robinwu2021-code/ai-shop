package ai.neargo.shop.merchant.service;

import java.util.List;

/**
 * 类目经营授权。
 *
 * <p>没有它，V4 给类目挂上的 {@code required_code} 就是**一个只会拒绝的门槛** ——
 * 有门槛没有发证机关，挂了门槛的类目永远拒绝所有人，而商家只看到「你还没有资质授权」，
 * 去哪申请没人知道。一个只会拒绝的校验比没有校验更糟：它看起来在工作。
 */
public interface MerchantAuthCodeService {

    /** 全部可用授权码（运营授权时的选项）。 */
    List<AuthCodeVO> listCodes();

    /**
     * 设置某商家的经营授权范围。**全量覆盖**，不是增量。
     *
     * @param reason 改动原因，**必填** —— 它决定商家能上架什么，出事要能查到依据
     */
    SetResult setCodes(String merchantNo, List<String> codes, String reason);

    /**
     * @param codes    改完之后这家主体持有的码（全量）
     * @param revoked  这次撤掉的码
     * @param affected 因这次撤码而<b>下次上架会被拒</b>的在架商品数。
     *                 运营按下确认之前要看得见它 —— 看不见的话，一次「顺手收紧」
     *                 会在几天后变成商家的「我的货怎么上不去了」，而两件事没人会联系起来
     */
    record SetResult(List<String> codes, List<String> revoked, long affected) {
    }

    /**
     * @param requiredQualification 需要的资质证件名。空 = 无证件要求
     */
    record AuthCodeVO(String code, String name, String requiredQualification) {
    }
}
