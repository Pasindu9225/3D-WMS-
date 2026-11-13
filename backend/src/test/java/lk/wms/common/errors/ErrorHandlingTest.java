package lk.wms.common.errors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import lk.wms.IntegrationTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import wmstest.ErrorProbeController;

/** SEC-09 (deny by default), SEC-63 (generic ProblemDetail errors), SEC-67 (404 for unknown routes). */

@AutoConfigureMockMvc
@Import(ErrorProbeController.class)
class ErrorHandlingTest extends IntegrationTest {

    private static final String ULID = "[0-9A-HJKMNP-TV-Z]{26}";

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(ULID)));
    }

    @Test
    void noLoginGives401ProblemDetailWithMatchingCorrelationId() throws Exception {
        MvcResult res = mvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andReturn();
        String cid = res.getResponse().getHeader("X-Correlation-Id");
        mvc.perform(get("/api/v1/anything"))
                .andExpect(jsonPath("$.correlationId").value(not(cid))); // fresh id per request
        org.junit.jupiter.api.Assertions.assertEquals(cid,
                com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.correlationId"));
    }

    @Test
    @WithMockUser
    void unknownRouteGives404() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(matchesPattern(ULID)));
    }

    @Test
    @WithMockUser
    void unexpectedErrorHidesInternals() throws Exception {
        mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Unexpected error"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("SECRET"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    @WithMockUser
    void businessErrorKeepsItsCodeAndStatus() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIN_FULL"))
                .andExpect(jsonPath("$.detail").value("Bin is full"));
    }

    @Test
    @WithMockUser
    void missingPermissionGives403() throws Exception {
        mvc.perform(get("/test/admin-only"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "users:manage")
    void withPermissionPasses() throws Exception {
        mvc.perform(get("/test/admin-only")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void validationErrorListsFieldsWithoutEchoingInput() throws Exception {
        mvc.perform(post("/test/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @WithMockUser
    void malformedJsonGives400() throws Exception {
        mvc.perform(post("/test/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(content().string(not(containsString("Exception"))));
    }
}
