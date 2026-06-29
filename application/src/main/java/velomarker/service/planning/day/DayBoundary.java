package velomarker.service.planning.day;

import velomarker.service.planning.*;
import velomarker.service.planning.route.*;
import velomarker.service.planning.day.*;
import velomarker.service.planning.coverage.*;
import velomarker.service.planning.coverage.prep.*;
import velomarker.service.planning.coverage.seed.*;
import velomarker.service.planning.coverage.index.*;
import velomarker.service.planning.coverage.metric.*;
import velomarker.service.planning.coverage.geom.*;
import velomarker.service.planning.coverage.scoring.*;
import velomarker.service.planning.coverage.debug.*;

/**
 * Granice dnia: indeksy w SAMPLE ElevationProfile + km w skali sample (do logów/diagnostyki).
 * Orchestration mapuje {@code startSampleIdx/endSampleIdx} liniowo na indeksy w pełnej geometrii
 * BRouter (downsample jest uniform-by-INDEX, więc mapping {@code fullIdx = round(sampleIdx × (fullSize-1)/(sampleCount-1))}
 * jest dokładny) i liczy realny dystans z {@code fullCumKm}. Pola {@code *Sample} NIE służą do
 * obliczania finalnego dystansu dnia — to byłby błąd taki jak stary linear rescale.
 */
public record DayBoundary(int startSampleIdx, int endSampleIdx,
                          double startKmSample, double endKmSample,
                          double distanceKmSample, double elevationGain) {
}
