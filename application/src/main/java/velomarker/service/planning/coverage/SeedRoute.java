package velomarker.service.planning.coverage;

import java.util.List;

/**
 * Mutowalny stan trasy seeda: punkty trasy + wybrane gminy + kotwice + baseline. Grupuje 6 kolekcji
 * wleczonych dotąd jako osobne parametry przez cały silnik seeda. Kolekcje są mutowane w miejscu
 * (route.set/add) — getter zwraca tę samą referencję, więc zmiany są widoczne dla wszystkich faz.
 */
final class SeedRoute {

    /** Punkty trasy (waypointy) — mutowane in-place przez fazy (anchor/prune/grow). */
    private final List<double[]> route;
    /** Wybrane gminy (entry-point + score) odpowiadające waypointom. */
    private final List<SeedSel> selected;
    /** Punkty „tylko-kotwica" (start/end/przeloty) — nie liczą się jako pokrycie gminy. */
    private final List<double[]> anchorOnly;
    /** Kotwice trasy (start + end). */
    private final List<double[]> anchors;
    /** Korytarz bazowy (downsampled) — odniesienie do projekcji i kar za oddalenie. */
    private final List<double[]> baseline;
    /** Skumulowane km wzdłuż baseline (do projekcji punktów na korytarz). */
    private final double[] baseCum;

    SeedRoute(List<double[]> route, List<SeedSel> selected, List<double[]> anchorOnly,
              List<double[]> anchors, List<double[]> baseline, double[] baseCum) {
        this.route = route;
        this.selected = selected;
        this.anchorOnly = anchorOnly;
        this.anchors = anchors;
        this.baseline = baseline;
        this.baseCum = baseCum;
    }

    List<double[]> route() { return route; }
    List<SeedSel> selected() { return selected; }
    List<double[]> anchorOnly() { return anchorOnly; }
    List<double[]> anchors() { return anchors; }
    List<double[]> baseline() { return baseline; }
    double[] baseCum() { return baseCum; }
}
