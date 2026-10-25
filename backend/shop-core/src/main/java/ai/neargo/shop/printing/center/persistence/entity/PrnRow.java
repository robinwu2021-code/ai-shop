package ai.neargo.shop.printing.center.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public abstract class PrnRow {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantNo;
    private String entityNo;
    private Long revision;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
