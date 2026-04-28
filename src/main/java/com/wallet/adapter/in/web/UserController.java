package com.wallet.adapter.in.web;

import com.wallet.application.command.user.CreateUserCommandHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {

    private final CreateUserCommandHandler createUserCommandHandler;

    public UserController(CreateUserCommandHandler createUserCommandHandler) {
        this.createUserCommandHandler = createUserCommandHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateUserResponse createUser() {
        CreateUserCommandHandler.CreateUserResult createUserResult = createUserCommandHandler.createUser();
        return new CreateUserResponse(createUserResult.userId().toString());
    }

    public record CreateUserResponse(
        @Schema(example = "550e8400-e29b-41d4-a716-446655440001", description = "New user id (UUID)")
        String userId
    ) {}
}
