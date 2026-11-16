package ai.neargo.shop.portal.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual immutable production DDL on disposable MySQL9; no invented reporting schema. */
public final class CustomerReportingFixture {
  private CustomerReportingFixture() {}
  private static final String HASH="0".repeat(64);
  public static void createAndSeed(JdbcTemplate db) {
    create(db,"V1__baseline.sql","usr_account","mch_account","mch_entity","mch_store");
    create(db,"V222__usr_person.sql","usr_person");
    create(db,"V224__mbr_member.sql","mbr_member","mbr_member_store","mbr_member_source");
    create(db,"V544__brand_management_organization.sql","mch_management_space","mch_brand","mch_brand_store_binding");
    create(db,"V648__operating_entity_create.sql","mch_operating_entity");
    // Replay the exact market-column ALTER from its immutable source migration.
    var market=Pattern.compile("(?is)ALTER TABLE usr_account ADD COLUMN market[^;]*;").matcher(source("V629__account_market_and_store_industry_text.sql"));
    if(!market.find())throw new IllegalStateException("Actual registration market ALTER missing");
    db.execute(market.group());
    var now=LocalDateTime.now(java.time.Clock.systemUTC());
    account(db,"MAIN","U1","Owner one","TW","E2",now.minusDays(5),false);
    account(db,"MAIN","U2","Owner two","CN",null,now.minusDays(2),false);
    account(db,"MAIN","U0","No store","AE","E1",now,false);
    account(db,"MAIN","DELETED","Deleted","TW",null,now,true);
    account(db,"OTHER","FU1","Foreign","CN",null,now,false);
    businessAccount(db,"MAIN","A1","U1","E1","ACTIVE");businessAccount(db,"MAIN","A2","U2","E2","ACTIVE");businessAccount(db,"MAIN","OLD","U0","E1","DISABLED");businessAccount(db,"OTHER","FA1","FU1","FE1","ACTIVE");
    insert(db,"mch_entity","entity_no,name","E1","Business one");insert(db,"mch_entity","entity_no,name","E2","Business two");insert(db,"mch_entity","tenant_no,entity_no,name","OTHER","FE1","Foreign entity");
    store(db,"MAIN","S1","E1","Original store");store(db,"MAIN","S2","E1","Later store");store(db,"MAIN","S3","E2","Hidden store");store(db,"OTHER","FS1","FE1","Foreign store");
    management(db,"MAIN","MG1","Real chain management");management(db,"OTHER","MG1","Foreign management");
    brand(db,"MAIN","B1","Real shared brand","ACTIVE");brand(db,"MAIN","B0","Unbound brand","DRAFT");brand(db,"MAIN","B2","Hidden brand","ACTIVE");brand(db,"OTHER","B1","Foreign brand","ACTIVE");
    binding(db,"MAIN","BIND1","B1","S1","ACTIVE",now.minusDays(10),null);binding(db,"MAIN","BIND2","B1","S3","ACTIVE",now.minusDays(10),null);binding(db,"MAIN","BIND3","B2","S2","ENDED",now.minusDays(20),now.minusDays(5));binding(db,"OTHER","FBIND1","B1","FS1","ACTIVE",now.minusDays(10),null);
    person(db,"MAIN","PS1","U1","1234");person(db,"MAIN","PS2",null,"1234");person(db,"MAIN","PS3","U2","5555");person(db,"OTHER","FPS1","FU1","9999");
    member(db,1,"MAIN","M1","E1","PS1","ACTIVE","SCAN","S1",100);
    member(db,2,"MAIN","M2","E2","PS1","ACTIVE","ORDER","S3",200);
    member(db,3,"MAIN","M3","E1","PS2","LEAD","MANUAL",null,150);
    member(db,4,"MAIN","M4","E2","PS3","ACTIVE","ORDER","S3",300);
    member(db,5,"OTHER","FM1","FE1","FPS1","ACTIVE","ORDER","FS1",1);
    memberSource(db,1,"MAIN","SRC1","M1","E1","SCAN","S1",100,true);memberSource(db,2,"MAIN","SRC2","M1","E1","ORDER","S2",500,false);memberSource(db,3,"MAIN","SRC3","M2","E2","ORDER","S3",200,true);memberSource(db,4,"OTHER","FSRC","FM1","FE1","ORDER","FS1",1,true);
    insert(db,"mbr_member_store","member_no,entity_no,store_no","M1","E1","S2");insert(db,"mbr_member_store","member_no,entity_no,store_no","M2","E2","S3");insert(db,"mbr_member_store","tenant_no,member_no,entity_no,store_no","OTHER","FM1","FE1","FS1");
    operating(db,"MAIN","OE1","U1","Linked operating entity","TW","E1","ENABLED");operating(db,"MAIN","OE0","U1","Independent operating entity","TW",null,"ENABLED");operating(db,"MAIN","OE2","U2","Out of scope","CN","E2","ENABLED");operating(db,"MAIN","OFF","U1","Disabled","TW","E1","DISABLED");operating(db,"OTHER","OE1","FU1","Foreign operating entity","CN","FE1","ENABLED");
  }
  private static String source(String file) {
    try(var in=new ClassPathResource("db/migration/"+file).getInputStream()) {return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
    catch(IOException e){throw new UncheckedIOException(e);}
  }
  private static void create(JdbcTemplate db,String file,String... tables) {
    var sql=source(file);
    for(String table:tables){
      var ddl=Pattern.compile("(?is)CREATE TABLE (?:IF NOT EXISTS )?"+table+"\\s*\\(.*?\\)\\s*ENGINE[^;]*;").matcher(sql);
      if(!ddl.find())throw new IllegalStateException("Missing production DDL: "+table);
      // Legacy source collation adaptation only; preserve all columns, keys and native generated constraints.
      db.execute(ddl.group().replace("utf8mb4_uca1400_ai_ci","utf8mb4_unicode_520_ci"));
    }
  }
  private static void insert(JdbcTemplate db,String table,String columns,Object... values) {
    var row=new LinkedHashMap<String,Object>();row.put("tenant_no","MAIN");row.put("created_at",LocalDateTime.now(java.time.Clock.systemUTC()));row.put("updated_at",row.get("created_at"));row.put("created_by","FIXTURE");row.put("updated_by","FIXTURE");
    var keys=columns.split(",");for(int i=0;i<keys.length;i++)row.put(keys[i],values[i]);
    db.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
  }
  private static void account(JdbcTemplate db,String tenant,String no,String name,String market,String favorite,LocalDateTime at,boolean deleted){insert(db,"usr_account","tenant_no,user_no,nickname,status,market,entity_no,created_at,deleted",tenant,no,name,"NORMAL",market,favorite,at,deleted);}
  private static void businessAccount(JdbcTemplate db,String tenant,String no,String user,String entity,String status){insert(db,"mch_account","tenant_no,mch_account_no,user_no,entity_no,status,is_owner",tenant,no,user,entity,status,1);}
  private static void store(JdbcTemplate db,String tenant,String no,String entity,String name){insert(db,"mch_store","tenant_no,store_no,entity_no,name",tenant,no,entity,name);}
  private static void management(JdbcTemplate db,String tenant,String no,String name){insert(db,"mch_management_space","tenant_no,management_no,name,owner_actor_no,status,bootstrap_source_account_no,bootstrap_key,bootstrap_hash",tenant,no,name,"U1","ACTIVE","A1","CREATE_"+no,HASH);}
  private static void brand(JdbcTemplate db,String tenant,String no,String name,String status){insert(db,"mch_brand","tenant_no,brand_no,management_no,name,status,scope_revision,name_key,create_key,create_hash",tenant,no,"MG1",name,status,1,name,"CREATE_"+no,HASH);}
  private static void binding(JdbcTemplate db,String tenant,String no,String brand,String store,String status,LocalDateTime from,LocalDateTime to){insert(db,"mch_brand_store_binding","tenant_no,binding_no,brand_no,store_no,status,effective_from,effective_to,scope_revision,change_no",tenant,no,brand,store,status,from,to,1,"CHANGE_"+no);}
  private static void person(JdbcTemplate db,String tenant,String no,String user,String tail){insert(db,"usr_person","tenant_no,person_no,user_no,phone_tail,phone_hash,status",tenant,no,user,tail,no,"ACTIVE");}
  private static void member(JdbcTemplate db,long id,String tenant,String no,String entity,String person,String status,String source,String store,long joined){insert(db,"mbr_member","id,tenant_no,member_no,entity_no,person_no,status,source,first_store_no,joined_at",id,tenant,no,entity,person,status,source,store,joined);}
  private static void memberSource(JdbcTemplate db,long id,String tenant,String no,String member,String entity,String type,String store,long at,boolean first){insert(db,"mbr_member_source","id,tenant_no,source_no,member_no,entity_no,source_type,store_no,occurred_at,is_first",id,tenant,no,member,entity,type,store,at,first);}
  private static void operating(JdbcTemplate db,String tenant,String no,String owner,String name,String market,String legal,String status){insert(db,"mch_operating_entity","tenant_no,operating_entity_no,owner_user_no,name,market,legal_subject_no,status,version,create_key,create_hash",tenant,no,owner,name,market,legal,status,2,"CREATE_"+no,HASH);}
}
