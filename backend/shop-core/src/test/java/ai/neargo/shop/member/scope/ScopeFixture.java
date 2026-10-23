package ai.neargo.shop.member.scope;
import ai.neargo.shop.member.service.*;
import ai.neargo.shop.spi.member.MemberOperatingScopePort;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;import java.util.*;
import static org.mockito.Mockito.*;
class ScopeFixture {
 final JdbcTemplate jdbc;final MemberOperatingScopePort org=mock(MemberOperatingScopePort.class);final MemberIdentityFactsPort identities=mock(MemberIdentityFactsPort.class);
 final MemberScopeService members;final MemberScopeQueryService query;
 ScopeFixture() throws Exception {
  String url=System.getProperty("p2.mysql.url");if(url!=null&&!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p2_member_[0-9_]+\\?.*"))throw new IllegalArgumentException("isolated test database required");
  var ds=url==null?new DriverManagerDataSource("jdbc:h2:mem:scope-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""):new DriverManagerDataSource(url,System.getProperty("p2.mysql.user"),Files.readString(Path.of(System.getProperty("p2.mysql.password-file"))).trim());jdbc=new JdbcTemplate(ds);
  if(url!=null)for(String table:List.of("mbr_scope_legacy_link","mbr_store_profile","mbr_scope_member","mbr_member_store","mbr_member","mbr_identity_ticket"))jdbc.execute("DROP TABLE IF EXISTS "+table);
  jdbc.execute("CREATE TABLE mbr_identity_ticket(id bigint primary key)");
  String ddl=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V545__brand_member_scopes.sql")).replaceAll("(?m)^--.*$","");
  if(url==null)ddl=ddl.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci","").replace(",\n ADD COLUMN", ";ALTER TABLE mbr_identity_ticket ADD COLUMN").replace("CHECK (scope_type IN ('BRAND','STORE'))", "CHECK (CASE scope_type WHEN 'BRAND' THEN TRUE WHEN 'STORE' THEN TRUE ELSE FALSE END)");
  for(String part:ddl.split(";"))if(!part.isBlank()){if(url==null){var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);if(m.find())part=part.replace("KEY uk_","KEY "+m.group(1)+"_uk_").replace("KEY ix_","KEY "+m.group(1)+"_ix_");}jdbc.execute(part);}
  jdbc.execute("CREATE TABLE mbr_member(tenant_no varchar(32),member_no varchar(64),person_no varchar(64),entity_no varchar(64),first_store_no varchar(64),status varchar(24),remark varchar(500),deleted int default 0)");
  jdbc.execute("CREATE TABLE mbr_member_store(tenant_no varchar(32),member_no varchar(64),store_no varchar(64),deleted int default 0)");
  when(org.resolve("MAIN","E1","S1")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E1","S1","BRAND","B1",1,"甲店"));
  when(org.resolve("MAIN","E2","S2")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E2","S2","BRAND","B1",1,"乙店"));
  when(org.resolve("MAIN","E1","S3")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E1","S3","BRAND","B2",1,"丙店"));
  when(org.resolve("MAIN","E1","S4")).thenReturn(new MemberOperatingScopePort.Scope("MAIN","E1","S4","STORE","S4",0,"独立店"));
  when(org.lock(anyString(),anyString(),anyString())).thenAnswer(a->org.resolve(a.getArgument(0),a.getArgument(1),a.getArgument(2)));
  when(org.stores(any())).thenAnswer(a->{var s=(MemberOperatingScopePort.Scope)a.getArgument(0);return s.scopeNo().equals("B1")?List.of(org.resolve("MAIN","E1","S1"),org.resolve("MAIN","E2","S2")):List.of(s);});
  when(identities.find(eq("MAIN"),anyString())).thenAnswer(a->Optional.of(new MemberIdentityFactsPort.Identity(a.getArgument(1),null,"138****8000",true)));
  when(identities.match(eq("MAIN"),anyList(),anyString())).thenAnswer(a->((List<String>)a.getArgument(1)).stream().map(id->new MemberIdentityFactsPort.Identity(id,null,"138****8000",true)).toList());
  var repo=new MemberScopeRepository(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper());members=new MemberScopeService(repo,org,identities);query=new MemberScopeQueryService(repo,members,identities,"0123456789abcdefghijklmnopqrstuvwxyz-secret");
 }
}
