package kr.iroum.inspection.route;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.route.RouteService.Route;
import kr.iroum.inspection.route.RouteService.StopInput;

/** 활동 담당자 코스·버전 관리(기능정의서 ACT-03). service/: 원본에 없던 API다. */
@RestController
@RequestMapping("/api/v1/admin/routes")
public class AdminRouteController {

    private final RouteService routes;

    public AdminRouteController(RouteService routes) {
        this.routes = routes;
    }

    public record NewRoute(@NotNull UUID organizationId, @NotBlank @Size(max = 100) String name, @NotNull UUID departureBaseId,
            @NotNull UUID returnBaseId, @NotNull @Size(min = 1, max = 30) List<StopInput> stops) {
    }

    public record NewVersion(@NotNull UUID departureBaseId, @NotNull UUID returnBaseId, @NotNull @Size(min = 1, max = 30) List<StopInput> stops,
            @NotNull @PositiveOrZero Long version) {
    }

    public record RouteChange(@Size(max = 100) String name, String status, @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<Route> list(Authentication auth, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return routes.list(CurrentUser.of(auth), includeInactive);
    }

    @GetMapping("/options")
    RouteService.Options options(Authentication auth) {
        return routes.options(CurrentUser.of(auth));
    }

    @PostMapping
    ResponseEntity<Route> create(Authentication auth, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody NewRoute body) {
        var outcome = routes.create(CurrentUser.of(auth), body.organizationId(), body.name(), body.departureBaseId(), body.returnBaseId(), body.stops(),
                key);
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @PatchMapping("/{routeId}")
    Route update(Authentication auth, @PathVariable UUID routeId, @Valid @RequestBody RouteChange body) {
        return routes.update(CurrentUser.of(auth), routeId, body.name(), body.status(), body.version());
    }

    @PostMapping("/{routeId}/versions")
    Route newVersion(Authentication auth, @PathVariable UUID routeId, @Valid @RequestBody NewVersion body) {
        return routes.newVersion(CurrentUser.of(auth), routeId, body.departureBaseId(), body.returnBaseId(), body.stops(), body.version());
    }
}
