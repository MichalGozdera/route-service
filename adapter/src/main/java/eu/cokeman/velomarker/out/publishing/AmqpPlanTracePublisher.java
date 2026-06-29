package eu.cokeman.velomarker.out.publishing;

import eu.cokeman.velomarker.amqp.publisher.DomainEventPublisher;
import eu.cokeman.velomarker.event.PlanTraceEvent;
import eu.cokeman.velomarker.mapper.Polyline3DCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import velomarker.port.out.planning.PlanTracePublisher;
import velomarker.service.planning.PlanTraceFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Publikuje LIVE podgląd rosnącej trasy podczas planowania (COVERAGE) do notification-exchange.
 * notification-service routuje na SSE „planning-track" per-user → front rysuje prosty, nieklikalny ślad.
 *
 * <p>BEZ throttla — każdy checkpoint (co 5 batchy dobierania / per-runda wygładzania / per-cykl finalize) leci,
 * żeby user widział każdy przyrost. Geometria downsamplowana do {@link #MAX_POINTS} (lżejsza klatka).
 */
@Component
public class AmqpPlanTracePublisher implements PlanTracePublisher {

    private static final Logger log = LoggerFactory.getLogger(AmqpPlanTracePublisher.class);
    private static final int MAX_POINTS = 1500;

    private final DomainEventPublisher domainEventPublisher;
    private final String notificationExchangeName;

    public AmqpPlanTracePublisher(DomainEventPublisher domainEventPublisher,
                                  @Value("${app.exchange.notification-exchange}") String notificationExchangeName) {
        this.domainEventPublisher = domainEventPublisher;
        this.notificationExchangeName = notificationExchangeName;
    }

    @Override
    public void publish(UUID taskId, UUID userId, PlanTraceFrame frame) {
        try {
            if (frame == null || frame.geometry() == null || frame.geometry().size() < 2
                    || taskId == null || userId == null) return;
            String encoded = Polyline3DCodec.encode(downsample(frame.geometry(), MAX_POINTS));
            domainEventPublisher.publish(notificationExchangeName, "",
                    new PlanTraceEvent(taskId, userId, coarsePhase(frame.phase()), encoded,
                            frame.distanceKm(), frame.elevationGainM(), frame.coveredAreaIds(), frame.coveredByLevel()));
        } catch (RuntimeException e) {
            log.debug("Plan trace publish failed (best-effort): {}", e.getMessage());
        }
    }

    /** Równomierny downsample do max punktów (zachowuje pierwszy i ostatni). NIE gubi klatek — tylko punkty. */
    private static List<double[]> downsample(List<double[]> pts, int max) {
        int n = pts.size();
        if (n <= max) return pts;
        List<double[]> out = new ArrayList<>(max);
        double step = (double) (n - 1) / (max - 1);
        for (int i = 0; i < max; i++) out.add(pts.get((int) Math.round(i * step)));
        out.set(out.size() - 1, pts.get(n - 1));
        return out;
    }

    /** Surowa nazwa fazy seeda → coarse kod tłumaczony na froncie (LIVE_PHASE_*).
     *  GROWING = TYLKO klatki dobierania przez {@code CandidatePicker.pick()} (init-grow + finalize-grow);
     *  kotwiczenie/cięcie/cykl finalize = SMOOTHING („wygładzanie"). */
    private static String coarsePhase(String phase) {
        if (phase == null) return "SMOOTHING";
        String p = phase.toLowerCase(Locale.ROOT);
        if (p.contains("baseline")) return "BASELINE";
        if (p.contains("grow") || p.contains("batch")) return "GROWING"; // klatki z pick(): init-grow + finalize-grow
        return "SMOOTHING"; // anchor / cut / prune / finalize-cykl = wygładzanie
    }
}
