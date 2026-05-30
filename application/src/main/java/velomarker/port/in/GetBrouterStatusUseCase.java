package velomarker.port.in;

import velomarker.port.out.BrouterControlClient.WorkerStatus;

import java.util.List;

public interface GetBrouterStatusUseCase {
    List<WorkerStatus> status();
}
