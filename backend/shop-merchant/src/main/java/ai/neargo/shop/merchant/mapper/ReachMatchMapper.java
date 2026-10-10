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
                  <if test="communityNos != null and communityNos.size() > 0">
                    OR (level = 'COMMUNITY' AND ref_code IN
                    <foreach collection="communityNos" item="c" open="(" separator="," close=")">#{c}</foreach>)
                  </if>
                </trim>
              )
            </script>
            """)
    List<AreaHitRow> areaHits(@Param("ancestors") List<String> ancestors,
                              @Param("communityNos") List<String> communityNos,
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

    /**
     * 门店属性快照的<b>版本探针</b>（ADR-034）：影响判定的五张表的 {@code COUNT(*) + SUM(version)}。
     *
     * <p>为什么是这两个量：新增/物理删改变 {@code COUNT(*)}；{@code updateById} 走 MyBatis-Plus 乐观锁
     * （{@code BaseEntity.version} 带 {@code @Version}）必然递增 {@code version}。于是 insert / update / delete
     * 三种写操作都会改变这个数，<b>且与时间精度无关</b> —— 同一毫秒内的修改也测得出来。
     *
     * <p>这替掉了「在每个写入口手工 evict 缓存」：那条路要覆盖 6 个 service 的二十多个事务方法，
     * 漏一处的症状是「商家改完范围，买家几十秒内看不到」，而且不报错。
     * 探针让正确性由机器保证，新增写路径不需要任何人记得接线。
     */
    @Select("""
            SELECT (SELECT COUNT(*) FROM mch_entity)
                 + (SELECT COALESCE(SUM(version), 0) FROM mch_entity)
                 + (SELECT COUNT(*) FROM mch_store)
                 + (SELECT COALESCE(SUM(version), 0) FROM mch_store)
                 + (SELECT COUNT(*) FROM mch_service_area)
                 + (SELECT COALESCE(SUM(version), 0) FROM mch_service_area)
                 + (SELECT COUNT(*) FROM mch_fulfillment_channel)
                 + (SELECT COALESCE(SUM(version), 0) FROM mch_fulfillment_channel)
                 + (SELECT COUNT(*) FROM mch_channel_area)
                 + (SELECT COALESCE(SUM(version), 0) FROM mch_channel_area)
            """)
    long snapshotVersion();

    /** 行政级/聚落级命中的一行 */
    record AreaHitRow(String storeNo, String areaNo, String mode) {
    }

    /** 网格命中的一行 */
    record CellHitRow(String storeNo, String areaNo, String mode, Boolean boundary) {
    }
}
