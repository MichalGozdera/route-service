package velomarker.service.planning.coverage;

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

import java.util.Map;

/** Kolaboratory zbudowane raz na plan (setup) — przekazywane między etapami plan(). */
record CoverageEngine(CoverageAreaIndex index, EdgeRouter edgeRouter, RouteMetrics metrics,
                      SeedBuilder seedBuilder, Map<Integer, String> areaCat, double totalLimit) {}
