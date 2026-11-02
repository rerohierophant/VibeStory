package com.vibestory.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return email -> users.findByEmail(email.strip().toLowerCase(Locale.ROOT))
            .map(user -> new Viewer(user.getId(), user.getEmail(), user.getPasswordHash()))
            .orElseThrow(() -> new UsernameNotFoundException("邮箱或密码错误"));
    }

    @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts, ObjectMapper json)
            throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(contexts))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/login", "/v3/api-docs/**",
                    "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) ->
                    writeProblem(json, response, HttpStatus.UNAUTHORIZED, "请先登录"))
                .accessDeniedHandler((request, response, error) ->
                    writeProblem(json, response, HttpStatus.FORBIDDEN, "请求缺少有效的 CSRF 令牌或权限")))
            .logout(logout -> logout.disable()) // AuthController owns the documented JSON API endpoint.
            .build();
    }

    private void writeProblem(ObjectMapper json, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
