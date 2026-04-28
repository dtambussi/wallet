package com.wallet.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wallet.application.command.transfer.TransferCommandHandler;
import com.wallet.application.result.TransferResult;
import com.wallet.infrastructure.config.OperationalSwitchPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = TransferController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("Transfer Controller (WebMvc)")
class TransferControllerWebMvcTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
    private static final UUID TO_USER_ID = UUID.fromString("660e8400-e29b-41d4-a716-446655440099");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferCommandHandler transferCommandHandler;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @MockitoBean
    private OperationalSwitchPolicy operationalSwitchPolicy;

    @BeforeEach
    void stubDefaults() {
        Counter counter = Mockito.mock(Counter.class);
        Mockito.when(meterRegistry.counter(any(String.class), any(String[].class))).thenReturn(counter);
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("POST /transfers with toCurrency returns 503 when FX killswitch is off")
    void crossCurrencyTransferReturnsServiceUnavailableWhenFxDisabled() throws Exception {
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(false);

        mockMvc.perform(
                post("/users/{userId}/transfers", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toUserId\":\"" + TO_USER_ID + "\",\"amount\":\"10.00\",\"currency\":\"USD\",\"toCurrency\":\"ARS\"}")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("SERVICE_DISABLED"));
    }

    @Test
    @DisplayName("POST /transfers without toCurrency proceeds even when FX killswitch is off")
    void sameCurrencyTransferIgnoresFxKillswitch() throws Exception {
        Mockito.when(operationalSwitchPolicy.isFxEnabled()).thenReturn(false);
        UUID ledgerEntryId = UUID.randomUUID();
        when(transferCommandHandler.transfer(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new TransferResult.Success(ledgerEntryId));

        mockMvc.perform(
                post("/users/{userId}/transfers", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"toUserId\":\"" + TO_USER_ID + "\",\"amount\":\"10.00\",\"currency\":\"USD\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.ledgerEntryId").value(ledgerEntryId.toString()));
    }
}
