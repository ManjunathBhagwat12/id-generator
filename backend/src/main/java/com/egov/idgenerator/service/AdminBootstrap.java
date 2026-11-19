package com.egov.idgenerator.service;

import com.egov.idgenerator.config.IdGenProperties;
import com.egov.idgenerator.repository.AdminUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the initial admin from ADMIN_USERNAME / ADMIN_PASSWORD if (and only if) that username does not exist.
 * Existing admins are never modified, so changing the env var later does not silently reset a password.
 * The password is hashed with BCrypt before storage and is never logged.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final IdGenProperties props;
    private final AdminUserRepository admins;
    private final PasswordEncoder encoder;

    public AdminBootstrap(IdGenProperties props, AdminUserRepository admins, PasswordEncoder encoder) {
        this.props = props;
        this.admins = admins;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = props.admin() == null ? null : props.admin().username();
        String password = props.admin() == null ? null : props.admin().password();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("ADMIN_USERNAME / ADMIN_PASSWORD not set: no initial admin created");
            return;
        }
        if (admins.findByUsername(username.trim()).isPresent()) {
            log.info("Admin '{}' already exists; leaving it unchanged", username.trim());
            return;
        }
        if (password.length() < 8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("ADMIN_PASSWORD must be 8 to 72 bytes long");
        }
        boolean created = admins.createIfAbsent(username.trim(), encoder.encode(password));
        log.info(created ? "Created initial admin '{}'" : "Admin '{}' was created concurrently", username.trim());
    }
}
