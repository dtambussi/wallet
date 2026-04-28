package com.wallet.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wallet.application.command.batch.BatchTransferCommandHandler;
import com.wallet.application.result.BatchTransferResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = BatchTransferController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("Batch Transfer Controller (WebMvc)")
class BatchTransferControllerWebMvcTest {

    private static final UUID SENDER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
    private static final UUID RECIPIENT_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440004");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchTransferCommandHandler batchTransferCommandHandler;

    @Test
    @DisplayName("POST /batch-transfers returns 201 with ledger entry id on success")
    void batchTransferReturnsLedgerEntryId() throws Exception {
        UUID ledgerEntryId = UUID.randomUUID();
        when(batchTransferCommandHandler.batchTransfer(any(), any(), any(), any()))
            .thenReturn(new BatchTransferResult.Success(ledgerEntryId));

        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"50.00","currency":"USD"}
                        ]}
                        """.formatted(RECIPIENT_ID))
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId.toString()));
    }

    @Test
    @DisplayName("POST /batch-transfers returns 404 when sender or recipient is unknown")
    void batchTransferReturnsNotFoundForMissingUser() throws Exception {
        when(batchTransferCommandHandler.batchTransfer(any(), any(), any(), any()))
            .thenReturn(new BatchTransferResult.UserNotFound("Sender not found"));

        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"50.00","currency":"USD"}
                        ]}
                        """.formatted(RECIPIENT_ID))
            )
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /batch-transfers returns 409 when sender has insufficient funds")
    void batchTransferReturnsConflictForInsufficientFunds() throws Exception {
        when(batchTransferCommandHandler.batchTransfer(any(), any(), any(), any()))
            .thenReturn(new BatchTransferResult.InsufficientFunds("Insufficient funds"));

        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"9999.00","currency":"USD"}
                        ]}
                        """.formatted(RECIPIENT_ID))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("POST /batch-transfers returns 400 when transfers list is empty")
    void batchTransferReturnsBadRequestForEmptyList() throws Exception {
        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transfers\":[]}")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /batch-transfers returns 400 when a transfer item has a null amount")
    void batchTransferReturnsBadRequestForNullAmount() throws Exception {
        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","currency":"USD"}
                        ]}
                        """.formatted(RECIPIENT_ID))
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /batch-transfers returns 400 when a transfer item has a null toUserId")
    void batchTransferReturnsBadRequestForNullToUserId() throws Exception {
        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transfers\":[{\"amount\":\"50.00\",\"currency\":\"USD\"}]}")
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /batch-transfers returns 409 when idempotency key conflicts")
    void batchTransferReturnsConflictForIdempotencyKeyConflict() throws Exception {
        when(batchTransferCommandHandler.batchTransfer(any(), any(), any(), any()))
            .thenReturn(new BatchTransferResult.IdempotencyKeyConflict("Key conflict"));

        mockMvc.perform(
                post("/users/{userId}/batch-transfers", SENDER_ID)
                    .header("Idempotency-Key", "existing-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"transfers":[
                          {"toUserId":"%s","amount":"50.00","currency":"USD"}
                        ]}
                        """.formatted(RECIPIENT_ID))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));
    }
}
