package ai.neargo.shop.portal.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.portal.service.CustomerReportingService;
import ai.neargo.shop.spi.user.OpsCustomerDirectoryPort;
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

/**
 * 运营端的顾客浏览：经营主体、注册、品牌、会员列表与详情。
 *
 * <p>是实时查询，不是报表（报表按日摄入、可重算，放报表模块）；跨域的入口组合按 Java 规范 §7.1 放在门户层。
 * 平台一侧（账号、人、主体、门店、品牌）经 {@link OpsCustomerDirectoryPort} 走平台句柄；这里只查会员营销库的会员表，再拼。
 * 会员列表跨两库分页：先到平台一侧把关键字、门店 / 品牌筛选换成编号集合，在会员一侧排序分页，再回平台取这一页的人与门店名。
 */
@Service
@Profile("ops")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerReportingServiceImpl implements CustomerReportingService {
  private final NamedParameterJdbcTemplate jdbc;
  private final OpsCustomerDirectoryPort platform;
  private final Clock clock;
  public CustomerReportingServiceImpl(DataSource dataSource, OpsCustomerDirectoryPort platform) {
    this.jdbc = new NamedParameterJdbcTemplate(dataSource); this.platform = platform; this.clock = Clock.systemUTC();
  }
  private static OpsCustomerDirectoryPort.Scope platformScope(Scope s) {
    return new OpsCustomerDirectoryPort.Scope(s.tenantNo(), s.all(), s.entities());
  }
  private static StoreLink link(OpsCustomerDirectoryPort.StoreLink l) {
    return new StoreLink(l.storeNo(), l.name(), l.entityNo(), l.brandNo(), l.brandName());
  }
  @Override public OperatingEntity operatingEntity(Scope s, String id) {
    var e = platform.operatingEntity(platformScope(s), id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    if(!e.visible())throw BizException.of(ErrorCode.FORBIDDEN);
    return new OperatingEntity(e.operatingEntityNo(),e.name(),e.market(),e.status(),e.version(),e.legalSubjectNo(),e.ownerUserNo(),Set.of("PAYMENT_MERCHANT_CREATE"));
  }
  @Override public Registrations registrations(Scope s, Filter f) {
    var from=LocalDate.now(clock).minusDays(29);
    var r=platform.registrations(platformScope(s),f.keyword(),f.page(),f.size(),from);
    var records=r.records().stream().map(x->new Registration(x.userNo(),x.nickname(),x.status(),x.market(),x.registeredAt(),x.entityCount(),x.storeCount())).toList();
    var trend=new ArrayList<Trend>();for(int i=0;i<30;i++){String day=from.plusDays(i).toString();trend.add(new Trend(day,r.dailyFrom().getOrDefault(day,0L)));}
    return new Registrations(PageData.of(records,r.total(),f.page(),f.size()),r.total(),r.withoutStores(),trend.stream().mapToLong(Trend::count).sum(),trend);
  }
  @Override public List<StoreLink> registrationStores(Scope s,String userNo) {
    return platform.registrationStores(platformScope(s),userNo).stream().map(CustomerReportingServiceImpl::link).toList();
  }
  @Override public PageData<Brand> brands(Scope s,Filter f) {
    var r=platform.brands(platformScope(s),f.keyword(),f.page(),f.size());
    var records=r.records().stream().map(b->new Brand(b.brandNo(),b.name(),b.status(),b.managementNo(),b.managementName(),b.stores().stream().map(CustomerReportingServiceImpl::link).toList())).toList();
    return PageData.of(records,r.total(),f.page(),f.size());
  }

  // ---- 会员一侧（会员营销库） ----
  private MapSqlParameterSource params(Scope s) {
    return new MapSqlParameterSource("tenant", s.tenantNo())
        .addValue("entities", s.entities().isEmpty() ? Set.of("__NO_SCOPE__") : s.entities());
  }
  private String scope(Scope s, String entity) {
    return s.all() ? "1=1" : s.entities().isEmpty() ? "1=0" : entity + " IN (:entities)";
  }
  private String memberWhere(Scope s,String alias) {return alias+".tenant_no=:tenant AND "+alias+".deleted=0 AND "+scope(s,alias+".entity_no");}
  /** 会员 m 与 :stores 里任一门店有关系：首店、店内档案（主体要对得上）、来源。 */
  private String memberRelations(String m) {
    return "("+m+".first_store_no IN (:stores) OR EXISTS (SELECT 1 FROM mbr_store_profile rel WHERE rel.tenant_no="+m+".tenant_no COLLATE utf8mb4_unicode_520_ci AND rel.member_no="+m+".member_no COLLATE utf8mb4_unicode_520_ci AND rel.entity_no="+m+".entity_no COLLATE utf8mb4_unicode_520_ci AND rel.store_no IN (:stores) AND rel.deleted=0) OR EXISTS (SELECT 1 FROM mbr_member_source src WHERE src.tenant_no="+m+".tenant_no AND src.member_no="+m+".member_no AND src.entity_no="+m+".entity_no AND src.store_no IN (:stores) AND src.deleted=0))";
  }
  /** 每个人最早的一条可见会员身份（先选出原始来源，再套门店 / 品牌筛选）。 */
  private String firstMembership(Scope s) {
    return " FROM mbr_member first WHERE "+memberWhere(s,"first")+" AND first.person_no IS NOT NULL AND first.id=(SELECT m0.id FROM mbr_member m0 WHERE m0.person_no=first.person_no AND "+memberWhere(s,"m0")+" ORDER BY m0.joined_at,m0.id LIMIT 1)";
  }
  private record FirstMembership(String personNo, long joinedAt, String source, String firstStoreNo, long membershipCount) {}
  private List<FirstMembership> firstMemberships(Scope s, String where, MapSqlParameterSource p, String tail) {
    return jdbc.query("SELECT first.person_no,first.joined_at,first.source,first.first_store_no,(SELECT COUNT(*) FROM mbr_member mc WHERE mc.person_no=first.person_no AND "+memberWhere(s,"mc")+") membership_count"+firstMembership(s)+where+tail,p,
        (r,n)->new FirstMembership(r.getString("person_no"),r.getLong("joined_at"),r.getString("source"),r.getString("first_store_no"),r.getLong("membership_count")));
  }
  private List<Person> persons(Scope s, List<FirstMembership> firsts) {
    var people=platform.persons(s.tenantNo(),firsts.stream().map(FirstMembership::personNo).toList());
    var storeNames=platform.storeNames(platformScope(s),firsts.stream().map(FirstMembership::firstStoreNo).filter(Objects::nonNull).distinct().toList());
    var result=new ArrayList<Person>();
    for(var m:firsts){var p=people.get(m.personNo());if(p==null)continue;
      result.add(new Person(p.personNo(),p.userNo(),p.phoneTail(),p.status(),m.joinedAt(),m.membershipCount(),m.source(),m.firstStoreNo(),m.firstStoreNo()==null?null:storeNames.get(m.firstStoreNo())));}
    return result;
  }
  @Override public PageData<Person> members(Scope s,Filter f) {
    var p=params(s).addValue("limit",f.size()).addValue("offset",(f.page()-1)*f.size());
    String where="";
    if(f.keyword()!=null && !f.keyword().isEmpty()) {
      var matched=platform.personsMatching(s.tenantNo(),f.keyword());
      if(matched.isEmpty())return PageData.of(List.of(),0,f.page(),f.size());
      p.addValue("persons",matched);where+=" AND first.person_no IN (:persons)";
    } else {
      var deleted=platform.deletedPersons(s.tenantNo());
      if(!deleted.isEmpty()){p.addValue("deleted",deleted);where+=" AND first.person_no NOT IN (:deleted)";}
    }
    if(f.storeNo()!=null || f.brandNo()!=null) {
      var stores=platform.storesMatching(platformScope(s),f.storeNo(),f.brandNo());
      if(stores.isEmpty())return PageData.of(List.of(),0,f.page(),f.size());
      p.addValue("stores",stores);
      where+=" AND EXISTS (SELECT 1 FROM mbr_member m WHERE m.person_no=first.person_no AND "+memberWhere(s,"m")+" AND "+memberRelations("m")+")";
    }
    long total=jdbc.queryForObject("SELECT COUNT(*)"+firstMembership(s)+where,p,Long.class);
    var page=firstMemberships(s,where,p," ORDER BY first.joined_at DESC,first.person_no DESC LIMIT :limit OFFSET :offset");
    return PageData.of(persons(s,page),total,f.page(),f.size());
  }
  @Override public PersonDetail member(Scope s,String personNo) {
    var p=params(s).addValue("person",personNo);
    var person=persons(s,firstMemberships(s," AND first.person_no=:person",p,""));
    if(person.isEmpty())throw BizException.of(ErrorCode.NOT_FOUND);
    var ps=platformScope(s);
    record Row(String memberNo,String entityNo,String status,long joinedAt,String source,String firstStoreNo){}
    var rows=jdbc.query("SELECT m.member_no,m.entity_no,m.status,m.joined_at,m.source,m.first_store_no FROM mbr_member m WHERE m.person_no=:person AND "+memberWhere(s,"m")+" ORDER BY m.joined_at,m.id",p,
      (r,n)->new Row(r.getString("member_no"),r.getString("entity_no"),r.getString("status"),r.getLong("joined_at"),r.getString("source"),r.getString("first_store_no")));
    var entityNames=platform.entityNames(s.tenantNo(),rows.stream().map(Row::entityNo).filter(Objects::nonNull).distinct().toList());
    var srcRows=jdbc.query("SELECT src.source_no,src.member_no,src.source_type,src.store_no,src.occurred_at,src.is_first FROM mbr_member_source src JOIN mbr_member m ON m.tenant_no=src.tenant_no AND m.member_no=src.member_no AND m.entity_no=src.entity_no WHERE m.person_no=:person AND "+memberWhere(s,"m")+" AND src.deleted=0 ORDER BY src.occurred_at,src.id",p,
      (r,n)->new Source(r.getString("source_no"),r.getString("member_no"),r.getString("source_type"),r.getString("store_no"),null,r.getLong("occurred_at"),r.getBoolean("is_first")));
    var named=new HashSet<String>();rows.forEach(m->{if(m.firstStoreNo()!=null)named.add(m.firstStoreNo());});srcRows.forEach(x->{if(x.storeNo()!=null)named.add(x.storeNo());});
    var storeNames=platform.storeNames(ps,named);
    var memberships=rows.stream().map(m->new Membership(m.memberNo(),m.entityNo(),entityNames.get(m.entityNo()),m.status(),m.joinedAt(),m.source(),m.firstStoreNo(),m.firstStoreNo()==null?null:storeNames.get(m.firstStoreNo()))).toList();
    var sources=srcRows.stream().map(x->new Source(x.sourceNo(),x.memberNo(),x.sourceType(),x.storeNo(),x.storeNo()==null?null:storeNames.get(x.storeNo()),x.occurredAt(),x.first())).toList();
    return new PersonDetail(person.getFirst(),memberships,sources,platform.storeLinks(ps,relatedStores(s,p)).stream().map(CustomerReportingServiceImpl::link).toList());
  }
  /** 这个人可见的会员身份关联到的门店号：首店、店内档案（主体对得上）、来源。 */
  private Set<String> relatedStores(Scope s, MapSqlParameterSource p) {
    var stores=new HashSet<String>();
    String visible="m.person_no=:person AND "+memberWhere(s,"m");
    stores.addAll(jdbc.queryForList("SELECT m.first_store_no FROM mbr_member m WHERE "+visible+" AND m.first_store_no IS NOT NULL",p,String.class));
    stores.addAll(jdbc.queryForList("SELECT rel.store_no FROM mbr_member m JOIN mbr_store_profile rel ON rel.tenant_no=m.tenant_no COLLATE utf8mb4_unicode_520_ci AND rel.member_no=m.member_no COLLATE utf8mb4_unicode_520_ci AND rel.entity_no=m.entity_no COLLATE utf8mb4_unicode_520_ci AND rel.deleted=0 WHERE "+visible,p,String.class));
    stores.addAll(jdbc.queryForList("SELECT src.store_no FROM mbr_member m JOIN mbr_member_source src ON src.tenant_no=m.tenant_no AND src.member_no=m.member_no AND src.entity_no=m.entity_no AND src.deleted=0 WHERE "+visible,p,String.class));
    stores.remove(null);
    return stores;
  }
}
