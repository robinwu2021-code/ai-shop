package ai.neargo.shop.pay.onboarding.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_onboarding_material（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：进件资料：一字段一行、按版本只增。敏感字段只存密文 id 与掩码；文件存对象存储，库里只存引用与哈希
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_onboarding_material")
public class PayOnboardingMaterial extends PayBaseEntity {

    /** 所属版本 pay_onboarding_version.id */
    private Long payOnboardingVersionId;

    /** 字段码：LEGAL_NAME / LICENSE_NO / ID_NUMBER / BANK_ACCOUNT / CONTACT_MOBILE / LICENSE_IMAGE …（字段字典按渠道维护） */
    private String fieldCode;

    /** TEXT 普通 / SENSITIVE 敏感 / FILE 文件 */
    private String fieldKind;

    /** 仅 TEXT：明文值 */
    private String valueText;

    /** SENSITIVE / FILE：展示用掩码或文件名 */
    private String valueMasked;

    /** 仅 SENSITIVE：密文 sec_secret_blob.id（密钥模块，命名空间 PAY）；查看明文写 sec_access_log */
    private Long secSecretBlobId;

    /** 仅 FILE：对象存储私有桶键 */
    private String fileRef;

    /** 仅 FILE：内容哈希 */
    private String fileSha256;

    /** 已上传渠道换得的媒体号（微信 media_id 等） */
    private String channelMediaId;

    /** 渠道媒体号过期时间 */
    private LocalDateTime channelMediaExpireAt;
}
