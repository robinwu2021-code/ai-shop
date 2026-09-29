package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_part")
public class ElcPart extends ElcMutableEntity {

    public static final String SOURCE_UPLOAD = "UPLOAD";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_MERGED = "MERGED";

    private String partNo;

    private String mpn;

    private String mpnNorm;

    private String mfrCode;

    private String mfrNameRaw;

    /** 列名是 SQL 保留字边缘（package），实体里叫 pkg，列名显式写 */
    @com.baomidou.mybatisplus.annotation.TableField("package")
    private String pkg;

    private String description;

    private String source;

    private String status;

    private String mergedInto;
}
