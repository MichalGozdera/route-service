package velomarker.service.planning.coverage.seed;

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

/** Wynik doboru. {@code jumpAhead}=w bramce zasięgu 0 kandydatów, ale są poza nią (granica skoku);
 *  {@code nextDistKm}=dystans najbliższego kandydata za bramką (do przesunięcia frontiera). */
public record PickResult(int inserted, boolean poolExhausted, boolean jumpAhead, double nextDistKm) {}
