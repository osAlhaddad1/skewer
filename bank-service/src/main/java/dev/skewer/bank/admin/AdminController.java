package dev.skewer.bank.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import dev.skewer.bank.api.InFlight;
import dev.skewer.checker.history.Account;

/** Test-profile endpoints the workload uses between runs. Not something a real bank would expose. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final BankAdmin admin;
    private final InFlight inFlight;

    public AdminController(BankAdmin admin, InFlight inFlight) {
        this.admin = admin;
        this.inFlight = inFlight;
    }

    /** Resets only once every straggler from the previous run has finished, so none writes into the new run. */
    @PostMapping("/reset")
    public void reset(@RequestBody List<Account> accounts) throws InterruptedException {
        quiesce(60_000);
        admin.reset(accounts);
    }

    /** Waits until no operation is executing; 409 if some are still running after the timeout. */
    @PostMapping("/quiesce")
    public void quiesce(@RequestParam(defaultValue = "60000") long timeoutMs) throws InterruptedException {
        if (!inFlight.awaitIdle(timeoutMs)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, inFlight.current() + " operations still running");
        }
    }

    @PostMapping("/instrumentation")
    public void instrumentation(@RequestParam boolean enabled) {
        admin.instrumentation(enabled);
    }

    @GetMapping("/history")
    public ServerHistory history() {
        return admin.export();
    }
}
