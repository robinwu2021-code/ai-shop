package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

public class PurchaseOrderQueryTest {
    JdbcTemplate db; JdbcPurchaseOrderQuery query;
    static final Filter ALL=new Filter(null,null,null,null,null,null,null,false);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:report_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds); query=new JdbcPurchaseOrderQuery(ds);
        db.execute("CREATE ALIAS JSON_EXTRACT FOR 'ai.neargo.commerce.persistence.PurchaseOrderQueryTest.extract'");
        db.execute("CREATE ALIAS JSON_UNQUOTE FOR 'ai.neargo.commerce.persistence.PurchaseOrderQueryTest.unquote'");
        db.execute("CREATE TABLE mall_order(tenant_no VARCHAR,order_no VARCHAR,entity_no VARCHAR,store_no VARCHAR,currency VARCHAR,cash_due_minor BIGINT,pay_status VARCHAR,created_at TIMESTAMP(3))");
        db.execute("CREATE TABLE mall_offline_claim(tenant_no VARCHAR,order_no VARCHAR,claim_no VARCHAR,claim_snapshot VARCHAR)");
        order("MAIN","Z","E2","S2","USD",100,3000);order("MAIN","B","E1","S1","USD",9007199254740993L,2000);
        order("MAIN","A","E1","S1","USD",11,2000);order("MAIN","OLD","E1","DELETED_STORE","CNY",12,1000);
        order("OTHER","B","E1","S1","USD",99,5000);
        claim("B","C1","SUBMITTED"); claim("B","C2","DECLINED");claim("A","C1","REVIEWING");
    }
    @AfterEach void close() { db.execute("DROP ALL OBJECTS"); }
    void order(String t,String no,String e,String s,String c,long amount,long time) {
        db.update("INSERT INTO mall_order VALUES(?,?,?,?,?,?,?,?)",t,no,e,s,c,amount,"UNPAID",LocalDateTime.ofInstant(Instant.ofEpochMilli(time),ZoneOffset.UTC));
    }
    void claim(String no,String claim,String status) { db.update("INSERT INTO mall_offline_claim VALUES('MAIN',?,?,?)",no,claim,"{\"status\":\""+status+"\",\"submittedAt\":1}"); }
    List<Row> search(Access access,Filter filter,Cursor after,int limit) { return query.search(new Query(access,filter,after,limit)); }
    Access restricted() { return new Access("MAIN",false,Set.of("E1")); }
    @Test void scopesFilterBeforeLimitAndKeepFinancialHistory() {
        var rows=search(restricted(),ALL,null,1);assertEquals("B",rows.getFirst().orderNo());
        assertEquals("9007199254740993",rows.getFirst().payableMinor());
        assertEquals(List.of("B","A","OLD"),search(restricted(),ALL,null,10).stream().map(Row::orderNo).toList());
        assertTrue(search(new Access("MAIN",false,Set.of()),ALL,null,10).isEmpty());
        assertTrue(search(restricted(),new Filter("E2",null,null,null,null,null,null,false),null,10).isEmpty());
        assertEquals(4,search(new Access("MAIN",true,Set.of()),ALL,null,10).size());
        assertEquals("DELETED_STORE",search(restricted(),ALL,null,10).getLast().storeNo());
    }
    @Test void cursorHandlesModelTiesAndOrderDescending() {
        assertEquals(List.of("B","A","OLD"),search(restricted(),ALL,new Cursor(2000,Model.ENTITY,"ZZ"),10).stream().map(Row::orderNo).toList());
        assertEquals(List.of("A","OLD"),search(restricted(),ALL,new Cursor(2000,Model.STORE,"B"),10).stream().map(Row::orderNo).toList());
    }
    @Test void filtersAndPendingClaimsAreAppliedInSql() {
        var filter=new Filter("E1","S1",null,"UNPAID","USD",2000L,3000L,true);
        var rows=search(restricted(),filter,null,1);assertEquals("B",rows.getFirst().orderNo());assertEquals("SUBMITTED",rows.getFirst().claimStatus());
        assertEquals(2,search(restricted(),filter,null,10).size());
        assertEquals("REVIEWING",search(restricted(),filter,new Cursor(2000,Model.STORE,"B"),10).getFirst().claimStatus());
        assertTrue(search(restricted(),new Filter(null,null,"' OR 1=1 --",null,null,null,null,false),null,10).isEmpty());
    }
    @Test void attributionIsTenantConstrainedBoundedAndContractsAreImmutable() {
        assertEquals("9007199254740993",query.findByOrderNos("MAIN",List.of("B")).getFirst().payableMinor());
        assertEquals("99",query.findByOrderNos("OTHER",List.of("B")).getFirst().payableMinor());
        assertTrue(query.findByOrderNos("MAIN",List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->query.findByOrderNos("MAIN",Collections.nCopies(501,"B")));
        assertThrows(IllegalArgumentException.class,()->new Query(restricted(),ALL,null,501));
        var refs=new HashSet<>(Set.of("E1"));var access=new Access("MAIN",false,refs);refs.clear();assertTrue(access.allows("E1"));
        assertThrows(UnsupportedOperationException.class,()->access.entityNos().clear());
    }
    // H2 lacks these MySQL JSON functions. Fixtures expose the same scalar semantics; native SQL remains unchanged.
    public static String extract(String json,String path) {
        var node=JsonMapper.builder().build().readTree(json);for(var part:path.substring(2).split("\\."))node=node.path(part);
        return node.isMissingNode()?null:node.toString();
    }
    public static String unquote(String value) { return value==null?null:JsonMapper.builder().build().readTree(value).asText(); }
}
