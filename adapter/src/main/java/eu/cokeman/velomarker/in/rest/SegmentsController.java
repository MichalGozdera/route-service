package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.openapi.api.SegmentsApi;
import eu.cokeman.velomarker.openapi.model.DownloadSegmentRequestDto;
import eu.cokeman.velomarker.openapi.model.SegmentResponseDto;
import eu.cokeman.velomarker.openapi.model.SegmentTaskResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.SegmentInfo;
import velomarker.entity.SegmentName;
import velomarker.port.in.DeleteSegmentUseCase;
import velomarker.port.in.DownloadSegmentUseCase;
import velomarker.port.in.ListSegmentsUseCase;

import java.util.List;

@RestController
public class SegmentsController implements SegmentsApi {

    private final ListSegmentsUseCase listUseCase;
    private final DownloadSegmentUseCase downloadUseCase;
    private final DeleteSegmentUseCase deleteUseCase;

    public SegmentsController(ListSegmentsUseCase listUseCase,
                               DownloadSegmentUseCase downloadUseCase,
                               DeleteSegmentUseCase deleteUseCase) {
        this.listUseCase = listUseCase;
        this.downloadUseCase = downloadUseCase;
        this.deleteUseCase = deleteUseCase;
    }

    @Override
    public ResponseEntity<List<SegmentResponseDto>> listSegments(Boolean includeWorld) {
        boolean europeOnly = !Boolean.TRUE.equals(includeWorld);
        List<SegmentInfo> segments = listUseCase.listAll(europeOnly);
        return ResponseEntity.ok(segments.stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<SegmentTaskResponseDto> downloadSegment(DownloadSegmentRequestDto request) {
        SegmentName name = SegmentName.parse(request.getName());
        DownloadSegmentUseCase.SegmentTask task = downloadUseCase.startDownload(name);
        SegmentTaskResponseDto dto = new SegmentTaskResponseDto();
        dto.setTaskId(task.taskId());
        dto.setSegmentName(task.segment().name());
        dto.setStatus(SegmentTaskResponseDto.StatusEnum.fromValue(task.status().name()));
        return ResponseEntity.accepted().body(dto);
    }

    @Override
    public ResponseEntity<Void> deleteSegment(String name) {
        deleteUseCase.delete(SegmentName.parse(name));
        return ResponseEntity.noContent().build();
    }

    private SegmentResponseDto toDto(SegmentInfo s) {
        SegmentResponseDto dto = new SegmentResponseDto();
        dto.setName(s.name().name());
        dto.setLonStart(s.name().lonStart());
        dto.setLatStart(s.name().latStart());
        dto.setRemoteSizeBytes(s.remoteSizeBytes());
        dto.setInstalled(s.installed());
        dto.setInstalledSizeBytes(s.installedSizeBytes());
        dto.setInstalledAt(s.installedAt());
        dto.setOutdated(s.isOutdated());
        return dto;
    }
}
