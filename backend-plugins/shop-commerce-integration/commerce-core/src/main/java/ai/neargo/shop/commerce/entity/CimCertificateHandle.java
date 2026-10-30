package ai.neargo.shop.commerce.entity;

import ai.neargo.shop.common.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Original controlled certificate reference and immutable provider/source evidence, not a local
 * asset balance.
 */
@Getter
@Setter
@TableName("cim_certificate_handle")
public class CimCertificateHandle extends BaseEntity {
    private String handleNo;
    private String entityNo;
    private String storeNo;
    private String connectionNo;
    private String accountNo;
    private String profileNo;
    private Long encryptedBlobId;
    private String status;
}
