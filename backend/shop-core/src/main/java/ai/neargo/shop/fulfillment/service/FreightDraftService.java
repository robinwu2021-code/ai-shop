package ai.neargo.shop.fulfillment.service;

import ai.neargo.shop.fulfillment.dto.FreightTemplateVO;

import java.util.List;

/**
 * 从快递100 报价生成运费模板草稿（TDD-快递100商家寄件 §8 AC17）。**只生成、不保存** —— 运营看过、改过再存。
 */
public interface FreightDraftService {

    /**
     * @param origin          发货地址（至少到城市，如「山西省运城市」）
     * @param carrier         快递公司，微信 delivery_id
     * @param firstWeightGram 首重（克）
     * @param addWeightGram   续重单位（克）
     */
    Draft draft(String origin, String carrier, int firstWeightGram, int addWeightGram);

    /**
     * @param rows 每个省的原始报价，运营据此核对「为什么这个省加收」
     */
    record Draft(String name, int firstWeightGram, long firstFee, int addWeightGram, long addFee,
                 List<FreightTemplateVO.OutOfRangeVO> outOfRange, List<ProvincePrice> rows, int unquoted) {
    }

    /** @param firstFee 首重价（分），查不到为 null；@param addFee 一个续重单位的价（分） */
    record ProvincePrice(String region, Long firstFee, Long addFee) {
    }
}
