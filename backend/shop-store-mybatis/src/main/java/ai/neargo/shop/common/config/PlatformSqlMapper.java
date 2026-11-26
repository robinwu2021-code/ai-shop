package ai.neargo.shop.common.config;

/**
 * 标记：不绑单一实体、只写注解 SQL 的 Mapper，且查的全是平台基础库的表。
 * 与 {@link PlatformTableMapper} 同一个用意，给不继承 {@code BaseMapper} 的那几个用。
 */
public interface PlatformSqlMapper {
}
