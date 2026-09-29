package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("elc_manufacturer")
public class ElcManufacturer extends ElcMutableEntity {

    /** 厂牌不明时的占位码。它也是一行真数据（种子里有），料号的 mfr_code 永不为空 */
    public static final String UNKNOWN = "UNKNOWN";

    private String mfrCode;

    private String nameEn;

    private String nameCn;

    private String status;

    private String mergedInto;
}
