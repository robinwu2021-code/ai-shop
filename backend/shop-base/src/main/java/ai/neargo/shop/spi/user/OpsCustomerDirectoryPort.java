package ai.neargo.shop.spi.user;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 运营端顾客浏览要的平台事实：C 端账号与人、B 端账号、主体、门店、品牌都在平台基础库。商户域实现、走平台句柄；
 * 门户的顾客浏览只调这里取平台一侧，会员一侧自己查再拼（TDD-平台基础库换表 第 1 步）。口径与原联表 SQL 逐条一致。
 *
 * <p>{@link Scope}：运营人员的数据范围。{@code all} 看全部；否则只看 {@code entities} 里的主体，空集合什么都看不到。
 */
public interface OpsCustomerDirectoryPort {

    record Scope(String tenantNo, boolean all, Set<String> entities) {
        public Scope {
            entities = Set.copyOf(entities);
        }
    }

    /** 经营主体：存在、启用、所有人账号正常；{@code visible} 表示当前范围能不能看 */
    record OperatingEntityFact(String operatingEntityNo, String name, String market, String status, long version,
            String legalSubjectNo, String ownerUserNo, boolean visible) {
    }

    record Registration(String userNo, String nickname, String status, String market, long registeredAt,
            long entityCount, long storeCount) {
    }

    /** 注册列表：一页记录、总数、没有门店的人数、{@code from} 起每天的注册数（只含有注册的日子，yyyy-MM-dd） */
    record Registrations(List<Registration> records, long total, long withoutStores, Map<String, Long> dailyFrom) {
    }

    record StoreLink(String storeNo, String name, String entityNo, String brandNo, String brandName) {
    }

    record Brand(String brandNo, String name, String status, String managementNo, String managementName,
            List<StoreLink> stores) {
    }

    record Brands(List<Brand> records, long total) {
    }

    record PersonFact(String personNo, String userNo, String phoneTail, String status) {
    }

    /** 经营主体；不存在、停用或所有人账号不正常为空 */
    Optional<OperatingEntityFact> operatingEntity(Scope scope, String operatingEntityNo);

    /** 范围内的注册账号（关键字匹配用户号或昵称），按注册时间倒序分页 */
    Registrations registrations(Scope scope, String keyword, long page, long size, LocalDate dailyFrom);

    /** 账号在范围内关联到的门店 */
    List<StoreLink> registrationStores(Scope scope, String userNo);

    /** 范围内有生效门店绑定的品牌（关键字匹配名称或品牌号），按品牌号分页，各带范围内的门店 */
    Brands brands(Scope scope, String keyword, long page, long size);

    /** 范围内未删的门店号，可按门店号、生效绑定的品牌号再筛（传空不筛） */
    Set<String> storesMatching(Scope scope, String storeNo, String brandNo);

    /** 范围内未删的门店（带生效绑定的品牌），按门店号排序 */
    List<StoreLink> storeLinks(Scope scope, Collection<String> storeNos);

    /** 范围内的门店名（含已删的门店，历史来源要显示名字）；范围外的不给 */
    Map<String, String> storeNames(Scope scope, Collection<String> storeNos);

    /** 主体名（含已删） */
    Map<String, String> entityNames(String tenantNo, Collection<String> entityNos);

    /** 人号或手机尾号含关键字的未删的人 */
    Set<String> personsMatching(String tenantNo, String keyword);

    /** 已删的人 */
    Set<String> deletedPersons(String tenantNo);

    /** 未删的人，按人号 */
    Map<String, PersonFact> persons(String tenantNo, Collection<String> personNos);
}
