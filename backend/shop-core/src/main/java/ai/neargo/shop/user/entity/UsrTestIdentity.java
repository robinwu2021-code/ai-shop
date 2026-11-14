package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 测试身份白名单（TDD-测试身份白名单登录）。统一码 / 统一密码不在这里，只在服务器配置 */
@Getter
@Setter
@TableName("usr_test_identity")
public class UsrTestIdentity extends BaseEntity {

    /** PHONE / EMAIL */
    private String identityType;

    /** 规范化后的值：手机号去空格与连字符，邮箱小写 */
    private String identityValue;

    private Boolean enabled;

    private String remark;
}
