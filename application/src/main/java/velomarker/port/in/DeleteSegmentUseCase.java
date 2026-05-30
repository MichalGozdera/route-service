package velomarker.port.in;

import velomarker.entity.SegmentName;

public interface DeleteSegmentUseCase {
    /** Deletes file + triggers rolling restart of BRouter workers. */
    void delete(SegmentName name);
}
