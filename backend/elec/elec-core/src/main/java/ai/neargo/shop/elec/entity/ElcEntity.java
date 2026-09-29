package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 只追加的表的基类：没有 updated_at / updated_by。
 * <b>没有这两列，就没有「改一行」这个动作</b> —— 继承哪个基类就是这张表的规矩。
 */
@Getter
@Setter
public abstract class ElcEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private String createdBy;
}
