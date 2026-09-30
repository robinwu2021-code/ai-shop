package ai.neargo.shop.elec.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 表头别名：哪种写法的表头是哪个字段。{@code supplierNo} 为空串是全局，否则只对那一家生效 ——
 * 供应商确认过的写法只记成他自己的，<b>一家写错不带偏全平台</b>，要全局得运营提升。
 */
@Getter
@Setter
@TableName("elc_header_alias")
public class ElcHeaderAlias extends ElcMutableEntity {

    /** 全局别名的 supplier_no。不用 null：唯一键不管 null */
    public static final String GLOBAL = "";

    public static final String SOURCE_SEED = "SEED";
    public static final String SOURCE_OPS = "OPS";
    public static final String SOURCE_LEARNED = "LEARNED";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";

    private String supplierNo;

    private String aliasNorm;

    private String aliasRaw;

    private String field;

    private String source;

    private String status;
}
