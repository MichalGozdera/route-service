package velomarker.service.planning.alns2;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeCacheTest {

    @Test
    void miss_then_hit() {
        EdgeCache cache = new EdgeCache();
        int[] computeCount = {0};
        EdgeCache.EdgeInfo info1 = cache.getOrCompute(14.5, 50.0, 15.5, 50.0, pts -> {
            computeCount[0]++;
            return new EdgeCache.EdgeInfo(80.0, 100.0, 90.0);
        });
        EdgeCache.EdgeInfo info2 = cache.getOrCompute(14.5, 50.0, 15.5, 50.0, pts -> {
            computeCount[0]++;
            return new EdgeCache.EdgeInfo(999.0, 999.0, 999.0); // shouldn't be called
        });
        assertThat(computeCount[0]).isEqualTo(1);
        assertThat(info1.distanceKm()).isEqualTo(80.0);
        assertThat(info2.distanceKm()).isEqualTo(80.0); // same instance from cache
        assertThat(cache.hits()).isEqualTo(1);
        assertThat(cache.misses()).isEqualTo(1);
    }

    @Test
    void directional_key_distinct() {
        EdgeCache cache = new EdgeCache();
        cache.getOrCompute(14.5, 50.0, 15.5, 50.0, pts -> new EdgeCache.EdgeInfo(80.0, 0, 80));
        cache.getOrCompute(15.5, 50.0, 14.5, 50.0, pts -> new EdgeCache.EdgeInfo(82.0, 0, 82));
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void hit_ratio() {
        EdgeCache cache = new EdgeCache();
        for (int i = 0; i < 5; i++) {
            cache.getOrCompute(14, 50, 15, 50, pts -> new EdgeCache.EdgeInfo(10, 0, 10));
        }
        assertThat(cache.hits()).isEqualTo(4);
        assertThat(cache.misses()).isEqualTo(1);
        assertThat(cache.hitRatio()).isEqualTo(0.8);
    }

    // === v3.16: ledger realnych strzałów per powód ===

    @Test
    void realCalls_tallied_per_reason() {
        // onRealCall() liczy realny strzał pod BIEŻĄCYM powodem (loader woła go przy brouter.apply).
        EdgeCache cache = new EdgeCache();
        cache.setReason("grow");
        cache.onRealCall();
        cache.onRealCall();
        cache.setReason("ogonek-relokacja");
        cache.onRealCall();
        assertThat(cache.realCalls()).isEqualTo(3);
        assertThat(cache.realCallsByReason()).containsEntry("grow", 2L).containsEntry("ogonek-relokacja", 1L);
    }

    @Test
    void realCalls_notBumpedBySlicedSeed() {
        // seedSlicedEdges seeduje cache loaderem BEZ brouter.apply (gotowy EdgeInfo) → miss++, ale
        // realCalls NIE rośnie (to było źródło zawyżania: misses ≠ realne strzały).
        EdgeCache cache = new EdgeCache();
        cache.setReason("grow");
        cache.getOrCompute(14.0, 50.0, 14.1, 50.0, pts -> new EdgeCache.EdgeInfo(5, 0, 5)); // sliced-seed, brak onRealCall
        assertThat(cache.misses()).isEqualTo(1);
        assertThat(cache.realCalls()).isZero();
        assertThat(cache.realCallsByReason()).isEmpty();
    }
}
