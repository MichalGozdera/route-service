package velomarker.service.planning;

/**
 * Współczynnik {@code road / straight} dla doboru gmin: o ile dłuższa jest realna trasa BRouter niż
 * linia prosta przez kolejne wjazdy. Mierzony realnym probe'em — najpierw seed ze szkieletu
 * start→via→meta (baseline), potem nadpisany dokładniejszym density-probe'em przez 30 najbliższych gmin.
 *
 * <p>Bez defaultów i fallbacków: jeśli baseline (start→meta) nie da się policzyć, plan jest niemożliwy
 * → błąd, nie zgadywanie. Klasa nie jest thread-safe — jedna instancja per task (jeden wątek liczący).
 */
public class RoadFactorCalibrator {

    private double roadAreas;

    /**
     * Ustaw współczynnik z realnego pomiaru BRoutera ({@code actualRoadKm / straightKm}), przycięty do
     * [1.05, 3.5] jako sanity. No-op gdy {@code straightKm ≤ 0} (zdegenerowana geometria).
     */
    public void measure(double actualRoadKm, double straightKm) {
        if (straightKm <= 0) return;
        this.roadAreas = clamp(actualRoadKm / straightKm);
    }

    public double roadAreas() { return roadAreas; }

    private static double clamp(double value) {
        if (value < 1.05) return 1.05;
        if (value > 3.5) return 3.5;
        return value;
    }
}
