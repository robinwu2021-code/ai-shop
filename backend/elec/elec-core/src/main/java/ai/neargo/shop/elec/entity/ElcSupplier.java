package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_supplier")
public class ElcSupplier extends ElcMutableEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    private String supplierNo;

    private String companyName;

    private String kind;

    private String city;

    private String contactName;

    private String contactPhone;

    private String maskCode;

    private String status;

    /** 运营暂停时写的理由。恢复后保留（下次暂停覆盖）—— 「上次为什么停过」是有用的 */
    private String suspendReason;

    private java.time.LocalDateTime suspendedAt;

    private java.time.LocalDateTime notifiedAt;
}
