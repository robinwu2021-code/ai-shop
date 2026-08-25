package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.PersonPort;
import ai.neargo.shop.user.entity.UsrPerson;
import ai.neargo.shop.user.service.PersonService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link PersonPort} 的实现。**只做转换，不含逻辑** —— 判断都在 {@link PersonService} 里。
 *
 * <p>视图里没有完整手机号：需要它的只有平台申诉处置，那条路要二次确认与审计日志，
 * 不从这个 Port 走。
 */
@Component
public class PersonPortImpl implements PersonPort {

    private final PersonService personService;

    public PersonPortImpl(PersonService personService) {
        this.personService = personService;
    }

    @Override
    public PersonView resolveOrCreateByPhone(String phone) {
        return view(personService.resolveOrCreateByPhone(phone));
    }

    @Override
    public Optional<PersonView> findByUser(String userNo) {
        return personService.findByUser(userNo).map(PersonPortImpl::view);
    }

    @Override
    public Optional<PersonView> find(String personNo) {
        return personService.find(personNo).map(PersonPortImpl::view);
    }

    private static PersonView view(UsrPerson p) {
        return new PersonView(p.getPersonNo(), p.getPhoneTail(), p.getUserNo());
    }
}
