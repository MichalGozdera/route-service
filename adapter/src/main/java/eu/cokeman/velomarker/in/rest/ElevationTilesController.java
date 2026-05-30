package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.openapi.api.ElevationTilesApi;
import eu.cokeman.velomarker.openapi.model.DemTileResponseDto;
import eu.cokeman.velomarker.openapi.model.DemTileTaskResponseDto;
import eu.cokeman.velomarker.openapi.model.DownloadDemTileRequestDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.DemTileInfo;
import velomarker.entity.DemTileName;
import velomarker.port.in.DeleteDemTileUseCase;
import velomarker.port.in.DownloadDemTileUseCase;
import velomarker.port.in.ListDemTilesUseCase;

import java.util.List;

@RestController
public class ElevationTilesController implements ElevationTilesApi {

    private final ListDemTilesUseCase listUseCase;
    private final DownloadDemTileUseCase downloadUseCase;
    private final DeleteDemTileUseCase deleteUseCase;

    public ElevationTilesController(ListDemTilesUseCase listUseCase,
                                    DownloadDemTileUseCase downloadUseCase,
                                    DeleteDemTileUseCase deleteUseCase) {
        this.listUseCase = listUseCase;
        this.downloadUseCase = downloadUseCase;
        this.deleteUseCase = deleteUseCase;
    }

    @Override
    public ResponseEntity<List<DemTileResponseDto>> listDemTiles(Boolean includeWorld) {
        boolean europeOnly = !Boolean.TRUE.equals(includeWorld);
        List<DemTileInfo> tiles = listUseCase.listAll(europeOnly);
        return ResponseEntity.ok(tiles.stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<DemTileTaskResponseDto> downloadDemTile(DownloadDemTileRequestDto request) {
        DemTileName name = DemTileName.parse(request.getName());
        DownloadDemTileUseCase.DemTileTask task = downloadUseCase.startDownload(name);
        DemTileTaskResponseDto dto = new DemTileTaskResponseDto();
        dto.setTaskId(task.taskId());
        dto.setTileName(task.tile().name());
        dto.setStatus(DemTileTaskResponseDto.StatusEnum.fromValue(task.status().name()));
        return ResponseEntity.accepted().body(dto);
    }

    @Override
    public ResponseEntity<Void> deleteDemTile(String name) {
        deleteUseCase.delete(DemTileName.parse(name));
        return ResponseEntity.noContent().build();
    }

    private DemTileResponseDto toDto(DemTileInfo t) {
        DemTileResponseDto dto = new DemTileResponseDto();
        dto.setName(t.name().name());
        dto.setLonStart(t.name().lonStart());
        dto.setLatStart(t.name().latStart());
        dto.setRemoteSizeBytes(t.remoteSizeBytes());
        dto.setInstalled(t.installed());
        dto.setInstalledSizeBytes(t.installedSizeBytes());
        dto.setInstalledAt(t.installedAt());
        dto.setOutdated(t.isOutdated());
        return dto;
    }
}
