package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import velomarker.port.out.BrouterRoutingClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wykonuje N dummy BRouter calls przy starcie serwisu — żeby JIT skompilował hot methods,
 * mmap segments .rd5 weszły w OS page cache, GC ustabilizował heap. Bez tego pierwsze
 * 500-800 realnych calls są 5-10× wolniejsze (max do 15 sec) niż po stabilizacji JVM.
 *
 * <p>Pary waypointów rozłożone w Polsce (różne regiony) by warm-up'ować różne tile'e .rd5.
 * Wszystkie z {@code computeStats=false} — nie obchodzi nas wynik, tylko czas wykonania.
 * Wątki parallel — żeby JIT zobaczył multi-threaded execution.
 */
@Component
@ConditionalOnProperty(name = "route.brouter.warmup", havingValue = "true", matchIfMissing = true)
public class BrouterWarmupRunner {

    private static final Logger log = LoggerFactory.getLogger(BrouterWarmupRunner.class);

    private final BrouterRoutingClient brouterClient;

    public BrouterWarmupRunner(BrouterRoutingClient brouterClient) {
        this.brouterClient = brouterClient;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void warmup() {
        // Pary [from, to] rozłożone w Polsce — różne tile'e .rd5, różne dystanse (3-30 km).
        // Krótkie pary by warmup był szybki (10-15 sec total).
        double[][][] pairs = new double[][][] {
                {{21.0, 52.2}, {21.3, 52.3}},   // Warszawa region
                {{19.9, 50.0}, {20.2, 50.1}},   // Kraków
                {{17.0, 51.1}, {17.3, 51.2}},   // Wrocław
                {{18.6, 54.4}, {18.9, 54.5}},   // Gdańsk
                {{16.9, 52.4}, {17.2, 52.5}},   // Poznań
                {{22.5, 51.2}, {22.8, 51.3}},   // Lublin
                {{19.4, 51.8}, {19.7, 51.9}},   // Łódź
                {{23.1, 53.1}, {23.4, 53.2}},   // Białystok
                {{18.0, 53.1}, {18.3, 53.2}},   // Bydgoszcz
                {{20.6, 53.8}, {20.9, 53.9}},   // Olsztyn
        };
        String[] profiles = {"trekking", "fastbike", "safety"};

        long t0 = System.currentTimeMillis();
        AtomicLong done = new AtomicLong();
        Thread[] threads = new Thread[Math.min(8, pairs.length * profiles.length)];
        java.util.concurrent.atomic.AtomicInteger workIdx = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = pairs.length * profiles.length;

        for (int t = 0; t < threads.length; t++) {
            threads[t] = Thread.ofVirtual().start(() -> {
                int idx;
                while ((idx = workIdx.getAndIncrement()) < total) {
                    int pi = idx % pairs.length;
                    int prof = idx / pairs.length;
                    try {
                        brouterClient.calculate(List.of(pairs[pi][0], pairs[pi][1]), profiles[prof], false);
                        done.incrementAndGet();
                    } catch (RuntimeException ignored) {
                        // Brak .rd5 dla regionu lub inny błąd — nie ma znaczenia, warmup jest best-effort.
                    }
                }
            });
        }
        for (Thread th : threads) {
            try { th.join(60_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
        long elapsedMs = System.currentTimeMillis() - t0;
        log.info("BRouter warmup: {} of {} calls done in {} ms (JIT/page-cache/GC pre-warmed; pierwsze realne calls będą szybsze)",
                done.get(), total, elapsedMs);
    }
}
