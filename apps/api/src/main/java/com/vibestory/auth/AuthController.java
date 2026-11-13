package com.vibestory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import com.vibestory.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;
    private final AccountRegistration registration;

    public AuthController(AuthenticationManager authentication, SecurityContextRepository contexts,
                          AccountRegistration registration) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.registration = registration;
    }

    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(min = 6, max = 72) String password) {}
    public record Me(String id, String email) {}
    public record Csrf(String headerName, String token) {}

    @GetMapping("/api/auth/csrf")
    Csrf csrf(@io.swagger.v3.oas.annotations.Parameter(hidden = true) CsrfToken token) {
        return new Csrf(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/api/auth/login")
    Me login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        validatePasswordBytes(body.password());
        return startSession(body, request, response);
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    Me register(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        validatePasswordBytes(body.password());
        registration.register(body.email(), body.password());
        return startSession(body, request, response);
    }

    private static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("密码不可超过 72 UTF-8 字节");
    }

    private Me startSession(Login body, HttpServletRequest request, HttpServletResponse response) {
        var auth = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response);
        // Rotate the anonymous CSRF token along with the session identity.
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return me((Viewer) auth.getPrincipal());
    }

    @GetMapping("/api/me")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
    Me me(@AuthenticationPrincipal Viewer viewer) { return new Me(Long.toString(viewer.id()), viewer.email()); }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
    void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        var handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(contexts);
        handler.logout(request, response, authentication);
        new CookieClearingLogoutHandler("JSESSIONID").logout(request, response, authentication);
    }
}
