package ai.neargo.shop.media;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Platform GC must retain food references even while a standalone food host owns their writes. */
@Component
public class FoodPresentationMediaRefs implements MediaRefSource {
    private final DataSource dataSource;

    public FoodPresentationMediaRefs(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public List<MediaRefColumn> columns() {
        var tables = new HashSet<String>();
        try (var connection = dataSource.getConnection();
                var rows = connection.getMetaData().getTables(connection.getCatalog(), connection.getSchema(),
                        "%", new String[]{"TABLE"})) {
            // Enumerate and compare exact names: '_' in a table-pattern argument is a wildcard.
            while (rows.next()) tables.add(rows.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot determine restaurant media reference tables", failure);
        }
        var result = new ArrayList<MediaRefColumn>();
        if (tables.contains("fnb_dish")) {
            result.add(MediaRefColumn.of("fnb_dish", "presentation", "菜品 · 展示图片", "goods_no"));
        }
        if (tables.contains("fnb_config_revision")) {
            result.add(MediaRefColumn.of("fnb_config_revision", "definition_snapshot", "餐饮配置 · 历史图片", "revision_no"));
        }
        return List.copyOf(result);
    }
}
