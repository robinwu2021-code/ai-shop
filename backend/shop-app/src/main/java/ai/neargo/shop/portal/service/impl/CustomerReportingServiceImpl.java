package ai.neargo.shop.portal.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.portal.service.CustomerReportingService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("ops")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerReportingServiceImpl implements CustomerReportingService {
  private final NamedParameterJdbcTemplate jdbc;
  private final Clock clock;
  public CustomerReportingServiceImpl(DataSource dataSource) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource); this.clock = Clock.systemUTC();
  }
  private MapSqlParameterSource params(Scope s) {
    return new MapSqlParameterSource("tenant", s.tenantNo())
        .addValue("entities", s.entities().isEmpty() ? Set.of("__NO_SCOPE__") : s.entities());
  }
  private String scope(Scope s, String entity) {
    return s.all() ? "1=1" : s.entities().isEmpty() ? "1=0" : entity + " IN (:entities)";
  }
  private String accountWhere(Scope s) {
    // usr_account.entity_no is a favourite store, never an ownership or visibility anchor.
    return "u.tenant_no=:tenant AND u.deleted=0 AND " + (s.all() ? "1=1" :
        "EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no=u.tenant_no AND a.user_no=u.user_no AND a.deleted=0 AND a.status='ACTIVE' AND " + scope(s,"a.entity_no") + ")");
  }
  private String registrationStoreWhere(Scope s) {
    return "a.tenant_no=:tenant AND a.user_no=u.user_no AND a.deleted=0 AND a.status='ACTIVE' AND " + scope(s,"a.entity_no");
  }
  @Override public OperatingEntity operatingEntity(Scope s, String id) {
    var p=params(s).addValue("id",id);
    String visible=s.all()?"1=1":"(oe.legal_subject_no IN (:entities) OR (oe.legal_subject_no IS NULL AND EXISTS (SELECT 1 FROM mch_account a WHERE a.tenant_no=oe.tenant_no COLLATE utf8mb4_unicode_520_ci AND a.user_no=oe.owner_user_no COLLATE utf8mb4_unicode_520_ci AND a.deleted=0 AND a.status='ACTIVE' AND a.is_owner=1 AND "+scope(s,"a.entity_no")+")))";
    var rows=jdbc.query("SELECT oe.operating_entity_no,oe.name,oe.market,oe.status,oe.version,oe.legal_subject_no,oe.owner_user_no,("+visible+") visible FROM mch_operating_entity oe JOIN usr_account u ON u.tenant_no=oe.tenant_no COLLATE utf8mb4_unicode_520_ci AND u.user_no=oe.owner_user_no COLLATE utf8mb4_unicode_520_ci AND u.deleted=0 AND u.status='NORMAL' WHERE oe.tenant_no=:tenant AND oe.operating_entity_no=:id AND oe.deleted=0 AND oe.status='ENABLED'",p,
        (r,n)->new AbstractMap.SimpleImmutableEntry<>(r.getBoolean("visible"),new OperatingEntity(r.getString("operating_entity_no"),r.getString("name"),r.getString("market"),r.getString("status"),r.getLong("version"),r.getString("legal_subject_no"),r.getString("owner_user_no"),Set.of("PAYMENT_MERCHANT_CREATE"))));
    if(rows.isEmpty())throw BizException.of(ErrorCode.NOT_FOUND);
    if(!rows.getFirst().getKey())throw BizException.of(ErrorCode.FORBIDDEN);
    return rows.getFirst().getValue();
  }
  @Override public Registrations registrations(Scope s, Filter f) {
    var p=params(s).addValue("keyword", "%"+Objects.toString(f.keyword(),"")+"%")
        .addValue("limit",f.size()).addValue("offset",(f.page()-1)*f.size());
    String where=accountWhere(s)+" AND (u.user_no LIKE :keyword OR u.nickname LIKE :keyword)";
    String associations=registrationStoreWhere(s);
    String storeCount="(SELECT COUNT(DISTINCT st.store_no) FROM mch_account a JOIN mch_store st ON st.tenant_no=a.tenant_no AND st.entity_no=a.entity_no AND st.deleted=0 WHERE "+associations+")";
    var records=jdbc.query("SELECT u.user_no,u.nickname,u.status,u.market,u.created_at,"
        +"(SELECT COUNT(DISTINCT a.entity_no) FROM mch_account a WHERE "+associations+") entity_count,"
        +storeCount+" store_count FROM usr_account u WHERE "+where+" ORDER BY u.created_at DESC,u.user_no DESC LIMIT :limit OFFSET :offset",p,
        (r,n)->new Registration(r.getString("user_no"),r.getString("nickname"),r.getString("status"),r.getString("market"),r.getTimestamp("created_at").toInstant().toEpochMilli(),r.getLong("entity_count"),r.getLong("store_count")));
    long total=count("SELECT COUNT(*) FROM usr_account u WHERE "+where,p);
    long without=count("SELECT COUNT(*) FROM usr_account u WHERE "+where+" AND "+storeCount+"=0",p);
    var from=LocalDate.now(clock).minusDays(29);
    p.addValue("from",from.atStartOfDay());
    var raw=jdbc.query("SELECT DATE(u.created_at) day,COUNT(*) total FROM usr_account u WHERE "+where+" AND u.created_at>=:from GROUP BY DATE(u.created_at)",p,
        (r,n)->new Trend(r.getString("day"),r.getLong("total")));
    var byDay=new HashMap<String,Long>();raw.forEach(t->byDay.put(t.date(),t.count()));
    var trend=new ArrayList<Trend>();for(int i=0;i<30;i++){String day=from.plusDays(i).toString();trend.add(new Trend(day,byDay.getOrDefault(day,0L)));}
    return new Registrations(PageData.of(records,total,f.page(),f.size()),total,without,trend.stream().mapToLong(Trend::count).sum(),trend);
  }
  @Override public List<StoreLink> registrationStores(Scope s,String userNo) {
    var p=params(s).addValue("user",userNo);
    return jdbc.query("SELECT DISTINCT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mch_account a JOIN usr_account u ON u.tenant_no=a.tenant_no AND u.user_no=a.user_no AND u.deleted=0 JOIN mch_store st ON st.tenant_no=a.tenant_no AND st.entity_no=a.entity_no AND st.deleted=0 "+brandJoins()+" WHERE a.tenant_no=:tenant AND a.user_no=:user AND a.deleted=0 AND a.status='ACTIVE' AND "+scope(s,"a.entity_no")+" ORDER BY st.store_no",p,this::store);
  }
  private String brandJoins() {
    return " LEFT JOIN mch_brand_store_binding bb ON bb.tenant_no=st.tenant_no AND bb.store_no=st.store_no AND bb.deleted=0 AND bb.status='ACTIVE' AND bb.effective_from<=UTC_TIMESTAMP(3) AND (bb.effective_to IS NULL OR bb.effective_to>UTC_TIMESTAMP(3)) LEFT JOIN mch_brand b ON b.tenant_no=bb.tenant_no AND b.brand_no=bb.brand_no AND b.deleted=0 ";
  }
  private StoreLink store(ResultSet r,int n)throws SQLException {
    return new StoreLink(r.getString("store_no"),r.getString("name"),r.getString("entity_no"),r.getString("brand_no"),r.getString("brand_name"));
  }
  @Override public PageData<Brand> brands(Scope s,Filter f) {
    var p=params(s).addValue("keyword","%"+Objects.toString(f.keyword(),"")+"%").addValue("limit",f.size()).addValue("offset",(f.page()-1)*f.size());
    String binding="bb.tenant_no=b.tenant_no AND bb.brand_no=b.brand_no AND bb.deleted=0 AND bb.status='ACTIVE' AND bb.effective_from<=UTC_TIMESTAMP(3) AND (bb.effective_to IS NULL OR bb.effective_to>UTC_TIMESTAMP(3)) AND st.deleted=0 AND "+scope(s,"st.entity_no");
    String where="b.tenant_no=:tenant AND b.deleted=0 AND (b.name LIKE :keyword OR b.brand_no LIKE :keyword)"+(s.all()?"":" AND EXISTS (SELECT 1 FROM mch_brand_store_binding bb JOIN mch_store st ON st.tenant_no=bb.tenant_no AND st.store_no=bb.store_no WHERE "+binding+")");
    long total=count("SELECT COUNT(*) FROM mch_brand b WHERE "+where,p);
    var rows=jdbc.query("SELECT b.brand_no,b.name,b.status,b.management_no,ms.name management_name FROM mch_brand b LEFT JOIN mch_management_space ms ON ms.tenant_no=b.tenant_no AND ms.management_no=b.management_no AND ms.deleted=0 WHERE "+where+" ORDER BY b.brand_no LIMIT :limit OFFSET :offset",p,
        (r,n)->new Brand(r.getString("brand_no"),r.getString("name"),r.getString("status"),r.getString("management_no"),r.getString("management_name"),List.of()));
    var result=new ArrayList<Brand>();
    for(var b:rows){p.addValue("brand",b.brandNo());var stores=jdbc.query("SELECT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mch_brand b JOIN mch_brand_store_binding bb ON bb.tenant_no=b.tenant_no AND bb.brand_no=b.brand_no JOIN mch_store st ON st.tenant_no=bb.tenant_no AND st.store_no=bb.store_no WHERE b.tenant_no=:tenant AND b.brand_no=:brand AND "+binding+" ORDER BY st.store_no",p,this::store);result.add(new Brand(b.brandNo(),b.name(),b.status(),b.managementNo(),b.managementName(),stores));}
    return PageData.of(result,total,f.page(),f.size());
  }
  private String memberWhere(Scope s,String alias) {return alias+".tenant_no=:tenant AND "+alias+".deleted=0 AND "+scope(s,alias+".entity_no");}
  private String memberRelations(String m) {
    return "(st.store_no="+m+".first_store_no OR EXISTS (SELECT 1 FROM mbr_member_store rel WHERE rel.tenant_no="+m+".tenant_no AND rel.member_no="+m+".member_no AND rel.entity_no="+m+".entity_no AND rel.store_no=st.store_no AND rel.deleted=0) OR EXISTS (SELECT 1 FROM mbr_member_source src WHERE src.tenant_no="+m+".tenant_no AND src.member_no="+m+".member_no AND src.entity_no="+m+".entity_no AND src.store_no=st.store_no AND src.deleted=0))";
  }
  private String personFrom(Scope s) {
    // Select the earliest visible original membership before applying store/brand filters.
    return " FROM usr_person p JOIN mbr_member first ON first.person_no=p.person_no AND first.tenant_no=p.tenant_no AND first.id=(SELECT m0.id FROM mbr_member m0 WHERE m0.person_no=p.person_no AND "+memberWhere(s,"m0")+" ORDER BY m0.joined_at,m0.id LIMIT 1) LEFT JOIN mch_store origin ON origin.tenant_no=first.tenant_no AND origin.store_no=first.first_store_no AND "+scope(s,"origin.entity_no")+" ";
  }
  private String personColumns(Scope s) {
    return "SELECT p.person_no,p.user_no,p.phone_tail,p.status,first.joined_at,first.source,first.first_store_no,origin.name source_store_name,(SELECT COUNT(*) FROM mbr_member mc WHERE mc.person_no=p.person_no AND "+memberWhere(s,"mc")+") membership_count";
  }
  private Person person(ResultSet r,int n)throws SQLException {
    return new Person(r.getString("person_no"),r.getString("user_no"),r.getString("phone_tail"),r.getString("status"),r.getLong("joined_at"),r.getLong("membership_count"),r.getString("source"),r.getString("first_store_no"),r.getString("source_store_name"));
  }
  @Override public PageData<Person> members(Scope s,Filter f) {
    var p=params(s).addValue("keyword","%"+Objects.toString(f.keyword(),"")+"%").addValue("limit",f.size()).addValue("offset",(f.page()-1)*f.size());
    String where="p.tenant_no=:tenant AND p.deleted=0 AND (p.person_no LIKE :keyword OR p.phone_tail LIKE :keyword)";
    if(f.storeNo()!=null || f.brandNo()!=null) {
      String filter="";
      if(f.storeNo()!=null){p.addValue("store",f.storeNo());filter+=" AND st.store_no=:store";}
      if(f.brandNo()!=null){p.addValue("brand",f.brandNo());filter+=" AND b.brand_no=:brand";}
      where+=" AND EXISTS (SELECT 1 FROM mbr_member m JOIN mch_store st ON st.tenant_no=m.tenant_no AND "+memberRelations("m")+" AND st.deleted=0 AND "+scope(s,"st.entity_no")+brandJoins()+" WHERE m.person_no=p.person_no AND "+memberWhere(s,"m")+filter+")";
    }
    String from=personFrom(s)+" WHERE "+where;
    long total=count("SELECT COUNT(*)"+from,p);
    var rows=jdbc.query(personColumns(s)+from+" ORDER BY first.joined_at DESC,p.person_no DESC LIMIT :limit OFFSET :offset",p,this::person);
    return PageData.of(rows,total,f.page(),f.size());
  }
  @Override public PersonDetail member(Scope s,String personNo) {
    var p=params(s).addValue("person",personNo);
    var rows=jdbc.query(personColumns(s)+personFrom(s)+" WHERE p.tenant_no=:tenant AND p.person_no=:person AND p.deleted=0",p,this::person);
    if(rows.isEmpty())throw BizException.of(ErrorCode.NOT_FOUND);
    var memberships=jdbc.query("SELECT m.member_no,m.entity_no,e.name entity_name,m.status,m.joined_at,m.source,m.first_store_no,origin.name source_store_name FROM mbr_member m LEFT JOIN mch_entity e ON e.tenant_no=m.tenant_no AND e.entity_no=m.entity_no LEFT JOIN mch_store origin ON origin.tenant_no=m.tenant_no AND origin.store_no=m.first_store_no AND "+scope(s,"origin.entity_no")+" WHERE m.person_no=:person AND "+memberWhere(s,"m")+" ORDER BY m.joined_at,m.id",p,
      (r,n)->new Membership(r.getString("member_no"),r.getString("entity_no"),r.getString("entity_name"),r.getString("status"),r.getLong("joined_at"),r.getString("source"),r.getString("first_store_no"),r.getString("source_store_name")));
    var sources=jdbc.query("SELECT src.source_no,src.member_no,src.source_type,src.store_no,st.name,src.occurred_at,src.is_first FROM mbr_member_source src JOIN mbr_member m ON m.tenant_no=src.tenant_no AND m.member_no=src.member_no AND m.entity_no=src.entity_no LEFT JOIN mch_store st ON st.tenant_no=src.tenant_no AND st.store_no=src.store_no AND "+scope(s,"st.entity_no")+" WHERE m.person_no=:person AND "+memberWhere(s,"m")+" AND src.deleted=0 ORDER BY src.occurred_at,src.id",p,
      (r,n)->new Source(r.getString("source_no"),r.getString("member_no"),r.getString("source_type"),r.getString("store_no"),r.getString("name"),r.getLong("occurred_at"),r.getBoolean("is_first")));
    var stores=jdbc.query("SELECT DISTINCT st.store_no,st.name,st.entity_no,b.brand_no,b.name brand_name FROM mbr_member m JOIN mch_store st ON st.tenant_no=m.tenant_no AND "+memberRelations("m")+" AND st.deleted=0 AND "+scope(s,"st.entity_no")+brandJoins()+" WHERE m.person_no=:person AND "+memberWhere(s,"m")+" ORDER BY st.store_no",p,this::store);
    return new PersonDetail(rows.getFirst(),memberships,sources,stores);
  }
  private long count(String sql,MapSqlParameterSource p){return jdbc.queryForObject(sql,p,Long.class);}
}
