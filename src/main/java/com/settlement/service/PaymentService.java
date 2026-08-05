package com.settlement.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlement.config.AppProperties;
import com.settlement.domain.IdempotencyRecord;
import com.settlement.domain.OutboxItem;
import com.settlement.domain.Payment;
import com.settlement.error.ApiException;
import com.settlement.error.ErrorCode;
import com.settlement.metrics.AppMetrics;
import com.settlement.repo.IdempotencyRepository;
import com.settlement.repo.OutboxRepository;
import com.settlement.repo.PaymentRepository;
import com.settlement.web.dto.AuthorizeRequest;
import com.settlement.web.dto.CaptureRequest;
import com.settlement.web.dto.PaymentView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Authorize and capture.
 *
 * <p>Two different idempotency mechanisms are used here, because the two
 * operations have different shapes:
 *
 * <ul>
 *   <li><b>authorize</b> has no pre-existing row to lock, so the idempotency
 *       table's primary key <em>is</em> the mutual exclusion: concurrent
 *       duplicates collide on it and the loser replays the winner's response.</li>
 *   <li><b>capture</b> mutates an existing payment, so it takes a row lock on
 *       that payment first. That serializes every concurrent capture of the
 *       payment — same key or not — and turns a distributed race into a queue.</li>
 * </ul>
 *
 * <p>Transactions are managed explicitly via {@link TransactionTemplate} rather
 * than {@code @Transactional}, because the authorize path has to run a second
 * transaction after the first one is doomed by a duplicate-key collision, which
 * declarative demarcation makes awkward to express.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final String SCOPE_AUTHORIZE = "authorize";
    static final String SCOPE_CAPTURE = "capture";

    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final IdempotencyRepository idempotency;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final AppMetrics metrics;
    private final int settlementMaxAttempts;

    public PaymentService(PaymentRepository payments,
                          OutboxRepository outbox,
                          IdempotencyRepository idempotency,
                          PlatformTransactionManager txManager,
                          ObjectMapper json,
                          AppMetrics metrics,
                          AppProperties props) {
        this.payments = payments;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.metrics = metrics;
        this.settlementMaxAttempts = props.settlement().maxAttempts();
    }

    /** HTTP status plus body. {@code replayed} is informational, for logs and headers. */
    public record Result(int status, PaymentView view, boolean replayed) {
    }

    // ------------------------------------------------------------------
    // authorize
    // ------------------------------------------------------------------

    public Result authorize(AuthorizeRequest request, String correlationId) {
        String currency = normalizeCurrency(request.currency());
        long amount = request.amount();
        String key = request.idempotencyKey();
        String fingerprint = fingerprint(SCOPE_AUTHORIZE, amount + "|" + currency);

        // Fast path: this key has already been answered.
        Optional<IdempotencyRecord> seen = idempotency.find(SCOPE_AUTHORIZE, key);
        if (seen.isPresent()) {
            return replay(seen.get(), fingerprint, SCOPE_AUTHORIZE);
        }

        UUID id = UUID.randomUUID();
        try {
            PaymentView view = tx.execute(status -> {
                payments.insertAuthorized(id, amount, currency, correlationId);
                Payment payment = payments.findById(id).orElseThrow();
                PaymentView v = PaymentView.of(payment, null);
                // Committing the payment and the idempotency record together is
                // what makes the guarantee hold: there is no window in which a
                // payment exists without its key being claimed, or vice versa.
                idempotency.insert(SCOPE_AUTHORIZE, key, fingerprint, id, 201, toJson(v), correlationId);
                return v;
            });
            metrics.authorized();
            log.info("event=payment_authorized payment_id={} amount_minor={} currency={}",
                    id, amount, currency);
            return new Result(201, view, false);

        } catch (DuplicateKeyException e) {
            // A concurrent request with the same key committed while we were
            // working. Our transaction (and its payment row) rolled back, so no
            // duplicate payment exists. Answer with the winner's response.
            IdempotencyRecord record = idempotency.find(SCOPE_AUTHORIZE, key)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "idempotency key collided but no record is visible"));
            log.info("event=authorize_race_lost winner_payment_id={}", record.paymentId());
            return replay(record, fingerprint, SCOPE_AUTHORIZE);
        }
    }

    // ------------------------------------------------------------------
    // capture
    // ------------------------------------------------------------------

    /**
     * Capture a payment exactly once and enqueue its settlement.
     *
     * <p>Everything below happens inside one transaction that begins by locking
     * the payment row, so concurrent captures of the same payment execute
     * strictly one after another:
     *
     * <ol>
     *   <li>lock the payment;</li>
     *   <li>if this idempotency key was already answered, replay it (or 409 if
     *       it was answered for a different request);</li>
     *   <li>if the payment is not AUTHORIZED, 409 — it was already captured or
     *       voided, and neither can be captured again;</li>
     *   <li>otherwise transition to CAPTURED, insert the outbox row, and record
     *       the idempotency response — all in the same commit.</li>
     * </ol>
     *
     * <p>Twenty concurrent captures therefore produce exactly one capture and
     * exactly one outbox row: with the same key, one 200 and nineteen replayed
     * 200s; with distinct keys, one 200 and nineteen 409s. Never a 500, and
     * never two captures.
     */
    public Result capture(UUID paymentId, CaptureRequest request, String correlationId) {
        String key = request.idempotencyKey();
        // The capture request body carries only the key, so the request identity
        // is the payment being captured. Reusing a key against a different
        // payment therefore fails the fingerprint check and returns 409.
        String fingerprint = fingerprint(SCOPE_CAPTURE, paymentId.toString());

        try {
            return tx.execute(status -> {
                Payment payment = payments.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> ApiException.notFound("payment " + paymentId + " not found"));

                Optional<IdempotencyRecord> seen = idempotency.find(SCOPE_CAPTURE, key);
                if (seen.isPresent()) {
                    return replay(seen.get(), fingerprint, SCOPE_CAPTURE);
                }

                if (!payment.state().isCapturable()) {
                    metrics.captureRaceLost();
                    log.info("event=capture_rejected payment_id={} reason=not_capturable state={}",
                            paymentId, payment.state());
                    throw new ApiException(
                            payment.state() == com.settlement.domain.PaymentState.CAPTURED
                                    ? ErrorCode.ALREADY_CAPTURED
                                    : ErrorCode.INVALID_STATE_TRANSITION,
                            "payment is " + payment.state() + " and cannot be captured");
                }

                int updated = payments.markCaptured(paymentId);
                if (updated != 1) {
                    // Unreachable while we hold the row lock; if it ever fires,
                    // the locking assumption is broken and we must not proceed.
                    throw new IllegalStateException(
                            "capture transition lost under row lock for payment " + paymentId);
                }

                UUID settlementKey = UUID.randomUUID();
                outbox.enqueue(paymentId, settlementKey, payment.amountMinor(), payment.currency(),
                        settlementMaxAttempts, correlationId);

                Payment captured = payments.findById(paymentId).orElseThrow();
                OutboxItem item = outbox.findByPaymentId(paymentId).orElseThrow();
                PaymentView view = PaymentView.of(captured, item);
                idempotency.insert(SCOPE_CAPTURE, key, fingerprint, paymentId, 200, toJson(view), correlationId);

                metrics.captured();
                log.info("event=payment_captured payment_id={} settlement_key={} amount_minor={} currency={}",
                        paymentId, settlementKey, captured.amountMinor(), captured.currency());
                return new Result(200, view, false);
            });

        } catch (DuplicateKeyException e) {
            // The same capture key was used concurrently against a *different*
            // payment, so the two transactions held different row locks and both
            // reached the idempotency insert. Ours rolled back; nothing was
            // captured by us.
            metrics.captureRaceLost();
            IdempotencyRecord record = idempotency.find(SCOPE_CAPTURE, key)
                    .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                            "idempotency key collided but no record is visible"));
            log.info("event=capture_race_lost payment_id={} winner_payment_id={}",
                    paymentId, record.paymentId());
            return replay(record, fingerprint, SCOPE_CAPTURE);
        }
    }

    // ------------------------------------------------------------------
    // void (the terminal failure path a payment can reach without capture)
    // ------------------------------------------------------------------

    public PaymentView voidAuthorization(UUID paymentId) {
        return tx.execute(status -> {
            Payment payment = payments.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> ApiException.notFound("payment " + paymentId + " not found"));
            if (payment.state() != com.settlement.domain.PaymentState.AUTHORIZED) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "payment is " + payment.state() + " and cannot be voided");
            }
            payments.markFailed(paymentId);
            log.info("event=payment_voided payment_id={}", paymentId);
            return PaymentView.of(payments.findById(paymentId).orElseThrow(), null);
        });
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    public PaymentView get(UUID paymentId) {
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("payment " + paymentId + " not found"));
        return PaymentView.of(payment, outbox.findByPaymentId(paymentId).orElse(null));
    }

    public List<PaymentView> recent(int limit) {
        return payments.findRecent(limit).stream()
                .map(p -> PaymentView.of(p, outbox.findByPaymentId(p.id()).orElse(null)))
                .toList();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Same key + same request -> the original response. Same key + different
     * request -> 409, as required. Comparing a hash rather than the raw body
     * keeps arbitrary caller input out of the database and the logs.
     */
    private Result replay(IdempotencyRecord record, String fingerprint, String scope) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            metrics.keyReused();
            log.info("event=idempotency_key_reused scope={} original_payment_id={}", scope, record.paymentId());
            throw ApiException.keyReused(scope);
        }
        metrics.replayed(scope);
        log.info("event=idempotent_replay scope={} payment_id={} status={}",
                scope, record.paymentId(), record.responseStatus());
        return new Result(record.responseStatus(), fromJson(record.responseBody()), true);
    }

    private static String normalizeCurrency(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("^[A-Z]{3}$")) {
            throw ApiException.invalid("currency must be a 3-letter ISO-4217 code");
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("currency '" + code + "' is not a known ISO-4217 code");
        }
        return code;
    }

    private static String fingerprint(String scope, String canonicalRequest) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((scope + "|" + canonicalRequest).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String toJson(PaymentView view) {
        try {
            return json.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize payment view", e);
        }
    }

    private PaymentView fromJson(String body) {
        try {
            return json.readValue(body, PaymentView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored idempotent response", e);
        }
    }
}
