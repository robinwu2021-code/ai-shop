package ai.neargo.shop.portal.service;

import ai.neargo.shop.common.PageData;
import java.util.List;
import java.util.Set;

/** Read-only composition of registered accounts, real brand bindings and platform people. */
public interface CustomerReportingService {
  record Scope(String tenantNo, boolean all, Set<String> entities) {
    public Scope { entities = Set.copyOf(entities); }
  }
  record Filter(String keyword, String storeNo, String brandNo, long page, long size) {}
  record Registration(String userNo, String nickname, String status, String market,
                      long registeredAt, long entityCount, long storeCount) {}
  record Trend(String date, long count) {}
  record Registrations(PageData<Registration> page, long total, long withoutStores,
                       long newLast30Days, List<Trend> trend) {}
  record StoreLink(String storeNo, String name, String entityNo, String brandNo, String brandName) {}
  record Brand(String brandNo, String name, String status, String managementNo,
               String managementName, List<StoreLink> stores) {}
  record Person(String personNo, String userNo, String phoneTail, String status,
                long joinedAt, long membershipCount, String sourceType,
                String sourceStoreNo, String sourceStoreName) {}
  record Membership(String memberNo, String entityNo, String entityName, String status,
                    long joinedAt, String sourceType, String sourceStoreNo, String sourceStoreName) {}
  record Source(String sourceNo, String memberNo, String sourceType, String storeNo, String storeName, long occurredAt, boolean first) {}
  record PersonDetail(Person person, List<Membership> memberships, List<Source> sources, List<StoreLink> stores) {}
  record OperatingEntity(String operatingEntityId, String name, String market, String status,
                         long version, String legalSubjectId, String ownerUserNo, Set<String> allowedActions) {}
  OperatingEntity operatingEntity(Scope scope, String operatingEntityId);
  Registrations registrations(Scope scope, Filter filter);
  List<StoreLink> registrationStores(Scope scope, String userNo);
  PageData<Brand> brands(Scope scope, Filter filter);
  PageData<Person> members(Scope scope, Filter filter);
  PersonDetail member(Scope scope, String personNo);
}
