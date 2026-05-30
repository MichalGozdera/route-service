package velomarker.port.in;

public interface RestartWorkersUseCase {
    void rollingRestart();
    void restartWorker(int index);
}
