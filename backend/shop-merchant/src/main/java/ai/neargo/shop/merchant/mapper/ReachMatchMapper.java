package ai.neargo.shop.merchant.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 可见范围的命中查询（ADR-034）：两条索引点查，替掉「每请求把全平台范围整表拉进内存逐店判」。
 *
 * <p>核心手法是把「消费者区划码以范围码为前缀」改写成「范围码 ∈ 消费者祖先码集合」——
 * 前缀匹配用不上索引，{@code IN} 能走现成的 {@code idx_service_area_ref(level, ref_code)}；
 * 多边形同理，经 S2 网格变成 {@code cell_id IN (各级 token)}，命中 {@code idx_sac_cell}。
 * 于是行政五级、聚落、多边形、排除<b>共用一种索引形状</b>，MySQL / H2 完全一致，不需要任何空间 SQL。
 *
 * <p><b>只查命中，不做判定</b>：排除先算、路类型闸、fail-closed 全在 {@code ReachRule} 一处。
 * 「不限」也不在这里 —— 它是门店属性，走快照。
 *
 * <p>纳入项要 {@code status='ACTIVE'}；排除项<b>不看 status</b> —— 缩小自己的范围不需要审核，待审的排除也立即生效。
 * 祖先集或 token 为空时用 {@code 1=0} 短路：空 {@code IN ()} 在两种库上都是语法错。
 */
@Mapper
public interface ReachMatchMapper {

    /** 行政级（按祖先码）与聚落级（按小区号/父聚落号）的命中行 */
    @Select("""
            <script>
            SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode
            FROM mch_service_area
            WHERE deleted = 0
              AND store_no IS NOT NULL
              AND ((mode = 'INCLUDE' AND status = 'ACTIVE') OR mode = 'EXCLUDE')
              <if test="storeNo != null"> AND store_no = #{storeNo}</if>
              AND (
                <trim prefixOverrides="OR ">
                  <choose>
                    <when test="ancestors != null and ancestors.size() > 0">
                      (level IN ('PROVINCE','CITY','DISTRICT','STREET','VILLAGE') AND ref_code IN
                      <foreach collection="ancestors" item="a" open="(" separator="," close=")">#{a}</foreach>)
                    </when>
                    <otherwise>(1 = 0)</otherwise>
                  </choose>
                  <if test="communityNo != null">
                    OR (level = 'COMMUNITY' AND ref_code = #{communityNo})
                  </if>
                  <if test="parentNo != null">
                    OR (level = 'COMMUNITY' AND ref_code = #{parentNo})
                  </if>
                </trim>
              )
            </script>
            """)
    List<AreaHitRow> areaHits(@Param("ancestors") List<String> ancestors,
                              @Param("communityNo") String communityNo,
                              @Param("parentNo") String parentNo,
                              @Param("storeNo") String storeNo);

    /** 多边形的网格命中行。{@code boundary=1} 的只是「可能在内」，判定前要用几何精判 */
    @Select("""
            <script>
            SELECT store_no AS storeNo, area_no AS areaNo, mode AS mode, boundary AS boundary
            FROM mch_service_area_cell
            WHERE
              <choose>
                <when test="tokens != null and tokens.size() > 0">
                  cell_id IN <foreach collection="tokens" item="t" open="(" separator="," close=")">#{t}</foreach>
                </when>
                <otherwise>1 = 0</otherwise>
              </choose>
              <if test="storeNo != null"> AND store_no = #{storeNo}</if>
            </script>
            """)
    List<CellHitRow> cellHits(@Param("tokens") List<String> tokens, @Param("storeNo") String storeNo);

    /** 行政级/聚落级命中的一行 */
    record AreaHitRow(String storeNo, String areaNo, String mode) {
    }

    /** 网格命中的一行 */
    record CellHitRow(String storeNo, String areaNo, String mode, Boolean boundary) {
    }
}
