package dev.ledger.engine.dto;

import java.util.List;

public record TransferResponse(
        long transferId,
        String status,
        List<BalanceResponse> balances) {
}
