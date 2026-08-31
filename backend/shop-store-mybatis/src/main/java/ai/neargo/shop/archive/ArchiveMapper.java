package ai.neargo.shop.archive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 归档标记的读写。<b>不绑任何一个域的实体</b>，表名与主键列由调用方给。
 *
 * <p>为什么这么写，而不是各域拿自己的 Mapper 来更新：
 * <ul>
 *   <li>{@code mch_entity} 在 shop-merchant，而这个服务在 shop-core ——
 *       直接引它的 Mapper 会被 ArchUnit 的模块依赖规则拦下，
 *       而为「盖一个时间戳」引一整套 SPI port 是不成比例的</li>
 *   <li>归档是<b>横切关注点</b>：四个域的这段逻辑逐字相同，
 *       它本来就不该长在任何一个域里面</li>
 * </ul>
 *
 * <p><b>关于 {@code ${}}</b>：表名与列名走字符串拼接，MyBatis 无法参数化它们。
 * 这里安全的唯一理由是<b>取值只来自 {@code ArchiveService.Kind} 枚举的常量</b>，
 * 永远不经过请求参数。改动这个类时必须守住这条 ——
 * 一旦让外部字符串流到这两个位置，就是一个 SQL 注入点。
 */
@Mapper
public interface ArchiveMapper {

    /** 盖上归档时间。返回受影响行数，0 = 该业务号不存在 */
    @Update("UPDATE ${table} SET archived_at = #{at}, version = version + 1 "
            + "WHERE ${keyCol} = #{bizNo} AND deleted = 0")
    int markArchived(@Param("table") String table, @Param("keyCol") String keyCol,
                     @Param("bizNo") String bizNo, @Param("at") java.time.LocalDateTime at);

    /** 清空归档时间（恢复）。 */
    @Update("UPDATE ${table} SET archived_at = NULL, version = version + 1 "
            + "WHERE ${keyCol} = #{bizNo} AND deleted = 0")
    int clearArchived(@Param("table") String table, @Param("keyCol") String keyCol,
                      @Param("bizNo") String bizNo);

    /** 存在性检查 —— 归档一个不存在的东西要报 404，而不是静默成功 */
    @Select("SELECT COUNT(*) FROM ${table} WHERE ${keyCol} = #{bizNo} AND deleted = 0")
    int exists(@Param("table") String table, @Param("keyCol") String keyCol,
               @Param("bizNo") String bizNo);
}
