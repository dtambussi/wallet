package com.wallet.application.port.out;

import java.util.UUID;

public interface UserRepository {

    void insertUser(UUID userId);

    boolean existsById(UUID userId);
}
