package com.wallet.adapter.out.persistence;

import com.wallet.application.port.out.UserRepository;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertUser(UUID userId) {
        jdbc.update(
            "INSERT INTO users (id) VALUES (:id)",
            new MapSqlParameterSource("id", userId)
        );
    }

    @Override
    public boolean existsById(UUID userId) {
        Boolean userExists = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id = :id)",
            new MapSqlParameterSource("id", userId),
            Boolean.class
        );
        return Boolean.TRUE.equals(userExists);
    }
}
