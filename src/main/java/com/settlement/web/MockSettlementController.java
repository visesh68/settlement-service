package com.settlement.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.settlement.error.ApiException;
import com.settlement.service.MockSettlementService;
import com.settlement.service.MockSettlementService.SettlementResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated downstream provider, exposed over HTTP so the drainer really
 * does make a network call to it.
 *
 * <p>Its 500s are a feature, not a fault, and they are deliberately not wrapped
 * in the usual error handling: a 500 here means "the provider did not settle
 * this", which is exactly the signal the drainer needs.
 */
@RestController
public class MockSettlementController {

    private final MockSettlementService downstream;

    public MockSettlementController(MockSettlementService downstream) {
        this.downstream = downstream;
    }

    public record MockSettlementRequest(
            @NotNull UUID settlementKey,
            @NotNull UUID paymentId,
            String name,
            @NotNull @Positive Long amountMinor,
            String currency) {
    }

    @PostMapping("/mock-settlement")
    public ResponseEntity<Map<String, Object>> settle(@RequestBody MockSettlementRequest request) {
        if (request == null || request.settlementKey() == null || request.paymentId() == null
                || request.amountMinor() == null || request.amountMinor() <= 0) {
            throw ApiException.invalid("settlement_key, payment_id and a positive amount_minor are required");
        }

        return downstream.settle(request.settlementKey(), request.paymentId(), request.name(),
                        request.amountMinor(), request.currency() == null ? "INR" : request.currency())
                .map(MockSettlementController::ok)
                .orElseGet(MockSettlementController::injectedFailure);
    }

    private static ResponseEntity<Map<String, Object>> ok(SettlementResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "SETTLED");
        body.put("settlement_key", result.settlementKey());
        body.put("payment_id", result.paymentId());
        body.put("name", result.name());
        body.put("provider_ref", result.providerRef());
        body.put("duplicate", result.duplicate());
        body.put("delivery_count", result.deliveryCount());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> injectedFailure() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "FAILED");
        body.put("error", "provider_unavailable");
        body.put("message", "settlement provider is temporarily unavailable");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
