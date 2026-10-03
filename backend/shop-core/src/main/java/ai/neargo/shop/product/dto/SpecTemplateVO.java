package ai.neargo.shop.product.dto;

import java.util.List;

/**
 * 规格模板（对齐 b-app {@code SpecTemplate}）。
 *
 * @param scope PLATFORM（平台统一维护）/ MERCHANT（商家自存）。
 *              商家只能改自己的 —— 平台模板是跨店可比的基础，
 *              一家店改了名字，别家的同名规格就对不上了
 */
public record SpecTemplateVO(String templateNo, String scope, String categoryType,
                             /**
                              * 类目级模板的归属类目；<b>空 = 品类兜底</b>。
                              *
                              * <p>端上靠它区分两层：类目级排在前面并标出来，
                              * 兜底那批排在后面 —— 不下发的话两批混在一起，
                              * 商家分不出哪个是「专门给这一类的」。
                              */
                             String categoryNo,
                             String name, List<Option> options, String merchantNo,
                             /**
                              * <b>主维度</b>：商家选完类目该自动建出来的就是这一组。
                              *
                              * <p>不下发的话端上只能靠「数组第一个」去猜 —— 后端确实是那么排的
                              * （主维度优先、其余按 sort），但那是一个不该被依赖的巧合：
                              * 排序规则一改，端上跟着错，而症状是「自动建出来的是包装不是重量」，
                              * 没有任何一处会报错。判据在哪就从哪下发。
                              */
                             boolean primary) {

    /** @param code 来自平台模板的有值，手输的没有。一期只存不用，二期做规格聚合要靠它 */
    public record Option(String code, String label) {
    }
}
