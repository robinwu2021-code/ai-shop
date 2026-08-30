package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家欠款账户：<b>退款追不回来时先记在账上，从后续货款里扣</b>。
 *
 * <p><b>与保证金（{@link MchDeposit}）方向相反，所以不合表。</b>
 * 保证金是商家的钱（平台代管，将来要退还），欠款是商家欠平台的钱。
 * 合在一张表上用正负号表达的话，「应退还多少保证金」这个问题就永远算不清了 ——
 * 而那是退店结账时必须给出的数。
 *
 * <p>它是 Z4 追偿三层里的<b>第二层</b>：保证金不足 → 记欠款 → 仍不足则停止放款。
 * 见 {@code docs/technical/design/账期与对账放款-方案.md} §三。
 */
@Getter
@Setter
@TableName("mch_debt")
public class MchDebt extends BaseEntity {

    private String entityNo;

    /** 当前欠款（分）。<b>方向单一，恒 >= 0</b> —— 出现负数说明有 bug，不是「预付」 */
    private Long balanceMinor;

    /** 累计产生（分），只增。与 balance 分开才答得出「这家历史上一共欠过多少」 */
    private Long totalIncurredMinor;

    /** 累计已偿（分），只增 */
    private Long totalRepaidMinor;

    private Long lastIncurredAt;
}
