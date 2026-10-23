package ai.neargo.shop.print.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("prn_credential_version")
public class PrnCredentialVersion extends PrnRow {
    @TableField(value = "credential_ref", updateStrategy = FieldStrategy.ALWAYS)
    private String credentialRef;

    @TableField(value = "printer_no", updateStrategy = FieldStrategy.ALWAYS)
    private String printerNo;

    @TableField(value = "version_number", updateStrategy = FieldStrategy.ALWAYS)
    private Integer versionNumber;

    @TableField(value = "credential_ciphertext", updateStrategy = FieldStrategy.ALWAYS)
    private String credentialCiphertext;

    @TableField(value = "encryption_key_version", updateStrategy = FieldStrategy.ALWAYS)
    private String encryptionKeyVersion;

    @TableField(value = "status", updateStrategy = FieldStrategy.ALWAYS)
    private String status;

    @TableField(value = "retired_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime retiredAt;

    @TableField(value = "created_by", updateStrategy = FieldStrategy.ALWAYS)
    private String createdBy;
}
