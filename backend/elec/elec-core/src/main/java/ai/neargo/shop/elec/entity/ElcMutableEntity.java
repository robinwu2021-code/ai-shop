package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 可改的表的基类。 */
@Getter
@Setter
public abstract class ElcMutableEntity extends ElcEntity {

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    private String updatedBy;
}
