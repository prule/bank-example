package com.bank.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.core.dto.TransferRequest;
import com.bank.core.service.TransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private TransferService transferService; // Mocks the business layer cleanly

  @Test
  void shouldReturn204WhenTransferIsSuccessful() throws Exception {
    // Arrange
    TransferRequest request = new TransferRequest();
    request.setSourceAccountNumber("ACC-1111");
    request.setDestinationAccountNumber("ACC-2222");
    request.setAmount(150.00);

    // Act & Assert
    mockMvc
        .perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    // Verify that the web payload mapped identically into the service invocation
    Mockito.verify(transferService, Mockito.times(1))
        .transferFunds(eq("ACC-1111"), eq("ACC-2222"), any(BigDecimal.class));
  }

  @Test
  void shouldReturn400BadRequestWhenPayloadIsMalformed() throws Exception {
    // Arrange: Missing destination account number entirely to trigger contract constraint violation
    TransferRequest badRequest = new TransferRequest();
    badRequest.setSourceAccountNumber("ACC-1111");
    badRequest.setAmount(50.00);

    // Act & Assert
    mockMvc
        .perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(badRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldReturn400WithErrorResponsePayloadWhenFundsAreInsufficient() throws Exception {
    // Arrange
    TransferRequest request = new TransferRequest();
    request.setSourceAccountNumber("ACC-1111");
    request.setDestinationAccountNumber("ACC-2222");
    request.setAmount(1000.00);

    // Force our mocked service boundary to trigger our domain validation exception
    Mockito.doThrow(new com.bank.core.domain.InsufficientFundsException("Insufficient funds."))
        .when(transferService)
        .transferFunds(eq("ACC-1111"), eq("ACC-2222"), any(BigDecimal.class));

    // Act & Assert
    mockMvc
        .perform(
            post("/api/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("INSUFFICIENT_FUNDS"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                .value("Insufficient funds."));
  }
}
