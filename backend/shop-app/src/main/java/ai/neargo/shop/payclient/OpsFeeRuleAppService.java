package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.dto.FeeRuleVO;
import java.util.List;
import java.util.Map;

/**
 * 平台端 · 费率维护的 app service。
 *
 * <p>调费率<b>一律插新行，不原地改</b> —— 原地改只能回答「现在是多少」，
 * 而真正会被问到的是「上个月那批单当时按什么费率算的」。
 * 所以这里没有 update，只有 add。
 */
public interface OpsFeeRuleAppService {

    /** 全部版本，含历史。运营要能看见「什么时候调过、调成什么、为什么调」 */
    List<FeeRuleVO> rules();

    /**
     * 某时刻实际生效的四格费率。
     *
     * @param at 为空取此刻。单独开这个而不是让端上从版本列表里推：
     *           「哪一版此刻在生效」牵涉停用回退的语义，端上推错了不会报错，只会显示错
     */
    Map<String, Integer> effectiveRates(Long at);

    /**
     * 加一个费率版本。
     *
     * @param effectiveFrom 为空 = 立即生效，填未来时刻 = 预约生效
     * @param rateBp        为空按 0 算。包装类型是为了让「没填」和「填了 0」能分开
     */
    FeeRuleVO add(String businessMode, String trafficSource, Integer rateBp,
                  Long effectiveFrom, String remark);
}
