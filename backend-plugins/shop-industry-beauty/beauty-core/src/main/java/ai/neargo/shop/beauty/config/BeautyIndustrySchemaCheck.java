package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.common.config.IndustryTableMapper;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;

/**
 * 启动时在行业句柄上核对美业用到的行业表（TDD-美业域迁移新库 B-3）：行业句柄下每个 Mapper 的实体都登记在
 * {@link BeautyIndustryTables}，并且实体的每一列在行业句柄所指的库里都能查。主库的私有 Flyway 校验不再管这些表。
 *
 * <p>列名按实体字段推出（{@code @TableField} 的列名或驼峰转下划线），不经 MyBatis-Plus 的全局表信息缓存。
 */
public final class BeautyIndustrySchemaCheck {
    /** 可选扩展（迁移 V10）：装没装、坏没坏由 {@code ServiceExecutionSchemaVerifier} 自己判，启动不要求它在。 */
    static final java.util.Set<String> OPTIONAL =
            java.util.Set.of("bty_service_execution_fence", "bty_service_execution_stock_intent");

    private BeautyIndustrySchemaCheck() {}

    /** 行业句柄下的实体，按表名。 */
    static Map<String, Class<?>> industryEntities() {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        for (Class<?> mapper : BeautyMappers.class.getDeclaredClasses()) {
            if (!IndustryTableMapper.class.isAssignableFrom(mapper)) continue;
            for (var generic : mapper.getGenericInterfaces())
                if (generic instanceof ParameterizedType type && type.getRawType() == IndustryTableMapper.class) {
                    Class<?> entity = (Class<?>) type.getActualTypeArguments()[0];
                    result.put(entity.getAnnotation(TableName.class).value(), entity);
                }
        }
        return result;
    }

    static List<String> columns(Class<?> entity) {
        List<String> columns = new ArrayList<>();
        for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                TableField mapping = field.getAnnotation(TableField.class);
                if (mapping != null && !mapping.exist()) continue;
                columns.add(mapping != null && !mapping.value().isEmpty() ? mapping.value()
                        : field.getName().replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return columns;
    }

    public static void verify(DataSource industry) {
        var entities = industryEntities();
        if (!new TreeSet<>(entities.keySet()).equals(new TreeSet<>(BeautyIndustryTables.NAMES)))
            throw new IllegalStateException("Beauty industry mappers differ from BeautyIndustryTables: "
                    + new TreeSet<>(entities.keySet()));
        List<String> failures = new ArrayList<>();
        try (Connection connection = industry.getConnection(); var statement = connection.createStatement()) {
            for (var entry : entities.entrySet()) {
                if (OPTIONAL.contains(entry.getKey())) continue;
                try {
                    statement.executeQuery("SELECT " + String.join(",", columns(entry.getValue())) + " FROM "
                            + entry.getKey() + " WHERE 1=0").close();
                } catch (SQLException missing) {
                    failures.add(entry.getKey() + ": " + missing.getMessage());
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot inspect the beauty industry datasource", ex);
        }
        if (!failures.isEmpty())
            throw new IllegalStateException("Beauty industry tables missing on the industry datasource "
                    + "(bind shop.modules.industry to n_industry): " + String.join("; ", failures));
    }
}
