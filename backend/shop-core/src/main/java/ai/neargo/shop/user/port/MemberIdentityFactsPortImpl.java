package ai.neargo.shop.user.port;
import ai.neargo.shop.spi.user.MemberIdentityFactsPort;import ai.neargo.shop.user.entity.UsrPerson;import ai.neargo.shop.user.service.PersonService;import ai.neargo.shop.common.*;import org.springframework.stereotype.Component;import java.util.*;
@Component
public class MemberIdentityFactsPortImpl implements MemberIdentityFactsPort {
 private final PersonService people;public MemberIdentityFactsPortImpl(PersonService people){this.people=people;}
 @Override public Optional<Identity> find(String tenant,String customer){return people.find(customer).filter(p->tenant.equals(p.getTenantNo())&&UsrPerson.ACTIVE.equals(p.getStatus())).map(p->{boolean consistent=p.getUserNo()==null||people.findByUser(p.getUserNo()).filter(c->tenant.equals(c.getTenantNo())&&p.getPersonNo().equals(c.getPersonNo())).isPresent();return new Identity(p.getPersonNo(),p.getUserNo(),people.maskedPhone(p),consistent);});}
 @Override public Identity resolvePhone(String tenant,String phone){if(phone==null||!phone.matches("1[3-9][0-9]{9}"))throw BizException.of(ErrorCode.BAD_REQUEST);var p=people.resolveOrCreateByPhone(phone);return find(tenant,p.getPersonNo()).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));}
 @Override public List<Identity> match(String tenant,List<String> allowed,String keyword){if(allowed.size()>200)throw BizException.of(ErrorCode.BAD_REQUEST);if(!keyword.matches("[0-9]+"))return List.of();return people.matchPhones(allowed,keyword).stream().map(p->find(tenant,p.getPersonNo())).flatMap(Optional::stream).toList();}
}
