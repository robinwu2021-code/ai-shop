package ai.neargo.shop.pay.dto;

import java.time.LocalDateTime;

/**
 * 费率规则的一个版本，运营端读到的形状。
 *
 * <p><b>为什么不直接返回 {@code StlFeeRule}。</b>两个原因，第二个才是要紧的：
 *
 * <p>一、entity 上有 {@code @TableName}，把它放进服务接口的签名里，
 * 接口层就绑死了持久化框架 —— 而支付域独立部署那一版要求
 * 接口层不带 MyBatis（判据是「进没进 classpath」，不是「用没用到」）。
 *
 * <p>二、entity 继承 {@code BaseEntity}，于是 {@code tenantNo}、{@code deleted}、
 * {@code version} 一直随响应发给了运营端。它们不是契约的一部分，
 * 却因为「顺手返回 entity」而成了事实上的契约 —— <b>下次给 BaseEntity 加一列，
 * 就又多一列悄悄发出去，没有任何一处会报错。</b>
 *
 * <p>字段与 ops-web 的 {@code FeeRuleVersion} 一一对应，故意保持同名：
 * 换成 VO 之后前端拿到的 JSON 不少一个字段，只少了上面那三个本就不该给的。
 */
public record FeeRuleVO(
        String ruleNo,
        String businessMode,
        String trafficSource,
        Integer rateBp,
        Long effectiveFrom,
        Integer enabled,
        String remark,
        LocalDateTime createdAt,
        String createdBy) {
}
