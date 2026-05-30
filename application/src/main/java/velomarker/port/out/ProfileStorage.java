package velomarker.port.out;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Filesystem operations on {@code /brouter/profiles2/*.brf}. Writes are atomic
 * (temp file + ATOMIC_MOVE) so BRouter workers never observe a partially written profile.
 */
public interface ProfileStorage {

    List<Profile> list();

    Optional<ProfileContent> read(String name) throws IOException;

    /** Atomic save: writes temp then moves. Returns updated metadata. */
    Profile save(String name, String content) throws IOException;

    boolean delete(String name) throws IOException;

    record Profile(String name, long sizeBytes, Instant modifiedAt) {}
    record ProfileContent(String name, String content, long sizeBytes, Instant modifiedAt) {}
}
