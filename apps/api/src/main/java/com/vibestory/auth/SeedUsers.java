package com.vibestory.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeedUsers implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;

    public SeedUsers(UserRepository users, PasswordEncoder encoder,
                     @Value("${vibestory.seed.email}") String email,
                     @Value("${vibestory.seed.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (email.isEmpty() && password.isEmpty()) return;
        if (!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 254 ||
                password.length() < 6 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("SEED_EMAIL 必须是邮箱，SEED_PASSWORD 必须为至少 6 字符、至多 72 UTF-8 字节");
        }
        // Restarting never resets an existing account's password.
        if (users.findByEmail(email).isEmpty()) users.save(new User(email, encoder.encode(password)));
    }
}
