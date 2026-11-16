package lk.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;

/** v1.0 task 9 (SPEC "Swagger off"): no OpenAPI/Swagger library in the build and no such page served. */
class NoSwaggerTest extends ApiTest {

    @Test
    void noSwaggerOnTheClasspathOrOnTheWire() throws Exception {
        for (String cls : new String[] {"org.springdoc.core.configuration.SpringDocConfiguration", "springfox.documentation.spring.web.plugins.Docket",
                "io.swagger.v3.oas.models.OpenAPI"}) {
            assertThat(isPresent(cls)).as(cls).isFalse();
        }
        for (String path : new String[] {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v2/api-docs"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isNotEqualTo(200);
        }
    }

    private static boolean isPresent(String cls) {
        try {
            Class.forName(cls, false, NoSwaggerTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
