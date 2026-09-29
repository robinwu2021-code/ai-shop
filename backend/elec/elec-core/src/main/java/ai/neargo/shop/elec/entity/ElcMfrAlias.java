package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_mfr_alias")
public class ElcMfrAlias extends ElcEntity {

    public static final String SOURCE_SEED = "SEED";
    public static final String SOURCE_OPS = "OPS";

    private String aliasNorm;

    private String mfrCode;

    private String source;
}
