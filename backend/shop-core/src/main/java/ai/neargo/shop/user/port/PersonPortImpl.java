package ai.neargo.shop.user.port;

import ai.neargo.shop.spi.user.PersonPort;
import ai.neargo.shop.user.entity.UsrPerson;
import ai.neargo.shop.user.service.PersonService;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link PersonPort} 的实现。**只做转换，不含逻辑** —— 判断都在 {@link PersonService} 里。
 *
 * <p><b>视图里没有完整手机号</b>，它单独走 {@link PersonPort#revealPhone}：
 * 号码不是「人档的一个字段」，是一次要说明理由、要留审计的**动作**。
 *
 * <p>2026-08-30 之前这条路根本不从 Port 走 —— member 域直接注入了
 * {@code PhoneCrypto} 与 {@code PersonMapper}，把解密密钥拿进了自己域里。
 * 那是一处跨域依赖违例，而拦它的架构规则常年红着，没有给过任何信号。
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

    @Override
    public Optional<String> revealPhone(String personNo) {
        return personService.revealPhone(personNo);
    }

    @Override
    public java.util.List<String> findByPhoneTail(String phoneTail) {
        return personService.findByPhoneTail(phoneTail);
    }

    private static PersonView view(UsrPerson p) {
        return new PersonView(p.getPersonNo(), p.getPhoneTail(), p.getUserNo());
    }
}
