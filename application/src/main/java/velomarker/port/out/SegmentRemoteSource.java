package velomarker.port.out;

import velomarker.entity.SegmentName;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** brouter.de catalog + binary download. */
public interface SegmentRemoteSource {

    /** Listing of every segment + size as advertised by brouter.de. */
    List<RemoteSegment> listAvailable();

    /** Streams the .rd5 binary into the given OutputStream. Caller owns the stream lifecycle. */
    void downloadTo(SegmentName name, OutputStream sink, ProgressListener progress) throws IOException;

    record RemoteSegment(SegmentName name, long sizeBytes) {}

    @FunctionalInterface
    interface ProgressListener {
        void onBytesTransferred(long total, long expected);
    }
}
