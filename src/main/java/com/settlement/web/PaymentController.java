package com.settlement.web;

import java.util.List;
import java.util.UUID;

import com.settlement.service.PaymentService;
import com.settlement.service.PaymentService.Result;
import com.settlement.web.dto.AuthorizeRequest;
import com.settlement.web.dto.CaptureRequest;
import com.settlement.web.dto.PaymentView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Authorize. 201 on first use of the key, the stored 201 on replay, 409 on key reuse with a different body. */
    @PostMapping("/payments")
    public ResponseEntity<PaymentView> authorize(@Valid @RequestBody AuthorizeRequest request) {
        Result result = payments.authorize(request, CorrelationIdFilter.current());
        return respond(result);
    }

    /** Capture. 200 on success or replay; 409 if already captured, voided, or the key was reused. */
    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<PaymentView> capture(@PathVariable("id") UUID id,
                                               @Valid @RequestBody CaptureRequest request) {
        Result result = payments.capture(id, request, CorrelationIdFilter.current());
        return respond(result);
    }

    /**
     * Void an authorization, moving it to the terminal FAILED state. Present so
     * that "a failed payment cannot be captured" is a reachable, testable path
     * rather than a claim about a state nothing can enter.
     */
    @PostMapping("/payments/{id}/void")
    public ResponseEntity<PaymentView> voidAuthorization(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(payments.voidAuthorization(id));
    }

    @GetMapping("/payments/{id}")
    public PaymentView get(@PathVariable("id") UUID id) {
        return payments.get(id);
    }

    /** Backing list for the admin page. */
    @GetMapping("/payments")
    public List<PaymentView> recent(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return payments.recent(Math.clamp(limit, 1, 500));
    }

    private ResponseEntity<PaymentView> respond(Result result) {
        return ResponseEntity.status(result.status())
                .header(REPLAY_HEADER, Boolean.toString(result.replayed()))
                .body(result.view());
    }
}
