package dev.skewer.bank.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.skewer.bank.strategy.Strategies;

/** The workload's entry point: one operation per request, strategy and isolation chosen per request. */
@RestController
@RequestMapping("/api")
public class OpController {

    private final Strategies strategies;
    private final InFlight inFlight;

    public OpController(Strategies strategies, InFlight inFlight) {
        this.strategies = strategies;
        this.inFlight = inFlight;
    }

    @PostMapping("/ops")
    public OpResult op(@RequestBody OpRequest request) {
        inFlight.enter();
        try {
            return strategies.get(request.strategy()).execute(request);
        } finally {
            inFlight.exit();
        }
    }

    @GetMapping("/strategies")
    public List<String> strategies() {
        return strategies.names();
    }
}
