package eu.cokeman.velomarker.out;

import org.slf4j.Logger;
import velomarker.entity.RouteStats;
import velomarker.service.RouteStatsFormatter;

/**
 * Loguje {@link RouteStats} jako "ludzki" tekst PL na INFO. Cienki wrapper na
 * {@link RouteStatsFormatter} (formatter siedzi w application, by korzystał z niego również
 * orchestrator planning'u dla agregatu wielu chunków).
 */
final class HumanRouteStatsLogger {

    private HumanRouteStatsLogger() {
    }

    /** Format dla już zbudowanego {@link RouteStats} (per call lub agregat). */
    static void log(Logger log, RouteStats stats, String profile) {
        if (stats == null || stats.totalMeters() == 0) {
            return;
        }
        String body = RouteStatsFormatter.format(stats, "Statystyki trasy (profil: " + profile + ")");
        if (!body.isEmpty()) {
            log.info(body);
        }
    }
}
