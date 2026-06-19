package eu.cokeman.velomarker.in.rest;

import eu.cokeman.velomarker.mapper.Polyline3DCodec;
import eu.cokeman.velomarker.mapper.RouteDraftExternalMapper;
import eu.cokeman.velomarker.openapi.api.DraftsApi;
import eu.cokeman.velomarker.openapi.model.RouteDraftGroupGeometriesResponseDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftGroupGeometryItemDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftRequestDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftResponseDto;
import eu.cokeman.velomarker.openapi.model.RouteDraftSummaryDto;
import eu.cokeman.velomarker.security.UserContextHelper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import velomarker.entity.RouteDraft;
import velomarker.exception.RouteDraftNotFoundException;
import velomarker.port.in.RouteDraftUseCase;
import velomarker.port.in.RouteDraftUseCase.RouteDraftCreateCommand;
import velomarker.port.in.RouteDraftUseCase.RouteDraftUpdateCommand;

import java.util.List;
import java.util.UUID;

@RestController
public class RouteDraftsController implements DraftsApi {

    private final RouteDraftUseCase useCase;
    private final RouteDraftExternalMapper mapper;
    private final UserContextHelper userContext;

    public RouteDraftsController(RouteDraftUseCase useCase,
                                 RouteDraftExternalMapper mapper,
                                 UserContextHelper userContext) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.userContext = userContext;
    }

    @Override
    public ResponseEntity<List<RouteDraftSummaryDto>> listRouteDrafts() {
        UUID userId = userContext.getCurrentUserId();
        List<RouteDraftSummaryDto> body = useCase.listForUser(userId).stream()
                .map(mapper::toSummaryDto)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<RouteDraftResponseDto> createRouteDraft(RouteDraftRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        RouteDraft created = useCase.create(new RouteDraftCreateCommand(
                userId,
                req.getName(),
                mapper.fromGeoJsonRequest(req),
                req.getProfile(),
                req.getDistanceKm(),
                req.getElevationGain(),
                req.getElevationLoss(),
                req.getGroupId(),
                req.getGroupName(),
                req.getDayNumber(),
                req.getWaypoints(),
                mapper.fromStatsDto(req.getStats())
        ));
        return ResponseEntity.status(201).body(mapper.toResponseDto(created));
    }

    @Override
    public ResponseEntity<RouteDraftResponseDto> getRouteDraft(UUID draftId) {
        UUID userId = userContext.getCurrentUserId();
        return ResponseEntity.ok(mapper.toResponseDto(useCase.getForUser(userId, draftId)));
    }

    @Override
    public ResponseEntity<RouteDraftResponseDto> updateRouteDraft(UUID draftId, RouteDraftRequestDto req) {
        UUID userId = userContext.getCurrentUserId();
        RouteDraft updated = useCase.update(new RouteDraftUpdateCommand(
                userId,
                draftId,
                req.getName(),
                mapper.fromGeoJsonRequest(req),
                req.getProfile(),
                req.getDistanceKm(),
                req.getElevationGain(),
                req.getElevationLoss(),
                req.getGroupId(),
                req.getGroupName(),
                req.getDayNumber(),
                req.getWaypoints(),
                mapper.fromStatsDto(req.getStats())
        ));
        return ResponseEntity.ok(mapper.toResponseDto(updated));
    }

    @Override
    public ResponseEntity<Void> deleteRouteDraft(UUID draftId) {
        UUID userId = userContext.getCurrentUserId();
        useCase.delete(userId, draftId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<String> exportRouteDraftGpx(UUID draftId) {
        UUID userId = userContext.getCurrentUserId();
        RouteDraft draft = useCase.getForUser(userId, draftId);
        return gpxResponse(buildGpx(List.of(draft)), draft.name());
    }

    @Override
    public ResponseEntity<String> exportRouteGroupGpx(UUID groupId) {
        UUID userId = userContext.getCurrentUserId();
        List<RouteDraft> days = useCase.getGroupForUser(userId, groupId);
        if (days.isEmpty()) {
            throw new RouteDraftNotFoundException(groupId); // 404 — pusta/cudza grupa
        }
        String groupName = days.get(0).groupName() != null ? days.get(0).groupName() : "wyprawa";
        return gpxResponse(buildGpx(days), groupName);
    }

    /** Bulk geometrie wszystkich dni grupy w JEDNYM żądaniu (Polyline3DCodec encoded). Zastępuje N×GET
     *  /route/drafts/{id} przy ładowaniu wyprawy na mapie — dla 2000 dni: ~30 s ⇒ ~1-2 s. Frontend dekoduje
     *  przez {@code decodePolyline3D}. Sortowane po dayNumber (kolejność z {@link RouteDraftUseCase#getGroupForUser}). */
    @Override
    public ResponseEntity<RouteDraftGroupGeometriesResponseDto> getRouteDraftGroupGeometries(UUID groupId) {
        UUID userId = userContext.getCurrentUserId();
        List<RouteDraft> days = useCase.getGroupForUser(userId, groupId);
        if (days.isEmpty()) {
            throw new RouteDraftNotFoundException(groupId);
        }
        List<RouteDraftGroupGeometryItemDto> items = days.stream()
                .map(d -> {
                    RouteDraftGroupGeometryItemDto item = new RouteDraftGroupGeometryItemDto();
                    item.setDayNumber(d.dayNumber() != null ? d.dayNumber() : 0);
                    item.setGeometryEncoded(Polyline3DCodec.encode(d.coordinates()));
                    item.setDistanceKm(d.distanceKm());
                    item.setStats(mapper.toStatsDto(d.stats()));
                    return item;
                })
                .toList();
        RouteDraftGroupGeometriesResponseDto resp = new RouteDraftGroupGeometriesResponseDto();
        resp.setGroupId(groupId);
        resp.setDays(items);
        return ResponseEntity.ok(resp);
    }

    private static ResponseEntity<String> gpxResponse(String gpx, String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gpx+xml"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safe + ".gpx\"")
                .body(gpx);
    }

    /** GPX 1.1: jeden {@code <trk>} na szkic (dzień), trkpt = [lon=c[0], lat=c[1]], {@code <ele>}=c[2] gdy 3D.
     *  Wiele dni (cała wyprawa) = wiele {@code <trk>} w kolejności przekazanej listy (posortowane po dayNumber). */
    private static String buildGpx(List<RouteDraft> drafts) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"velomarker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (RouteDraft d : drafts) {
            sb.append("  <trk>\n    <name>").append(xmlEscape(d.name())).append("</name>\n    <trkseg>\n");
            for (double[] c : d.coordinates()) {
                sb.append("      <trkpt lat=\"").append(c[1]).append("\" lon=\"").append(c[0]).append("\">");
                if (c.length >= 3) {
                    sb.append("<ele>").append(c[2]).append("</ele>");
                }
                sb.append("</trkpt>\n");
            }
            sb.append("    </trkseg>\n  </trk>\n");
        }
        sb.append("</gpx>\n");
        return sb.toString();
    }

    private static String xmlEscape(String v) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
