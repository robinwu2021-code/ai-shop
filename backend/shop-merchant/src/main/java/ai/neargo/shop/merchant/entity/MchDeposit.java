package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 保证金账户，一商户一行。
 *
 * <p><b>不走微信资金通道，是平台自己记的账</b>：本期只回答「够不够」这一个问题，
 * 实扣实退（理赔）是后续的事。分成两步是有意的——
 * 准入判定不需要等资金通道就位，而资金通道就位要等收付通。
 */
@Getter
@Setter
@TableName("mch_deposit")
public class MchDeposit extends BaseEntity {

    private String merchantNo;
    private Long paidMinor;
    private Long frozenMinor;

    /**
     * 可用余额 = 实缴 − 冻结。
     *
     * <p>判「够不够」要用可用而非实缴：一笔理赔冻结中的钱不能同时用来撑准入，
     * 否则同一笔保证金会被两处重复计数。
     */
    public long availableMinor() {
        return (paidMinor == null ? 0 : paidMinor) - (frozenMinor == null ? 0 : frozenMinor);
    }
}
