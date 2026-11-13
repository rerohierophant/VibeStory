package com.vibestory.auth;

import com.vibestory.common.ApiException;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.jooq.DSLContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountRegistration {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final DSLContext db;

    AccountRegistration(UserRepository users, PasswordEncoder passwords, DSLContext db) {
        this.users = users;
        this.passwords = passwords;
        this.db = db;
    }

    @Transactional
    public void register(String email, String password) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        try {
            users.saveAndFlush(new User(normalizedEmail, passwords.encode(password)));
        } catch (DataIntegrityViolationException error) {
            if (error.getCause() instanceof ConstraintViolationException violation
                    && "app_user_email_key".equals(violation.getConstraintName())) {
                throw ApiException.conflict("该邮箱已注册，请直接登录");
            }
            throw error;
        }
        // Legacy invitations could be addressed to an email before the account existed.
        db.execute("update team_invitation set status='REVOKED' where email=? and status='PENDING'", normalizedEmail);
    }
}
