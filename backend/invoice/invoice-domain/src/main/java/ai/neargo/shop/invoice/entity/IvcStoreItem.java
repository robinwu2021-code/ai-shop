package ai.neargo.shop.invoice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 门店常用品项（V15）：品名 + 课税别，POS 输入金额时快选。订单行建单时复制，品项改了不影响已建的单。 */
@Getter
@Setter
@TableName("ivc_store_item")
public class IvcStoreItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String storeItemNo;
    private String tenantNo;
    private String entityNo;
    private String storeNo;
    private String name;
    /** TAXABLE / ZERO_RATED / EXEMPT */
    private String taxType;
    private Integer sort;
    private Boolean enabled;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
