import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 只读导出菜单/角色/授权快照，供 verify_menu_migration.py compare 比对。
 * 166 上没有 mysql 客户端与 python 驱动，所以用运行 JAR 自带的 mysql-connector-j 走 JDBC。
 *
 * Usage: MenuSnapshot <client.properties> <out.json>
 * client.properties: url / user / password（文件放在 0700 私有目录，用后删除）。
 *
 * SQL 与 verify_menu_migration.py 的 snapshot() 逐字相同；NULL 写成 "NULL"、数字写成文本，
 * 与 mysql -B 的输出一致 —— 两种快照可以直接比对。连接设为只读。
 */
public class MenuSnapshot {
  static final Map<String, String> QUERIES = new LinkedHashMap<>();
  static {
    QUERIES.put("functions", "SELECT function_code,name,sort,enabled FROM sys_function WHERE end_code='OPS' ORDER BY function_code");
    QUERIES.put("points", "SELECT point_code,function_code,name,IFNULL(group_name,''),IFNULL(href,''),point_type,"
        + "IFNULL(ui_perm_code,''),IFNULL(perm_code,''),backend_status,ui_ready,IFNULL(gated_by,''),sort "
        + "FROM sys_function_point ORDER BY point_code");
    QUERIES.put("roles", "SELECT role_code,name,end_code,builtin,wildcard FROM sys_role ORDER BY role_code");
    QUERIES.put("grants", "SELECT role_code,point_code,end_code,IFNULL(entity_no,'<NULL>'),tenant_no,deleted FROM sys_role_point "
        + "ORDER BY role_code,point_code,entity_no");
    QUERIES.put("members", "SELECT * FROM sys_role_member ORDER BY 1");
    QUERIES.put("flyway", "SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) throw new IllegalArgumentException("usage: <client.properties> <out.json>");
    var p = new Properties();
    try (var in = Files.newInputStream(Path.of(args[0]))) { p.load(in); }
    var json = new StringBuilder("{");
    try (var c = DriverManager.getConnection(p.getProperty("url"), p.getProperty("user"), p.getProperty("password"))) {
      c.setReadOnly(true);
      boolean firstKey = true;
      for (var e : QUERIES.entrySet()) {
        json.append(firstKey ? "" : ", ").append(str(e.getKey())).append(": [");
        firstKey = false;
        try (var st = c.createStatement(); var rs = st.executeQuery(e.getValue())) {
          int n = rs.getMetaData().getColumnCount();
          boolean firstRow = true;
          while (rs.next()) {
            json.append(firstRow ? "[" : ", [");
            firstRow = false;
            for (int i = 1; i <= n; i++) {
              String v = rs.getString(i);
              json.append(i > 1 ? ", " : "").append(str(v == null ? "NULL" : v));
            }
            json.append("]");
          }
        }
        json.append("]");
      }
    }
    Files.writeString(Path.of(args[1]), json.append("}").toString(), StandardCharsets.UTF_8);
  }

  static String str(String s) {
    var b = new StringBuilder("\"");
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
      }
    }
    return b.append('"').toString();
  }
}
