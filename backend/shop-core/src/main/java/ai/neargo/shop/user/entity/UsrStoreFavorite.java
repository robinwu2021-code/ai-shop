package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 收藏的店（C-ST-07）。 */
@Getter
@Setter
@TableName("usr_store_favorite")
public class UsrStoreFavorite extends BaseEntity {

    private String userNo;
    private String entityNo;
}
