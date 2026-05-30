package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.openapi.api.ProfilesApi;
import eu.cokeman.velomarker.openapi.model.ProfileContentDto;
import eu.cokeman.velomarker.openapi.model.ProfileSummaryDto;
import eu.cokeman.velomarker.openapi.model.SaveProfileRequestDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.port.in.ProfileManagementUseCase;
import velomarker.port.out.ProfileStorage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@RestController
public class ProfilesController implements ProfilesApi {

    private final ProfileManagementUseCase useCase;

    public ProfilesController(ProfileManagementUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public ResponseEntity<List<ProfileSummaryDto>> listProfiles() {
        return ResponseEntity.ok(useCase.list().stream().map(this::toSummaryDto).toList());
    }

    @Override
    public ResponseEntity<ProfileContentDto> getProfile(String name) {
        try {
            Optional<ProfileStorage.ProfileContent> opt = useCase.read(name);
            return opt.map(c -> ResponseEntity.ok(toContentDto(c)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read profile " + name, e);
        }
    }

    @Override
    public ResponseEntity<Void> saveProfile(String name, SaveProfileRequestDto body) {
        try {
            useCase.save(name, body.getContent());
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to save profile " + name, e);
        }
    }

    @Override
    public ResponseEntity<Void> deleteProfile(String name) {
        try {
            boolean deleted = useCase.delete(name);
            return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete profile " + name, e);
        }
    }

    private ProfileSummaryDto toSummaryDto(ProfileStorage.Profile p) {
        ProfileSummaryDto dto = new ProfileSummaryDto();
        dto.setName(p.name());
        dto.setSizeBytes(p.sizeBytes());
        dto.setModifiedAt(p.modifiedAt());
        return dto;
    }

    private ProfileContentDto toContentDto(ProfileStorage.ProfileContent c) {
        ProfileContentDto dto = new ProfileContentDto();
        dto.setName(c.name());
        dto.setContent(c.content());
        dto.setSizeBytes(c.sizeBytes());
        dto.setModifiedAt(c.modifiedAt());
        return dto;
    }
}
