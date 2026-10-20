package ai.neargo.shop.member.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** New credential issuance is opt-in; lookup of already issued references retains its original deadline. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "shop.member-code")
public class MemberCodeProperties {
    private boolean enabled;
}
