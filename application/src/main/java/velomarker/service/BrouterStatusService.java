package velomarker.service;

import velomarker.port.in.GetBrouterLogsUseCase;
import velomarker.port.in.GetBrouterStatusUseCase;
import velomarker.port.in.RestartWorkersUseCase;
import velomarker.port.out.BrouterControlClient;
import velomarker.port.out.BrouterControlClient.LogSource;
import velomarker.port.out.BrouterControlClient.WorkerStatus;

import java.util.List;

public class BrouterStatusService
        implements GetBrouterStatusUseCase, RestartWorkersUseCase, GetBrouterLogsUseCase {

    private final BrouterControlClient client;

    public BrouterStatusService(BrouterControlClient client) {
        this.client = client;
    }

    @Override
    public List<WorkerStatus> status() { return client.status(); }

    @Override
    public void rollingRestart() { client.rollingRestart(); }

    @Override
    public void restartWorker(int index) { client.restartWorker(index); }

    @Override
    public List<LogSource> listSources() { return client.listLogs(); }

    @Override
    public String tail(String name, int lines) { return client.tailLog(name, lines); }
}
