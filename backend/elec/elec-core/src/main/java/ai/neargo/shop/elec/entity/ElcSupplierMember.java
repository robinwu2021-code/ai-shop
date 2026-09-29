package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_supplier_member")
public class ElcSupplierMember extends ElcMutableEntity {

    public static final String ROLE_OWNER = "OWNER";
    public static final String STATUS_ACTIVE = "ACTIVE";

    private String supplierNo;

    private String accountRef;

    private String role;

    private String status;
}
