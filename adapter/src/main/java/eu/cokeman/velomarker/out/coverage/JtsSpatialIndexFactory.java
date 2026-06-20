package eu.cokeman.velomarker.out.coverage;

import org.springframework.stereotype.Component;
import velomarker.port.out.planning.SpatialIndex;
import velomarker.port.out.planning.SpatialIndexFactory;

/** JTS-owy {@link SpatialIndexFactory} — buduje {@link JtsSpatialIndex} (STRtree) per zapytanie. */
@Component
public class JtsSpatialIndexFactory implements SpatialIndexFactory {
    @Override
    public SpatialIndex build(double[][] pts) {
        return new JtsSpatialIndex(pts);
    }
}
