package com.egov.idgenerator.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {

    public record AdminUser(String username, String passwordHash, boolean enabled) {
        @Override
        public String toString() { // never print the hash
            return "AdminUser[username=" + username + "]";
        }
    }

    private final JdbcTemplate jdbc;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.query("SELECT username, password_hash, enabled FROM admin_users WHERE username = ?",
                (rs, i) -> new AdminUser(rs.getString(1), rs.getString(2), rs.getBoolean(3)), username)
                .stream().findFirst();
    }

    /** @return true if inserted, false if the username already existed (never overwrites). */
    public boolean createIfAbsent(String username, String passwordHash) {
        return jdbc.update("INSERT INTO admin_users (username, password_hash) VALUES (?, ?) ON CONFLICT DO NOTHING",
                username, passwordHash) == 1;
    }
}
