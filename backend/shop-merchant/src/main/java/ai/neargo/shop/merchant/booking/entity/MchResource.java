package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 人员或物理资源的稳定主档；不按门店复制同一人员的容量。 */
@Getter
@Setter
@TableName("mch_resource")
public class MchResource extends BaseEntity {
    /** 可信经营主体。 */
    private String entityNo;
    /** 规范资源号，停用后保留。 */
    private String resourceNo;
    /** 物理资源门店，STAFF为空。 */
    private String storeNo;
    /** STAFF或ROOM或SEAT或DEVICE。 */
    private String resourceType;
    /** STAFF引用的主体内业务人员，物理资源为空。 */
    private String personnelNo;
    /** 资源展示名。 */
    private String name;
    /** 并发上限；STAFF固定为1，不等于剩余容量。 */
    private Integer capacity;
    /** ACTIVE或SUSPENDED。 */
    private String status;
    /** 后续独占式开放占用仲裁使用的阻断指针。 */
    private String openOccupationNo;
}
