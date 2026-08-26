package ai.neargo.shop.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 库位：存货的地方，门店与仓与在途都在这里 */
@Getter
@Setter
@TableName("inv_location")
public class InvLocation extends InvMutableEntity {

    private String locationId;

    private String ownerId;

    private String name;

    /** STORE 门店 / WAREHOUSE 仓 / TRANSIT 在途 / VIRTUAL 虚拟（报废区、样品、借出） */
    private String kind;

    /** 嵌入平台时 = mch_store.store_no；仓可以没有对应门店 */
    private String externalRef;

    /** 这个点从哪里发货；空 = 发自己的。**不允许链式**（被指向者自己必须为空） */
    private String sourceLocationId;

    /** 一业主恰好一个，删不掉。存量「主体级库存」迁到它名下 */
    private Integer isDefault;

    /** ACTIVE / DISABLED */
    private String status;

}
