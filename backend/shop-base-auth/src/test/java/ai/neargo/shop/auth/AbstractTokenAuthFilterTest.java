package ai.neargo.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

class AbstractTokenAuthFilterTest {
    @ParameterizedTest
    @MethodSource("authorizationHeaders")
    void servletAndBoundHeaderEntriesPreserveTheSameBearerSemantics(String header, String expected) {
        var request = new MockHttpServletRequest();
        if (header != null) request.addHeader("Authorization", header);
        assertThat(AbstractTokenAuthFilter.bearer(header)).isEqualTo(expected);
        assertThat(AbstractTokenAuthFilter.bearer(request)).isEqualTo(expected);
    }

    private static Stream<Arguments> authorizationHeaders() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("", null),
                Arguments.of("Bearer", null),
                Arguments.of("Bearer ", null),
                Arguments.of("Bearer \t  ", null),
                Arguments.of("bearer token", null),
                Arguments.of("BEARER token", null),
                Arguments.of(" Bearer token", null),
                Arguments.of("Bearer\ttoken", null),
                Arguments.of("Basic token", null),
                Arguments.of("Bearer token", "token"),
                Arguments.of("Bearer   token\t ", "token"),
                Arguments.of("Bearer token inner", "token inner"));
    }
}
