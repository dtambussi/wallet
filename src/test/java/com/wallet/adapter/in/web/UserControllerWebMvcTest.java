package com.wallet.adapter.in.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wallet.application.command.user.CreateUserCommandHandler;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UserController.class)
@DisplayName("User Controller (WebMvc)")
class UserControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateUserCommandHandler createUserCommandHandler;

    @Test
    @DisplayName("POST /users returns 201 and new user id")
    void createUserReturnsCreatedBody() throws Exception {
        UUID userId = UUID.randomUUID();
        when(createUserCommandHandler.createUser()).thenReturn(new CreateUserCommandHandler.CreateUserResult.Created(userId));

        mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value(userId.toString()));
    }
}
