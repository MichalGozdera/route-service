package velomarker.port.in;

import velomarker.port.out.BrouterControlClient.LogSource;

import java.util.List;

public interface GetBrouterLogsUseCase {
    List<LogSource> listSources();
    String tail(String name, int lines);
}
