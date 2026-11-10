package ai.neargo.shop.beauty.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Complete V15 physical contract; exact V1–V15 history. No verifier recursion. */
public final class TaskStartSchemaExtension {
    public static final String SCRIPT="V15__beauty_task_start.sql";
    public enum Installation { ABSENT, INSTALLED, CORRUPT }
    public record Postcondition(Installation installation,JsonNode descriptor) {}
    private TaskStartSchemaExtension() {}
    public static Postcondition inspect(DataSource source) {
        Connection connection=null;
        try {
            connection=DataSourceUtils.getConnection(source);
            String schema=connection.getCatalog(); require(schema!=null && schema.matches("[a-zA-Z0-9_]+"));
            var columns=query(connection,"SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLLATION_NAME,COLUMN_DEFAULT,EXTRA"
                    +" FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME='bty_service_submission'"
                    +" AND COLUMN_NAME IN ('execution_no','action_observation_json') ORDER BY ORDINAL_POSITION",schema);
            var triggers=query(connection,"SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE,ACTION_TIMING,ACTION_STATEMENT"
                    +" FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? AND TRIGGER_NAME IN"
                    +" ('trg_bty_task_start_immutable','trg_bty_task_start_no_delete')",schema);
            var checks=query(connection,"SELECT tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE,tc.ENFORCED"
                    +" FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc"
                    +" ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME"
                    +" WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME='bty_service_submission'"
                    +" AND tc.CONSTRAINT_NAME IN ('ck_bty_task_start','ck_bty_task_start_columns')",schema);
            var indexes=query(connection,"SELECT INDEX_NAME,NON_UNIQUE,COLUMN_NAME,SEQ_IN_INDEX,INDEX_TYPE,IS_VISIBLE,SUB_PART,COLLATION"
                    +" FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME='bty_service_submission'"
                    +" AND INDEX_NAME='uk_bty_submission_execution' ORDER BY SEQ_IN_INDEX",schema);
            boolean history=!query(connection,"SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=?"
                    +" AND TABLE_NAME='bty_flyway_history'",schema).isEmpty();
            var marker=history ? query(connection,"SELECT version,script,type,checksum,success FROM `"+schema
                    +"`.bty_flyway_history WHERE version='15'") : List.<Map<String,Object>>of();
            if(columns.isEmpty() && triggers.isEmpty() && checks.isEmpty() && indexes.isEmpty() && marker.isEmpty())
                return new Postcondition(Installation.ABSENT,null);
            require(columns.size()==2 && triggers.size()==2 && checks.size()==2 && indexes.size()==4 && marker.size()==1);
            var d=descriptor();
            for(int i=0;i<2;i++) {
                var c=columns.get(i); String name=i==0?"execution_no":"action_observation_json";
                require(name.equals(text(c,"column_name")) && (i==0?"varchar(32)":"json").equals(text(c,"column_type").toLowerCase(Locale.ROOT))
                        && "YES".equals(text(c,"is_nullable")) && (i==0?"utf8mb4_bin":"").equals(text(c,"collation_name"))
                        && c.get("column_default")==null && text(c,"extra").isEmpty());
            }
            for(int i=0;i<4;i++) {
                var index=indexes.get(i);
                require(d.path("index").path("columns").get(i).asString().equals(text(index,"column_name"))
                        && index.get("non_unique") instanceof Number n && n.intValue()==0
                        && index.get("seq_in_index") instanceof Number ordinal && ordinal.intValue()==i+1
                        && "BTREE".equals(text(index,"index_type")) && "YES".equals(text(index,"is_visible"))
                        && index.get("sub_part")==null && "A".equals(text(index,"collation")));
            }
            var actionCheck=query(connection,"SELECT tc.CONSTRAINT_NAME,cc.CHECK_CLAUSE,tc.ENFORCED"
                    +" FROM information_schema.TABLE_CONSTRAINTS tc JOIN information_schema.CHECK_CONSTRAINTS cc"
                    +" ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME"
                    +" WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME='bty_service_submission' AND tc.CONSTRAINT_NAME='ck_bty_task_action'",schema);
            require(actionCheck.size()==1);
            var all=new ArrayList<>(checks); all.addAll(actionCheck);
            for(var expected:d.path("checks").properties()) {
                var found=all.stream().filter(r -> expected.getKey().equals(text(r,"constraint_name"))).toList();
                require(found.size()==1 && "YES".equals(text(found.getFirst(),"enforced"))
                        && ServiceExecutionSchemaVerifier.expression(expected.getValue().asString()).equals(
                            ServiceExecutionSchemaVerifier.expression(text(found.getFirst(),"check_clause"))));
            }
            for(var expected:d.path("triggers")) {
                var found=triggers.stream().filter(r -> expected.path("name").asString().equals(text(r,"trigger_name"))).toList();
                require(found.size()==1 && "BEFORE".equals(text(found.getFirst(),"action_timing"))
                        && "bty_service_submission".equals(text(found.getFirst(),"event_object_table"))
                        && expected.path("event").asString().equals(text(found.getFirst(),"event_manipulation"))
                        && ServiceExecutionSchemaVerifier.tokens(expected.path("body").asString()).equals(
                            ServiceExecutionSchemaVerifier.tokens(text(found.getFirst(),"action_statement"))));
            }
            var rows=query(connection,"SELECT version,script,type,checksum,success FROM `"+schema+"`.bty_flyway_history WHERE type<>'BASELINE'");
            require(rows.size()==15 || rows.size()==16 && TaskWorkflowSchemaVerifier.installed(source));
            for(int i=0;i<15;i++) {
                final String version=Integer.toString(i+1); var expected=d.path("history").get(i);
                var found=rows.stream().filter(r -> version.equals(text(r,"version"))).toList();
                require(found.size()==1); var actual=found.getFirst();
                require(expected.path("script").asString().equals(text(actual,"script")) && "SQL".equals(text(actual,"type"))
                        && actual.get("checksum") instanceof Number crc && crc.intValue()==expected.path("checksum").asInt()
                        && (Boolean.TRUE.equals(actual.get("success")) || actual.get("success") instanceof Number ok && ok.intValue()==1));
            }
            return new Postcondition(Installation.INSTALLED,d);
        } catch(Exception corrupt) { return new Postcondition(Installation.CORRUPT,null); }
        finally { if(connection!=null) DataSourceUtils.releaseConnection(connection,source); }
    }
    public static JsonNode descriptor() throws Exception {
        var loader=TaskStartSchemaExtension.class.getClassLoader();
        var descriptors=Collections.list(loader.getResources("META-INF/shop/beauty-task-start-schema.json"));
        var scripts=Collections.list(loader.getResources("db/industry/beauty/"+SCRIPT));
        require(descriptors.size()==1 && scripts.size()==1); JsonNode d;
        try(var input=descriptors.getFirst().openStream()) { d=JsonMapper.builder().build().readTree(input); }
        require(d.path("schemaVersion").asInt()==1 && d.path("platformVersion").asInt()==15
                && "beauty-core/primary".equals(d.path("owner").asString()) && SCRIPT.equals(d.path("migrationScript").asString())
                && d.path("history").size()==15 && d.path("columns").size()==2 && d.path("checks").size()==3 && d.path("triggers").size()==2);
        try(var input=scripts.getFirst().openStream()) {
            byte[] bytes=input.readAllBytes();
            require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(d.path("migrationSha256").asString())
                    && checksum(bytes)==d.path("flywayChecksum").asInt());
        }
        for(int i=0;i<15;i++) {
            var item=d.path("history").get(i); String name=item.path("script").asString();
            require(name.startsWith("V"+(i+1)+"__")); var files=Collections.list(loader.getResources("db/industry/beauty/"+name));
            require(files.size()==1); try(var input=files.getFirst().openStream()) { require(checksum(input.readAllBytes())==item.path("checksum").asInt()); }
        }
        return d;
    }
    private static int checksum(byte[] bytes) {
        var crc=new CRC32(); crc.update(new String(bytes,StandardCharsets.UTF_8).replaceFirst("^\\uFEFF","")
                .replace("\r","").replace("\n","").getBytes(StandardCharsets.UTF_8)); return (int)crc.getValue();
    }
    private static List<Map<String,Object>> query(Connection c,String sql,Object... values) throws SQLException {
        try(var statement=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            try(var result=statement.executeQuery()) {
                var rows=new ArrayList<Map<String,Object>>();
                while(result.next()) {
                    var row=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=result.getMetaData().getColumnCount();i++) row.put(result.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT),result.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    private static String text(Map<String,Object> row,String key) { var value=row.get(key); return value==null?"":value.toString(); }
    private static void require(boolean value) { if(!value) throw new IllegalStateException("TASK_START_SCHEMA_CORRUPT"); }
}
