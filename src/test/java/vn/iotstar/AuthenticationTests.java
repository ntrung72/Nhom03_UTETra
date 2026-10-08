package vn.iotstar;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpSession;
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
import vn.iotstar.service.AuthService;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class AuthenticationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthService auth;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void authenticationPagesRenderWithoutAuthentication() throws Exception {
        for (String path : new String[]{"/login", "/register", "/verify-account?email=view@utetra.test"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void unverifiedAndLockedAccountsCannotLoginThroughWeb() throws Exception {
        User user = user();
        user.setEnabled(false);
        users.saveAndFlush(user);
        mvc.perform(post("/login").with(csrf()).param("username", user.getUsername())
                .param("password", "Password123"))
            .andExpect(redirectedUrl("/login?error"));
        user.setEnabled(true);
        user.setLocked(true);
        users.saveAndFlush(user);
        mvc.perform(post("/login").with(csrf()).param("username", user.getEmail())
                .param("password", "Password123"))
            .andExpect(redirectedUrl("/login?error"));
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
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(post("/logout").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?logout"));
        assertTrue(session.isInvalid());
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

    private User user() {
        String suffix = UUID.randomUUID().toString();
        User user = new User(); user.setUsername(suffix); user.setEmail(suffix + "@utetra.test");
        user.setFullName("Khách kiểm thử"); user.setPasswordHash(encoder.encode("Password123")); user.setEnabled(true);
        return users.saveAndFlush(user);
    }
}
