package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 测试号固定验证码白名单（TDD-测试号固定验证码）。
 *
 * <p>命中的号请求验证码时<b>不发真实短信</b>，码恒为 {@link #code}。
 * 存在的唯一理由是苹果审核：审核员在美国，收不到中国短信，而登录是手机号 + 验证码。
 *
 * <p><b>每一行都是一把钥匙</b>，所以护栏在 {@code OtpTestPhoneService} 上而不是这张表上 ——
 * 最关键那条是「拒绝录入已存在账号的手机号」。实体本身只是那几列。
 */
@Getter
@Setter
@TableName("usr_otp_test_phone")
public class UsrOtpTestPhone extends BaseEntity {

    private String phone;

    /** 固定验证码。至少 6 位 —— 与 {@code PWD_MIN_LEN} 同档，挡住「1234」 */
    private String code;

    /** 停用**即时生效**：写口改完当场让缓存失效，不等 TTL、不等重启 */
    private Boolean enabled;

    /** 这一条为什么存在。不写清楚，半年后没人敢删也没人敢留 */
    private String remark;
}
