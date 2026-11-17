package lk.wms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lk.wms.ApiTest;
import lk.wms.auth.api.AuthCookies;
import lk.wms.common.mail.Mailer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** v2.0 task 2: self-service sign-up; nothing exists until the email is confirmed; no account enumeration (SEC-07, SEC-20, SEC-60). */
class SignupTest extends ApiTest {

    @MockitoBean
    Mailer mailer;

    private ResultActions signup(String ip, String company, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/signup").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyName\":\"" + company + "\",\"fullName\":\"Nimal Perera\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions verifyLink(String token) throws Exception {
        return mvc.perform(post("/api/v1/signup/verify").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"));
    }

    /** The link from the last email sent to this address. */
    private String tokenSentTo(String email) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer, org.mockito.Mockito.atLeastOnce()).send(eq(email), anyString(), text.capture());
        Matcher m = Pattern.compile("/verify#(\\S+)").matcher(text.getValue());
        assertThat(m.find()).as("link in: %s", text.getValue()).isTrue();
        return m.group(1);
    }

    @Test
    void signUpConfirmAndStartWorking() throws Exception {
        String email = "owner-" + System.nanoTime() + "@kandyfoods.lk";
        String company = "Kandy Foods " + System.nanoTime(); // other tests also make a Kandy Foods
        signup("10.1.0.1", company, email.toUpperCase(), "short").andExpect(status().isBadRequest()); // password rules
        signup("10.1.0.1", company, email.toUpperCase(), "a-long-enough-pass").andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email)).isZero(); // nothing yet
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE name = ?", Integer.class, company)).isZero();
        String first = tokenSentTo(email);

        // signing up again replaces the pending one; the first link no longer works
        clearInvocations(mailer);
        signup("10.1.0.1", company, email, "a-long-enough-pass").andExpect(status().isAccepted());
        String token = tokenSentTo(email);
        verifyLink(first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SIGNUP_LINK_INVALID"));

        // confirming makes the company on the sign-up plan and signs its admin in
        MvcResult ok = verifyLink(token).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email)).andReturn();
        Cookie access = ok.getResponse().getCookie(AuthCookies.ACCESS);
        assertThat(access).isNotNull();
        mvc.perform(get("/api/v1/setup").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].key").value("WAREHOUSE")).andExpect(jsonPath("$.steps[0].done").value(false))
                .andExpect(jsonPath("$.complete").value(false));
        assertThat(jdbc.queryForObject("SELECT plan_code FROM tenant t JOIN app_user u ON u.tenant_id = t.id WHERE u.email = ?", String.class, email))
                .isEqualTo("PILOT");
        assertThat(jdbc.queryForObject("SELECT max_warehouses FROM tenant t JOIN app_user u ON u.tenant_id = t.id WHERE u.email = ?", Integer.class, email))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT r.code FROM app_user u JOIN user_role ur ON ur.user_id = u.id JOIN role r ON r.id = ur.role_id WHERE u.email = ?",
                String.class, email)).isEqualTo("TENANT_ADMIN");
        // the warehouse step ticks as soon as one exists
        mvc.perform(post("/api/v1/warehouses").with(csrf()).cookie(access).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/setup").cookie(access)).andExpect(jsonPath("$.steps[0].done").value(true)).andExpect(jsonPath("$.warehouseId").isNotEmpty());

        // the link works once; the password chosen at sign-up signs in
        verifyLink(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SIGNUP_LINK_INVALID"));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"a-long-enough-pass\"}")).andExpect(status().isOk());

        // the same email again: same answer, but that address gets "you already have an account", and nothing is stored
        clearInvocations(mailer);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM signup_request", Integer.class);
        signup("10.1.0.2", "Copycat", email, "another-long-pass").andExpect(status().isAccepted());
        verify(mailer).send(eq(email), eq("Your 3D WMS account"), contains("already has an account"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signup_request", Integer.class)).isEqualTo(before);

        // an expired link and a made-up one: the same generic answer
        String late = "late-" + System.nanoTime() + "@x.lk";
        signup("10.1.0.3", "Late Ltd", late, "a-long-enough-pass").andExpect(status().isAccepted());
        String lateToken = tokenSentTo(late);
        jdbc.update("UPDATE signup_request SET expires_at = NOW(3) - INTERVAL 1 MINUTE WHERE email = ?", late);
        verifyLink(lateToken).andExpect(jsonPath("$.code").value("SIGNUP_LINK_INVALID"));
        verifyLink("nonsense").andExpect(jsonPath("$.code").value("SIGNUP_LINK_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, late)).isZero();
    }

    @Test
    void fiveSignUpsPerAddressPerHour() throws Exception {
        for (int i = 0; i < 5; i++) {
            signup("10.2.0.9", "Spam " + i, "spam" + i + "-" + System.nanoTime() + "@x.lk", "a-long-enough-pass").andExpect(status().isAccepted());
        }
        signup("10.2.0.9", "Spam 6", "spam6-" + System.nanoTime() + "@x.lk", "a-long-enough-pass").andExpect(status().isTooManyRequests());
        signup("10.2.0.10", "Elsewhere", "ok-" + System.nanoTime() + "@x.lk", "a-long-enough-pass").andExpect(status().isAccepted());
        verify(mailer, never()).send(contains("spam6"), anyString(), anyString());
    }
}
