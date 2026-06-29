package velomarker.service.planning.route;

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

import java.util.List;

record MergeAccum(List<double[]> coords, double totalDistKm,
                  List<velomarker.entity.RouteSpan> surfaceSpans,
                  List<velomarker.entity.RouteSpan> roadSpans,
                  List<velomarker.entity.RouteSpan> smoothnessSpans,
                  velomarker.entity.RouteStats aggregatedMaps) {}
