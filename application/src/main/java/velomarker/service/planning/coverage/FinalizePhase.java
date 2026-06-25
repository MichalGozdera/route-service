package velomarker.service.planning.coverage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * OSTATNIA FAZA seeda (FINALIZE) — osobna klasa odpowiedzialności (wzorzec {@link GrowNear}/{@link Anchorer}/
 * {@link SpurCutter}). Najpierw OBOWIĄZKOWY przebieg refine→anchor→refine→cut (zakotwicz surowy init-grow), POTEM
 * cykl budżetowy (≤5): zmierz → dobierz/utnij proporcjonalnie (reward-aware) → refine→anchor→refine→cut, aż pasmo
 * [95,105]%; na końcu domknij otoczone dziury. Deleguje WPROST do {@link Anchorer}/{@link SpurCutter}/{@link GrowNear}.
 * Mutuje {@link SeedRoute} w miejscu; zwraca {@link FinalizeResult}.
 */
final class FinalizePhase {

    private static final Logger log = LoggerFactory.getLogger(FinalizePhase.class);

    /** Wynik finalize: effort + ile dobrano (grow) / ucięto (trim). */
    record FinalizeResult(double realEffort, int grown, int trimmed) {}

    /** Kandydat do ucięcia: wp + klucz reward/koszt-objazdu (niski = zły deal = tnij pierwszy). */
    private record DealCand(SeedSel s, double key) {}

    /** Wynik peelingu: nowy effort + ile wp ucięto. */
    private record PeelResult(double realEffort, int peeled) {}

    private final SeedContext ctx;
    private final EdgeRouter edgeRouter;
    private final RouteMetrics metrics;
    private final GminaIndex gminaIndex;
    private final java.util.Map<String, Double> rewards;
    private final CoverageDebug debug;
    private final boolean debugGeoJson;
    private final CandidatePicker picker;
    private final SeedRoute seed;
    private final List<double[]> route;
    private final List<SeedSel> selected;

    private final double targetEffort, hiBand, growCeiling;
    private double realEffort;
    private boolean allCandidatesUsed;

    FinalizePhase(SeedContext ctx, SeedRoute seed, CandidatePicker picker, double targetEffort, double hiBand,
                  double growCeiling, double realEffort, boolean allCandidatesUsed) {
        this.ctx = ctx;
        this.edgeRouter = ctx.edgeRouter();
        this.metrics = ctx.metrics();
        this.gminaIndex = ctx.gminaIndex();
        this.rewards = ctx.rewards();
        this.debug = ctx.debug();
        this.debugGeoJson = ctx.debugGeoJson();
        this.picker = picker;
        this.seed = seed;
        this.route = seed.route();
        this.selected = seed.selected();
        this.targetEffort = targetEffort;
        this.hiBand = hiBand;
        this.growCeiling = growCeiling;
        this.realEffort = realEffort;
        this.allCandidatesUsed = allCandidatesUsed;
    }

    /**
     * Najpierw OBOWIĄZKOWY przebieg refine→anchor→refine→cut (zakotwicz surowy init-grow, NIEZALEŻNIE od budżetu),
     * POTEM cykl budżetowy (≤5). Na końcu domknij otoczone dziury + finalny untangle.
     */
    FinalizeResult run() {
        final double STALL_EPS = 1.0;
        int grown = 0, trimmed = 0;
        // OBOWIĄZKOWY pierwszy przebieg (NIEZALEŻNIE od budżetu): init-grow zostawił SUROWE wp (sorted, bez 2-opt/
        // anchor/cut) → zakotwicz na wjazdach + przytnij ogonki ZANIM zmierzymy budżet (inaczej eFrac na surowej
        // trasie = śmieć → zła decyzja; a eFrac w paśmie → break zwróciłby trasę niezakotwiczoną).
        refine("fin-init-pre-anchor");
        new Anchorer(ctx, seed, "fin-init").run();
        refine("fin-init-pre-cut");                  // refine PO anchor bo SpurCutter zakłada rozplątaną trasę
        realEffort = new SpurCutter(ctx, seed, targetEffort, 8, "fin-init-cut").run();   // 8-pass (do skutku)
        if (debugGeoJson) {
            debug.geometry("fin-init-real", metrics.realGeometry(route), route, metrics.realKm(route));
            debug.logShots("fin-init");
        }
        for (int cycle = 0; cycle < 5; cycle++) {
            double before = realEffort;
            double eFrac = realEffort / targetEffort;
            if (eFrac >= 0.95 && eFrac <= 1.05) {
                log.info("Coverage FINALIZE cycle {}: {}% → KONIEC (w paśmie)", cycle, Math.round(eFrac * 100));
                break;
            }
            int delta = 0;
            if (eFrac > 1.05) {
                PeelResult pr = peelToCeiling("fin" + cycle);   // porcję cięcia liczy sam peel (rn per iteracja)
                realEffort = pr.realEffort();
                trimmed += pr.peeled();
                delta = -pr.peeled();
            } else {
                if (allCandidatesUsed) {
                    log.info("Coverage FINALIZE cycle {}: {}% <95%, wszystkie kandydaci dobrani → KONIEC (akceptuj)",
                            cycle, Math.round(eFrac * 100));
                    break;
                }
                // MAŁYMI partiami z pomiarem (jak init-grow), STOP gdy przekroczy 105% — bez wielkiego przestrzału
                // jednym proporcjonalnym `pick`iem (to robiło dziurę + wymuszało peel → wtórne dziury).
                edgeRouter.setReason("grow");
                final int GROW_BATCH = 6;
                while (realEffort < hiBand) {
                    CandidatePicker.PickResult pr = picker.pick(GROW_BATCH);
                    if (pr.inserted() == 0) { allCandidatesUsed = true; break; }
                    delta += pr.inserted();
                    grown += pr.inserted();
                    CoverageLocalSearch.optimize(route);            // 2-opt po partii
                    realEffort = metrics.effortViaCache(route);     // realny pomiar po KAŻDEJ małej partii
                    if (pr.poolExhausted()) { allCandidatesUsed = true; break; }
                }
                if (delta == 0) allCandidatesUsed = true; // nic nie dobrano → następny under-cykl wyjdzie
            }
            // Po GROW: anchor (nowe gminy potrzebują wp ≥220m), potem cut. Po PEEL: BEZ anchora — re-kotwiczenie
            // ISTNIEJĄCYCH gmin re-influje dystans i NIWECZY peel (obserwacja: peel 116→103%, anchor 103→138%).
            // Usuwanie wp nie tworzy nowych gmin do zakotwiczenia, a cut (redukuje) sam doczyszcza spury po 2-opt.
            boolean grew = delta > 0;
            refine("fin" + cycle + (grew ? "-pre-anchor" : "-pre-cut"));
            if (grew) {
                new Anchorer(ctx, seed, "fin" + cycle).run();
                refine("fin" + cycle + "-pre-cut");
            }
            realEffort = new SpurCutter(ctx, seed, targetEffort, 3, "fin" + cycle + "-cut").run();   // 3-pass
            if (debugGeoJson) {
                debug.geometry("fin" + cycle + "-real", metrics.realGeometry(route), route, metrics.realKm(route));
                debug.logShots("fin" + cycle);
            }
            if (Math.abs(realEffort - before) < STALL_EPS && delta == 0) {
                log.info("Coverage FINALIZE cycle {}: bez postępu (Δeffort≈0, nic dobrane/ucięte) → STOP", cycle);
                break;
            }
        }
        // FINALNY untangle — kontrakt: trasa rozplątana dla plan()
        realEffort = metrics.effortViaCache(route);
        return new FinalizeResult(realEffort, grown, trimmed);
    }

    /** FINALNY refine kolejności (pełny or-opt + 2-opt do zbieżności). Loguje Δkm + debug-skeleton PRZED/PO. */
    private void refine(String phase) {
        if (route.size() < 4) return;
        if (debugGeoJson) debug.skeleton(phase + "-refine-before", route);
        double kmBefore = metrics.haversineKm(route);
        int wp = route.size();
        log.info("Coverage REFINE [{}]: start havKm={}, wps={}", new Object[]{phase, Math.round(kmBefore), wp});
        int moves = CoverageLocalSearch.optimize(route);
        double kmAfter = metrics.haversineKm(route);
        log.info("Coverage REFINE [{}]: havKm {}→{} (Δ{}), ruchów={}, wps={}",
                new Object[]{phase, Math.round(kmBefore), Math.round(kmAfter), Math.round(kmAfter - kmBefore), moves, wp});
        if (debugGeoJson) debug.skeleton(phase + "-refine-after", route);
    }

    /** Tnij FRINGE (nie-otoczone, reward/detour ASC) porcjami aż ≤hiBand lub brak bezpiecznych; gdy fringe pusty
     *  (pełne pokrycie) → tnij OBWÓD (borderAreaIds, chroń reward-P95). Zwraca {effort, ucięte}. */
    private PeelResult peelToCeiling(String phase) {
        final double PROGRESS_EPS = 1.0;
        int peeled = 0;
        // Zbijaj proporcjonalnie do 100% (nie do hiBand=105%): anchor/cut PO peel już nie re-influją (anchor tylko po grow),
        // więc to peel musi dowieźć do budżetu — nie ma na czym „polegać", że dotnie resztę.
        for (int peelK = 1; peelK <= 8 && realEffort > targetEffort; peelK++) {
            long peelCallsStart = edgeRouter.realCalls();
            double before = realEffort;
            RouteMetrics.EvalResult evt = metrics.eval(route);
            Set<Integer> visited = evt.visited();
            int gmint = visited.size();
            double eFracT = realEffort / targetEffort;
            int rn = Math.max(1, (int) Math.round(gmint * (eFracT - 1.0) / eFracT));
            boolean border = false;
            List<DealCand> cands = collectDealCandidates(visited, false);          // fringe (nie-otoczone)
            if (cands.isEmpty()) { cands = collectDealCandidates(visited, true); border = true; } // pełne pokrycie → OBWÓD
            if (cands.isEmpty()) break;
            cands.sort(Comparator.comparingDouble(DealCand::key));
            edgeRouter.setReason("pomiar");
            int removed = 0;
            for (DealCand dc : cands) {
                if (removed >= rn) break;
                int wpIdx = GeometryUtil.identityIndexOf(route, dc.s().point());
                if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
                route.remove(wpIdx);            // W MIEJSCU (bez rebuildOrdered) → prev→next wprost
                selected.remove(dc.s());
                peeled++; removed++;
            }
            if (removed == 0) break;
            CoverageLocalSearch.optimize(route);
            realEffort = metrics.effortAccurate(route);
            log.info("Coverage FINALIZE peel {} k={}: {} gmin @ {}% → -{} ({}) → {}%, calls={}",
                    new Object[]{phase, peelK, gmint, Math.round(eFracT * 100), removed,
                            border ? "OBWÓD" : "fringe", Math.round(realEffort / targetEffort * 100),
                            edgeRouter.realCalls() - peelCallsStart});
            if (realEffort >= before - PROGRESS_EPS) break;   // anty-spin: ucięto kolateral, effort nie schudł
        }
        return new PeelResult(realEffort, peeled);
    }

    /** Kandydaci do cięcia: nie-protected wp. {@code border=false}: FRINGE (nie-otoczone — bez dziur).
     *  {@code border=true}: OBWÓD (borderAreaIds), chroń reward≥P95 (cenne stolice na rim). Klucz reward/detour. */
    private List<DealCand> collectDealCandidates(Set<Integer> visited, boolean border) {
        final double DETOUR_EPS = 0.05;
        // Union z historycznie zaliczonymi: nie tnij gminy, której usunięcie tworzy dziurę także na styku z DAWNYM pokryciem.
        Set<Integer> union = new java.util.HashSet<>(visited);
        union.addAll(gminaIndex.historicallyVisited());
        Set<Integer> rim = border ? gminaIndex.borderAreaIds(union) : null;
        double p95 = border ? rewardP95(visited) : Double.MAX_VALUE;
        List<DealCand> cands = new ArrayList<>();
        for (SeedSel s : selected) {
            int aid = s.area().areaId();
            double rw = rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0);
            if (border) {
                if (!rim.contains(aid) || rw >= p95) continue;            // tylko obwód, chroń cenne stolice
            } else {
                if (gminaIndex.enclosedByVisited(aid, union)) continue;    // usunięcie zrobiłoby dziurę (≥90% obwodu) → chroń
            }
            int wpIdx = GeometryUtil.identityIndexOf(route, s.point());
            if (wpIdx <= 0 || wpIdx >= route.size() - 1) continue;
            double[] prev = route.get(wpIdx - 1), cur = route.get(wpIdx), next = route.get(wpIdx + 1);
            double eIn = edgeRouter.edge(prev, cur).distanceKm();
            double eOut = edgeRouter.edge(cur, next).distanceKm();
            double detour = Math.max(0.0, eIn + eOut
                    - velomarker.service.planning.WaypointSelector.haversineKm(prev, next));
            // Klucz: niski reward + duży objazd = tnij pierwszy; ALE pomnóż przez (1+adjFrac), by gminy
            // częściowo wtopione w pokrycie (wysoki udział granicy z zaliczonymi) ciąć PÓŹNIEJ → nie rób
            // wtórnych dziur. Skrajne/peryferyjne (adjFrac≈0) lecą pierwsze.
            double adj = gminaIndex.neighborVisitedFraction(aid, union);
            cands.add(new DealCand(s, (rw / Math.max(DETOUR_EPS, detour)) * (1.0 + adj)));
        }
        return cands;
    }

    /** 95-percentyl reward wśród zaliczonych (chroni najcenniejsze gminy na obwodzie przed cięciem). */
    private double rewardP95(Set<Integer> visited) {
        List<Double> rs = new ArrayList<>();
        for (SeedSel s : selected) {
            if (visited.contains(s.area().areaId())) rs.add(rewards.getOrDefault(RewardModel.categoryKey(s.area()), 1.0));
        }
        if (rs.isEmpty()) return Double.MAX_VALUE;
        rs.sort(null);
        return rs.get((int) Math.floor(0.95 * (rs.size() - 1)));
    }
}
