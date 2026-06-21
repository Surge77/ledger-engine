package dev.ledger.engine.controller;

import dev.ledger.engine.dto.ReversalResponse;
import dev.ledger.engine.dto.TransferRequest;
import dev.ledger.engine.dto.TransferResponse;
import dev.ledger.engine.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final LedgerService ledger;

    public TransferController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResponse response = ledger.transfer(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/reverse")
    public ResponseEntity<ReversalResponse> reverse(@PathVariable long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ledger.reverse(id));
    }
}
