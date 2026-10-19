package ai.neargo.shop.product.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("prd_ent_binding_command")
public class PrdEntBindingCommand extends BaseEntity {
    private String requestKey;
    private String payloadHash;
    private String status;
    private String responseSnapshot;
}
