package velomarker.service.planning.coverage;

import velomarker.entity.planning.UnvisitedArea;

/** Kandydat seeda: obszar + entry-point + klucz porządkowania (proj/Hilbert) + score + dist do baseline. */
record SeedSel(UnvisitedArea area, double[] point, double proj, double score, double distBase) {}
