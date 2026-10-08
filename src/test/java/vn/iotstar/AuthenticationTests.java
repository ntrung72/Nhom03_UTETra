package vn.iotstar;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.time.LocalDateTime;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpSession;
import vn.iotstar.entity.OtpToken;
import vn.iotstar.entity.DomainEnums.OtpPurpose;
import vn.iotstar.repository.OtpTokenRepository;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import vn.iotstar.dto.WebForms.RegisterForm;
import vn.iotstar.entity.User;
import vn.iotstar.repository.UserRepository;
import vn.iotstar.security.CustomUserDetailsService;
import vn.iotstar.security.JwtService;
import vn.iotstar.service.AuthService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AuthenticationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired CustomUserDetailsService details;
    @Autowired JwtService jwt;
    @Autowired AuthService auth;
    @Autowired OtpTokenRepository tokens;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authenticationPagesRenderWithoutAuthentication() throws Exception {
        for (String path : new String[]{"/login", "/register", "/forgot-password", "/verify-account?email=view@utetra.test", "/reset-password?email=view@utetra.test"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void anonymousAndMalformedTokenCannotReadPrivateApi() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid.jwt.token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void validJwtCanAccessCurrentUserButTamperedSignatureCannot() throws Exception {
        User user = user();
        String token = jwt.generateToken(details.loadUserByUsername(user.getUsername()));
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(user.getUsername()));
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + tampered))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIssuedBeforeAccountIsLockedCannotAuthenticate() throws Exception {
        User user = user();
        String token = jwt.generateToken(details.loadUserByUsername(user.getUsername()));
        user.setLocked(true); users.saveAndFlush(user);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenIssuedBeforeAccountIsDisabledCannotAuthenticate() throws Exception {
        User user = user();
        String token = jwt.generateToken(details.loadUserByUsername(user.getUsername()));
        user.setEnabled(false); users.saveAndFlush(user);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void loginApiIssuesBearerTokenForVerifiedAccount() throws Exception {
        User user = user();
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"Password123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("Bearer"))
            .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void registrationHashesPasswordAndWaitsForOtpVerification() {
        String suffix = UUID.randomUUID().toString();
        RegisterForm form = new RegisterForm(); form.setUsername(suffix); form.setFullName("Khách kiểm thử");
        form.setEmail(suffix + "@utetra.test"); form.setPassword("Password123"); form.setConfirmPassword("Password123");
        User user = auth.register(form);
        assertFalse(user.isEnabled()); assertTrue(encoder.matches("Password123", user.getPasswordHash()));
        assertNotEquals("Password123", user.getPasswordHash());
    }

    @Test
    void webLoginRemembersAccountAndLogoutClearsSession() throws Exception {
        User user = user();
        var result = mvc.perform(post("/login").with(csrf()).param("username", user.getEmail())
                .param("password", "Password123").param("remember-me", "on"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?success")).andReturn();
        Cookie remember = result.getResponse().getCookie("remember-me");
        assertNotNull(remember);
        assertTrue(remember.getMaxAge() > 0);
        mvc.perform(get("/login").cookie(remember)).andExpect(status().isOk())
            .andExpect(model().attributeExists("currentUser"));
        mvc.perform(get("/api/me").cookie(remember)).andExpect(status().isUnauthorized());
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?logout"));
        assertTrue(session.isInvalid());
    }

    @Test
    void apiLoginWithMultibytePasswordOverBcryptLimitReturnsUnauthorized() throws Exception {
        User user = user();
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"" + "ệ".repeat(25) + "\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationRejectsPasswordsOverBcryptByteLimitWithoutSavingUser() {
        RegisterForm form = new RegisterForm();
        form.setUsername(UUID.randomUUID().toString());
        form.setEmail(form.getUsername() + "@utetra.test");
        form.setFullName("Khách kiểm thử");
        form.setPassword("ệ".repeat(25));
        form.setConfirmPassword(form.getPassword());
        assertThrows(IllegalArgumentException.class, () -> auth.register(form));
        assertTrue(users.findByEmailIgnoreCase(form.getEmail()).isEmpty());
    }

    @Test
    void swaggerDescribesOnlyInitialAuthenticationApiAndBearerScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
            .andExpect(jsonPath("$.paths['/api/me'].get.security[0].bearerAuth").isArray())
            .andExpect(jsonPath("$.paths['/api/orders']").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/cart']").doesNotExist());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void webMutationsRequireCsrfToken() throws Exception {
        mvc.perform(post("/register")).andExpect(status().isForbidden());
        mvc.perform(post("/login")).andExpect(status().isForbidden());
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
    }

    @Test
    void invalidRegistrationNeverCreatesAnAccount() throws Exception {
        String email = UUID.randomUUID() + "@utetra.test";
        mvc.perform(post("/register").with(csrf()).param("username", "bad username")
                .param("email", email).param("fullName", "Khách kiểm thử")
                .param("password", "short").param("confirmPassword", "short"))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "username", "password"));
        assertTrue(users.findByEmailIgnoreCase(email).isEmpty());
    }

    @Test
    void registrationRejectsDuplicateUsernameEmailAndPasswordMismatch() {
        User existing = user();
        RegisterForm form = new RegisterForm();
        form.setUsername(existing.getUsername()); form.setEmail(UUID.randomUUID() + "@utetra.test");
        form.setFullName("Khách kiểm thử"); form.setPassword("Password123"); form.setConfirmPassword("Password123");
        assertThrows(IllegalArgumentException.class, () -> auth.register(form));
        form.setUsername(UUID.randomUUID().toString()); form.setEmail(existing.getEmail().toUpperCase());
        assertThrows(IllegalArgumentException.class, () -> auth.register(form));
        form.setEmail(UUID.randomUUID() + "@utetra.test"); form.setConfirmPassword("Different123");
        assertThrows(IllegalArgumentException.class, () -> auth.register(form));
        assertTrue(users.findByEmailIgnoreCase(form.getEmail()).isEmpty());
    }

    @Test
    void passwordResetConsumesOtpAndOnlyNewPasswordWorks() throws Exception {
        User user = user();
        OtpToken token = new OtpToken(); token.setEmail(user.getEmail());
        token.setPurpose(OtpPurpose.RESET_PASSWORD); token.setCodeHash(encoder.encode("123456"));
        token.setExpiresAt(LocalDateTime.now().plusMinutes(5)); tokens.saveAndFlush(token);
        auth.resetPassword(user.getEmail(), "123456", "NewPassword123", "NewPassword123");
        assertTrue(tokens.findById(token.getId()).orElseThrow().isUsed());
        assertTrue(encoder.matches("NewPassword123", users.findById(user.getId()).orElseThrow().getPasswordHash()));
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"Password123\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"NewPassword123\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void unverifiedAndLockedAccountsCannotLoginAndBlankCredentialsAreInvalid() throws Exception {
        User user = user(); user.setEnabled(false); users.saveAndFlush(user);
        String request = "{\"username\":\"" + user.getUsername() + "\",\"password\":\"Password123\"}";
        mvc.perform(post("/api/auth/login").contentType("application/json").content(request))
            .andExpect(status().isUnauthorized());
        user.setEnabled(true); user.setLocked(true); users.saveAndFlush(user);
        mvc.perform(post("/api/auth/login").contentType("application/json").content(request))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"username\":\"\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    private User user() {
        String suffix = UUID.randomUUID().toString();
        User user = new User(); user.setUsername(suffix); user.setEmail(suffix + "@utetra.test");
        user.setFullName("Khách kiểm thử"); user.setPasswordHash(encoder.encode("Password123")); user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
