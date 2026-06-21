package dev.ledger.engine.dto;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, boolean hasMore) {
}
