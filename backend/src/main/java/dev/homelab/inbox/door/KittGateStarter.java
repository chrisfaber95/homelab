package dev.homelab.inbox.door;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the {@link KittGate} connector on {@code KITT_GATE_HOST:KITT_GATE_PORT} when {@code kitt.gate.enabled} is set
 * (env {@code KITT_GATE_ENABLED}; off by default, H-028 W5c). Like {@code gate.run()} it keeps trying when the bind
 * fails, because tailscaled may come up after the backend. An empty or wildcard host leaves the gate off.
 */
class KittGateStarter implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(KittGateStarter.class);

    private final KittGate gate;
    private final String host;
    private final int port;
    private final boolean enabled;
    private final long retryMillis;
    private volatile ScheduledExecutorService timer;

    KittGateStarter(KittGate gate, String host, int port, boolean enabled, long retryMillis) {
        this.gate = gate;
        this.host = host;
        this.port = port;
        this.enabled = enabled;
        this.retryMillis = retryMillis;
    }

    @Override
    public synchronized void start() {
        if (!enabled || timer != null) {
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kitt-gate-start");
            t.setDaemon(true);
            return t;
        });
        timer.schedule(this::attempt, 0, TimeUnit.MILLISECONDS);
    }

    private void attempt() {
        try {
            Optional<Integer> bound = gate.listen(host, port);
            if (bound.isPresent() || host == null || host.isEmpty()) {
                return;
            }
            LOG.warn("kitt gate: not listening on {}:{}", host, port);
            return; // refused by the exposure rules: trying again would not change that
        } catch (Exception e) {
            LOG.warn("kitt gate: cannot listen on {}:{} ({}); trying again in {} s", host, port, e.toString(),
                    retryMillis / 1000);
        }
        ScheduledExecutorService t = timer;
        if (t != null) {
            t.schedule(this::attempt, retryMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
            timer = null;
        }
        gate.stop();
    }

    @Override
    public boolean isRunning() {
        return timer != null;
    }
}
