package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 支付域自己的设置（V285）。
 *
 * <p>四个 key 从来就是资金域的知识 —— 端积分策略、积分配置、个税规则、开票抬头 ——
 * 只是当初图省事存进了 {@code sys_setting}（平台通用设置），
 * 于是支付域要经 {@code SettingPort} 反向问主应用。
 * 搬过来之后那条依赖直接消失，<b>一行网络调用都不用加</b>。
 */
@Getter
@Setter
@TableName("pay_setting")
public class PaySetting extends BaseEntity {

    private String settingKey;

    /** JSON 文本。结构由各自的 VO 定义 */
    private String settingValue;

    /** 这条设置改动的影响面，写给下一个改它的人 */
    private String remark;
}
