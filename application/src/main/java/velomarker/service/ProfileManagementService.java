package velomarker.service;

import velomarker.port.in.ProfileManagementUseCase;
import velomarker.port.out.ProfileStorage;
import velomarker.port.out.ProfileStorage.Profile;
import velomarker.port.out.ProfileStorage.ProfileContent;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ProfileManagementService implements ProfileManagementUseCase {

    private final ProfileStorage storage;

    public ProfileManagementService(ProfileStorage storage) {
        this.storage = storage;
    }

    @Override
    public List<Profile> list() { return storage.list(); }

    @Override
    public Optional<ProfileContent> read(String name) throws IOException { return storage.read(name); }

    @Override
    public Profile save(String name, String content) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name cannot be empty");
        }
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Profile name must match [A-Za-z0-9_-]+");
        }
        if (content == null) content = "";
        return storage.save(name, content);
    }

    @Override
    public boolean delete(String name) throws IOException { return storage.delete(name); }
}
