package velomarker.exception;

/**
 * Rzucany gdy BRouter HTTP 400 z message {@code datafile X.rd5 not found}. Wyodrębniony
 * z {@link BrouterUpstreamException} bo to akcjonalny błąd usera: brakuje pliku DEM tile
 * dla danego obszaru i user może go pobrać z {@code http://brouter.de/brouter/segments4/X.rd5}.
 * Algorytm zbiera nazwy brakujących tile'ów i raportuje w Plan summary jako sumę z linkiem
 * do pobrania. Bez tego wyodrębnienia ginie w setkach „target-island" warnów BRoutera.
 */
public class BrouterMissingTileException extends BrouterUpstreamException {

    private final String tileName;

    public BrouterMissingTileException(String tileName, String message) {
        super(message);
        this.tileName = tileName;
    }

    public String tileName() {
        return tileName;
    }

    @Override public String errorCode() { return "BROUTER_MISSING_TILE"; }
}
