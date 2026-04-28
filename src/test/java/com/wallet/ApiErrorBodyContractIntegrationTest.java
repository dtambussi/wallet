package com.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
/*
  Contract checks for cross-cutting web behavior:
  - standard error body shape/codes from shared exception handling
  - request correlation header from RequestIdMdcFilter
  The requests use user endpoints, but the assertions verify infrastructure applied to all API paths.
 */
@DisplayName("API Error Contract Test")
class ApiErrorBodyContractIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("Should return validation error body shape for invalid amount")
    void validationErrorBodyShape() throws Exception {
        String userId = createUser();
        MvcResult response = mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": \"0\", \"currency\": \"USD\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
        JsonNode errorBody = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(errorBody.get("error").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(errorBody.has("message")).isTrue();
    }

    @Test
    @DisplayName("Should use VALIDATION_ERROR for malformed JSON")
    void malformedJsonUsesValidationErrorCode() throws Exception {
        String userId = createUser();
        String errorResponseBody = mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
            .andReturn().getResponse().getContentAsString();
        JsonNode errorResponse = objectMapper.readTree(errorResponseBody);
        assertThat(errorResponse.get("message").asText()).contains("Malformed");
    }

    @Test
    @DisplayName("Should use IDEMPOTENCY_KEY_CONFLICT error code on key reuse mismatch")
    void idempotencyConflictUsesContractErrorCode() throws Exception {
        String userId = createUser();
        String idempotencyKey = "k-repeat";
        mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content("{\"amount\": \"1.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isCreated());
        String errorResponseBody = mockMvc.perform(post("/users/" + userId + "/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content("{\"amount\": \"2.00\", \"currency\": \"USD\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"))
            .andReturn().getResponse().getContentAsString();
        JsonNode errorResponse = objectMapper.readTree(errorResponseBody);
        assertThat(errorResponse.get("message").asText()).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("Should return X-Request-Id header")
    void xRequestIdHeaderPresent() throws Exception {
        MvcResult response = mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andReturn();
        assertThat(response.getResponse().getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    @DisplayName("Should use VALIDATION_ERROR for invalid path user id")
    void invalidPathUserIdUsesValidationErrorCode() throws Exception {
        String errorResponseBody = mockMvc.perform(get("/users/not-a-uuid/balances"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        JsonNode errorResponse = objectMapper.readTree(errorResponseBody);
        assertThat(errorResponse.get("error").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(errorResponse.get("message").asText()).contains("userId");
    }

    @Test
    @DisplayName("Should return 404 NOT_FOUND for unknown route")
    void unknownRouteReturnsNotFound() throws Exception {
        String errorResponseBody = mockMvc.perform(get("/does-not-exist"))
            .andExpect(status().isNotFound())
            .andReturn().getResponse().getContentAsString();
        JsonNode errorResponse = objectMapper.readTree(errorResponseBody);
        assertThat(errorResponse.get("error").asText()).isEqualTo("NOT_FOUND");
    }

    private String createUser() throws Exception {
        String createUserResponseBody = mockMvc.perform(post("/users"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(createUserResponseBody).get("userId").asText();
    }
}
