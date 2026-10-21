package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Existing V1 working configuration; immutable revision facts are stored separately. */
@Getter
@Setter
@TableName("fnb_menu")
public class FnbMenu extends BaseEntity {
    private String menuNo;
    private String entityNo;
    private String name;
    private String menuType;
    private String timezone;
    private LocalDate validFrom;
    private LocalDate validTo;
    private Integer priority;
    private String exclusiveGroup;
    private String status;
    private Long configVersion;
    private String publishedRevisionNo;
}
