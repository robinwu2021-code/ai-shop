package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Restaurant fulfillment projection; financial and inventory documents retain their original owners. */
@Getter
@Setter
@TableName("fnb_course")
public class FnbCourse extends BaseEntity {
    private String entityNo;
    private String courseNo;
    private String checkNo;
    private String name;
    private Integer sequenceNo;
    private String status;
}
