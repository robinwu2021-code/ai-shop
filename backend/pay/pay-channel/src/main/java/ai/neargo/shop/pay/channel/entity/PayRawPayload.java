package ai.neargo.shop.pay.channel.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_raw_payload（db/pay V5 · TDD-支付服务-W2渠道层补完 S3）：加密原文 —— 调用请求 / 响应、回调原文、对账单原件。
 * 与记录表分开，便于按期限清理与单独授权。
 *
 * <p>密钥模块（W5）就绪前，{@code cipher_text} 存的是<b>脱敏全文</b>，{@code data_key_cipher} 为
 * {@code MaskedPlaintextSealer#NOT_ENCRYPTED}、{@code sec_master_key_id} 为 0 —— 读的人据此知道这一行没有加密、也不是完整原文。
 */
@Getter
@Setter
@TableName("pay_raw_payload")
public class PayRawPayload extends PayBaseEntity {

    /** CALL_REQUEST / CALL_RESPONSE / CALLBACK / STATEMENT */
    private String kind;

    /** DB / OBJECT（大文件如对账单走对象存储） */
    private String storage;

    /** storage=DB：AES-GCM 密文（W5 前为脱敏全文） */
    private String cipherText;

    /** storage=OBJECT：对象存储键（对象本身加密） */
    private String objectKey;

    /** 被主密钥包裹的数据密钥（经密钥模块 API 加解密） */
    private String dataKeyCipher;

    /** 包裹所用主密钥 sec_master_key.id */
    private Long secMasterKeyId;

    /** 明文哈希（举证用） */
    private String sha256;

    /** 明文字节数 */
    private Integer sizeBytes;

    /** 保留至（合规期限，Q4） */
    private LocalDate retainUntil;
}
