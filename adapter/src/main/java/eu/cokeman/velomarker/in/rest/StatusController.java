package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.openapi.api.StatusApi;
import eu.cokeman.velomarker.openapi.model.LogSourceResponseDto;
import eu.cokeman.velomarker.openapi.model.WorkerStatusResponseDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.port.in.GetBrouterLogsUseCase;
import velomarker.port.in.GetBrouterStatusUseCase;
import velomarker.port.in.RestartWorkersUseCase;

import java.util.List;

@RestController
public class StatusController implements StatusApi {

    private final GetBrouterStatusUseCase statusUseCase;
    private final RestartWorkersUseCase restartUseCase;
    private final GetBrouterLogsUseCase logsUseCase;

    public StatusController(GetBrouterStatusUseCase statusUseCase,
                             RestartWorkersUseCase restartUseCase,
                             GetBrouterLogsUseCase logsUseCase) {
        this.statusUseCase = statusUseCase;
        this.restartUseCase = restartUseCase;
        this.logsUseCase = logsUseCase;
    }

    @Override
    public ResponseEntity<List<WorkerStatusResponseDto>> getBrouterStatus() {
        var statuses = statusUseCase.status();
        return ResponseEntity.ok(statuses.stream().map(s -> {
            WorkerStatusResponseDto dto = new WorkerStatusResponseDto();
            dto.setName(s.name());
            dto.setState(s.state());
            dto.setPid(s.pid());
            dto.setUptime(s.uptime());
            return dto;
        }).toList());
    }

    @Override
    public ResponseEntity<Void> rollingRestart() {
        restartUseCase.rollingRestart();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> restartWorker(Integer index) {
        restartUseCase.restartWorker(index);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<LogSourceResponseDto>> listLogs() {
        return ResponseEntity.ok(logsUseCase.listSources().stream().map(s -> {
            LogSourceResponseDto dto = new LogSourceResponseDto();
            dto.setName(s.name());
            dto.setPath(s.path());
            dto.setSizeBytes(s.sizeBytes());
            dto.setModifiedAt(s.modifiedAtEpochSec());
            return dto;
        }).toList());
    }

    @Override
    public ResponseEntity<String> tailLog(String name, Integer lines) {
        int n = lines == null ? 200 : Math.max(1, Math.min(lines, 5000));
        return ResponseEntity.ok(logsUseCase.tail(name, n));
    }
}
