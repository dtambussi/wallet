package com.wallet.application.command.user;

import com.wallet.application.port.out.UserRepository;
import com.wallet.infrastructure.id.UuidV7Generator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateUserCommandHandler {

    private final UserRepository userRepository;

    public CreateUserCommandHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public CreateUserResult createUser() {
        UUID userId = UuidV7Generator.next();
        userRepository.insertUser(userId);
        return new CreateUserResult.Created(userId);
    }

    public sealed interface CreateUserResult permits CreateUserResult.Created {
        UUID userId();

        record Created(UUID userId) implements CreateUserResult {}
    }
}
