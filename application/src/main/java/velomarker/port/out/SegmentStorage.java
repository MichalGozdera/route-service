package velomarker.port.out;

import velomarker.entity.SegmentName;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Local filesystem operations on {@code /brouter/segments4/*.rd5}. Writes are
 * atomic: implementations must write to a temp file then ATOMIC_MOVE into place
 * so workers never observe a partial file.
 */
public interface SegmentStorage {

    List<InstalledSegment> listInstalled();

    Optional<InstalledSegment> find(SegmentName name);

    /**
     * Opens an OutputStream that streams to a temp file. Caller closes the stream
     * to finalize; then call {@link #commit(SegmentName)} to move into place.
     */
    OutputStream openTempForWrite(SegmentName name) throws IOException;

    /** Atomic move temp → segments4/{name}.rd5. */
    void commit(SegmentName name) throws IOException;

    /** Discard temp file without committing. */
    void abort(SegmentName name);

    /** Deletes the .rd5 file. No-op if not present. Returns true if file was deleted. */
    boolean delete(SegmentName name) throws IOException;

    record InstalledSegment(SegmentName name, long sizeBytes, Instant modifiedAt) {}
}
