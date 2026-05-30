package velomarker.port.in;

import velomarker.port.out.ProfileStorage.Profile;
import velomarker.port.out.ProfileStorage.ProfileContent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ProfileManagementUseCase {
    List<Profile> list();
    Optional<ProfileContent> read(String name) throws IOException;
    Profile save(String name, String content) throws IOException;
    boolean delete(String name) throws IOException;
}
