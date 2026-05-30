package eu.cokeman.velomarker.out;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Czyste funkcje czytnika HGT: wykrycie rozdzielczości z rozmiaru, nazwa kafla, biliniowa interpolacja z bufora
 * (wiersz 0 = północ, kolumna 0 = zachód) + obsługa void.
 */
class LocalHgtElevationTest {

    @Test
    void resolutionFromBytesDetects3and1arcsec() {
        assertThat(LocalHgtElevationClient.resolutionFromBytes(1201L * 1201 * 2)).isEqualTo(1201);   // 3″
        assertThat(LocalHgtElevationClient.resolutionFromBytes(3601L * 3601 * 2)).isEqualTo(3601);   // 1″
        assertThat(LocalHgtElevationClient.resolutionFromBytes(12345)).isZero();                     // śmieć
        assertThat(LocalHgtElevationClient.resolutionFromBytes(0)).isZero();
    }

    @Test
    void tileFileNameUsesSwCorner() {
        assertThat(LocalHgtElevationClient.tileFileName(52.2, 21.0)).isEqualTo("N52E021.hgt");
        assertThat(LocalHgtElevationClient.tileFileName(50.999, 14.001)).isEqualTo("N50E014.hgt");
        assertThat(LocalHgtElevationClient.tileFileName(-5.3, -120.7)).isEqualTo("S06W121.hgt");   // floor → -6, -121
    }

    /** Bufor 3×3: value(r,c)=r*10+c. row 0 = północ (lat 51), row 2 = południe (lat 50); col 0 = zach. (lon 14). */
    private static ByteBuffer grid3() {
        ByteBuffer b = ByteBuffer.allocate(3 * 3 * 2).order(ByteOrder.BIG_ENDIAN);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                b.putShort((short) (r * 10 + c));
            }
        }
        return b;
    }

    @Test
    void cornersMapToExactSamples() {
        ByteBuffer g = grid3();
        // NW (lat 51, lon 14) = (row0,col0)=0 ; SE (lat 50, lon 15) = (row2,col2)=22 ; środek = (1,1)=11
        assertThat(LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 51.0, 14.0)).isCloseTo(0, within(1e-6));
        assertThat(LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 50.0, 15.0)).isCloseTo(22, within(1e-6));
        assertThat(LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 50.5, 14.5)).isCloseTo(11, within(1e-6));
    }

    @Test
    void bilinearBetweenSamples() {
        ByteBuffer g = grid3();
        // lat 50.75 (rowF=0.5), lon 14.25 (colF=0.5): top=lerp(0,1)=0.5, bot=lerp(10,11)=10.5, res=lerp(0.5,10.5)=5.5
        assertThat(LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 50.75, 14.25)).isCloseTo(5.5, within(1e-6));
    }

    @Test
    void clampsOutsideTile() {
        ByteBuffer g = grid3();
        // lat poniżej kafla → clamp do wiersza południowego (2); lon poza → clamp do kolumny wschodniej (2) → 22
        assertThat(LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 49.0, 16.0)).isCloseTo(22, within(1e-6));
    }

    @Test
    void voidSampleFallsBackToNeighbor() {
        ByteBuffer g = grid3();
        g.putShort(0, (short) -32768);   // (0,0) = void
        // róg NW (51,14): v00=void → interpolacja bierze sąsiada (kolumna 1 = 1), nie 0/śmieć
        double v = LocalHgtElevationClient.elevationFromBuffer(g, 3, 50, 14, 51.0, 14.0);
        assertThat(v).isCloseTo(1, within(1e-6));
    }
}
