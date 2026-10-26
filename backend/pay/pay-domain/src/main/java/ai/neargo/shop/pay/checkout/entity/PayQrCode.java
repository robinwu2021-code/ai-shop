package ai.neargo.shop.pay.checkout.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_qr_code（db/pay V11 · TDD-支付服务-I5接入层 S1）：静态码（门店 / 桌台码），顾客输金额建单。码值随机不可枚举。
 */
@Getter
@Setter
@TableName("pay_qr_code")
public class PayQrCode extends PayBaseEntity {

    /** 码值（随机，出现在 URL 里） */
    private String qrCode;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** STORE / TABLE */
    private String kind;

    /** 桌台（餐饮） */
    private String tableRef;

    /** 币种 */
    private String currency;

    /** 单笔上限 */
    private Long singleLimitMinor;

    /** 单码日累计上限 */
    private Long dailyLimitMinor;

    /** ACTIVE / DISABLED */
    private String status;

    /** 谁建的码：BIZ 商家 / OPS 运营 */
    private String createdByType;
}
