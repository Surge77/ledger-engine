package dev.ledger.engine.controller;

import dev.ledger.engine.dto.ReconcileResult;
import dev.ledger.engine.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ReconciliationService reconciliation;

    public AdminController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping("/reconcile")
    public ReconcileResult reconcile() {
        return reconciliation.reconcile();
    }
}
