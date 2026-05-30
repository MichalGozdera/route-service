package velomarker.port.in;

import velomarker.entity.SegmentInfo;

import java.util.List;

public interface ListSegmentsUseCase {
    /** @param europeOnly if true, filter to the Europe bounding box. */
    List<SegmentInfo> listAll(boolean europeOnly);
}
