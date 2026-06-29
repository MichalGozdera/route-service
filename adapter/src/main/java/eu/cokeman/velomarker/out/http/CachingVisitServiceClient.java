package eu.cokeman.velomarker.out.http;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import velomarker.port.out.planning.AreaPool;
import velomarker.port.out.planning.SpecialGroupRef;
import velomarker.port.out.planning.VisitServiceClient;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Dekorator {@link VisitServiceClient} z dwupoziomowym cache (Caffeine).
 *
 * <ul>
 *   <li><b>catalogCache</b> (TTL 1h): dane administracyjne wspólne dla wszystkich userów — kraje, poziomy,
 *       grupy specjalne. Granice administracyjne nie zmieniają się często.</li>
 *   <li><b>userCache</b> (TTL 3min): pokrycie i lista nieodwiedzonych per user. Klucz = subject (userId),
 *       NIE bearer token (bo token się zmienia przy refresh).</li>
 * </ul>
 *
 * <p>@Primary — wstrzykiwany zamiast gołego {@link VisitServiceHttpClient}.
 */
@Component
@Primary
public class CachingVisitServiceClient implements VisitServiceClient {

    private final VisitServiceClient delegate;
    private final Cache<String, Object> userCache = Caffeine.newBuilder()
            .expireAfterWrite(3, TimeUnit.MINUTES).maximumSize(10_000).build();
    private final Cache<String, Object> catalogCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS).maximumSize(1_000).build();

    public CachingVisitServiceClient(@Qualifier("visitServiceHttpClient") VisitServiceClient delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    private <T> T user(String bearer, String key, Supplier<T> loader) {
        // Klucz cache = subject z JWT (deterministyczny per user), nie bearer (zmienia się przy refresh).
        String subject = subjectFromBearer(bearer);
        return (T) userCache.get(subject + "|" + key, k -> loader.get());
    }

    @SuppressWarnings("unchecked")
    private <T> T catalog(String key, Supplier<T> loader) {
        return (T) catalogCache.get(key, k -> loader.get());
    }

    // ── per-user (3 min) ──────────────────────────────────────────────────────────
    @Override
    public AreaPool listAreaPool(String bearer, int countryId, int levelId, int limit) {
        return user(bearer, "areas|" + countryId + "|" + levelId + "|" + limit,
                () -> delegate.listAreaPool(bearer, countryId, levelId, limit));
    }

    @Override
    public AreaPool listSpecialAreaPool(String bearer, int groupId, Integer countryId, int limit) {
        return user(bearer, "special|" + groupId + "|" + countryId + "|" + limit,
                () -> delegate.listSpecialAreaPool(bearer, groupId, countryId, limit));
    }

    // ── katalog wspólny (1h) ────────────────────────────────────────────────────────
    @Override
    public List<SpecialGroupRef> listSpecialGroupsCatalog(String bearer) {
        return catalog("specialGroups", () -> delegate.listSpecialGroupsCatalog(bearer));
    }

    @Override
    public java.util.Map<Integer, String> listLevelNames(String bearer) {
        return catalog("levelNames", () -> delegate.listLevelNames(bearer));
    }

    /** Wyciąga subject (sub/user_id) z JWT do deterministycznego klucza cache (nie zmienia się przy refresh). */
    private static String subjectFromBearer(String bearer) {
        if (bearer == null || bearer.isBlank()) {
            return "anonymous";
        }
        try {
            String token = bearer.startsWith("Bearer ") ? bearer.substring(7) : bearer;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return "anonymous";
            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                    java.nio.charset.StandardCharsets.UTF_8);
            int subStart = payload.indexOf("\"user_id\":\"");
            if (subStart < 0) {
                subStart = payload.indexOf("\"sub\":\"");
                if (subStart < 0) return "anonymous";
                subStart += 7;
            } else {
                subStart += 11;
            }
            int subEnd = payload.indexOf("\"", subStart);
            return subEnd < 0 ? "anonymous" : payload.substring(subStart, subEnd);
        } catch (Exception e) {
            return "anonymous";
        }
    }
}
