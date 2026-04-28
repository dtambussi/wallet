package com.wallet.application.command.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.wallet.application.port.out.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Create User Command Handler")
class CreateUserCommandHandlerTest {

    @Test
    @DisplayName("Should return created result with generated id")
    void createUserReturnsCreatedResultWithGeneratedId() {
        InMemoryUserRepository userRepository = new InMemoryUserRepository();
        CreateUserCommandHandler createUserCommandHandler = new CreateUserCommandHandler(userRepository);

        CreateUserCommandHandler.CreateUserResult createUserResult = createUserCommandHandler.createUser();

        assertThat(createUserResult).isInstanceOf(CreateUserCommandHandler.CreateUserResult.Created.class);
        assertThat(createUserResult.userId()).isNotNull();
        assertThat(userRepository.insertedId).isEqualTo(createUserResult.userId());
        assertThat(userRepository.existsById(createUserResult.userId())).isTrue();
    }

    private static final class InMemoryUserRepository implements UserRepository {
        private UUID insertedId;

        @Override
        public void insertUser(UUID id) {
            this.insertedId = id;
        }

        @Override
        public boolean existsById(UUID id) {
            return insertedId != null && insertedId.equals(id);
        }
    }
}
