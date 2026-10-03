package ai.neargo.shop.media;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 按声明去各业务表里读「装图片地址的那一列」。<b>不绑任何实体</b>，表名列名由调用方给。
 *
 * <p><b>关于 {@code ${}}</b>：表名与列名走字符串拼接，MyBatis 无法参数化它们。
 * 这里安全的唯一理由是<b>取值只来自 {@link MediaRefColumn} 的代码常量</b>，
 * 永远不经过请求参数 —— 与 {@code ArchiveMapper} 同一条约定。
 * 改动这个类时必须守住它：一旦让外部字符串流到这两个位置，就是一个 SQL 注入点。
 *
 * <p><b>刻意不过滤 {@code deleted = 0}</b>：软删的商品还能被恢复，
 * 把它的图算作「无人引用」就意味着恢复之后是一堆裂图。
 * 多算一个引用的代价是少删一张，反过来是不可逆的误删 —— 两边不对称。
 * （况且这些表里并非每张都有 {@code deleted} 列，统一加还会 SQL 报错。）
 */
@Mapper
public interface MediaRefMapper {

    @Select("SELECT ${keyCol} AS biz_key, ${col} AS val FROM ${table} WHERE ${col} IS NOT NULL")
    List<Map<String, Object>> scanColumn(@Param("table") String table,
                                         @Param("col") String col,
                                         @Param("keyCol") String keyCol);
}
