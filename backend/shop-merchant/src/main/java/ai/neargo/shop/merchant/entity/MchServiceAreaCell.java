package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 多边形范围项的 S2 网格派生行（V396，ADR-034）。
 *
 * <p>一条 {@code level=POLYGON} 的 {@link MchServiceArea} 在保存时被 {@code S2RegionCoverer} 离散成
 * 若干 cell，每个 cell 一行。查询时消费者坐标 → 各级父 cell token → {@code cell_id IN (...)}，
 * 与行政级的 {@code ref_code IN (祖先码)} 是同一种索引形状——几何因此不需要空间 SQL。
 *
 * <p>{@link #boundary} 为真的 cell 只是「可能在内」：命中后还要拿多边形的 geometry 精判（JTS covers，含边界）；
 * 为假的 cell 整个在面内，命中即在内。
 *
 * <p><b>派生数据、物理删除。</b>可由 {@code mch_service_area.geometry} 全量重建；
 * 删多边形项时随之级联删，不留墓碑。
 */
@Getter
@Setter
@TableName("mch_service_area_cell")
public class MchServiceAreaCell extends BaseEntity {

    /** 所属多边形范围项 {@code mch_service_area.area_no} */
    private String areaNo;

    private String entityNo;

    private String storeNo;

    /** 随多边形：INCLUDE / EXCLUDE */
    private String mode;

    /** S2 cell token */
    private String cellId;

    /** S2 级别 */
    private Integer s2Level;

    /** 1=边界 cell（命中后还要精判）；0=内部 cell（命中即在内） */
    private Boolean boundary;
}
