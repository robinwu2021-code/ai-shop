package ai.neargo.shop.food.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Existing V1 working configuration; immutable revision facts are stored separately. */
@Getter
@Setter
@TableName("fnb_menu_store")
public class FnbMenuStore extends BaseEntity {
    private String entityNo;
    private String menuNo;
    private String storeNo;
    private String overrideRevisionNo;
}
