package kr.iroum.inspection.facility;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.facility.FacilityService.Facility;

/** 시설 담당자 시설 대장 관리(기능정의서 FAC-01). service/: 원본에 없던 API다. */
@RestController
@RequestMapping("/api/v1/admin/facilities")
public class AdminFacilityController {

    private final FacilityService facilities;

    public AdminFacilityController(FacilityService facilities) {
        this.facilities = facilities;
    }

    public record NewFacility(@NotBlank @Size(max = 30) String facilityCode, @NotBlank String type, @NotBlank @Size(max = 100) String name,
            BigDecimal latitude, BigDecimal longitude, @Size(max = 100) String areaLabel, @NotNull UUID managingDepartmentId, Boolean publicManaged) {
    }

    /** 좌표를 바꾸려면 changeCoordinates=true (둘 다 null이면 지움) */
    public record FacilityChange(@Size(max = 100) String name, BigDecimal latitude, BigDecimal longitude, Boolean changeCoordinates,
            @Size(max = 100) String areaLabel, String status, UUID managingDepartmentId, Boolean publicManaged, @Size(max = 500) String reason,
            @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<Facility> list(Authentication auth) {
        return facilities.list(CurrentUser.of(auth));
    }

    @GetMapping("/departments")
    List<FacilityService.Department> departments(Authentication auth) {
        return facilities.departments(CurrentUser.of(auth));
    }

    @PostMapping
    ResponseEntity<Facility> create(Authentication auth, @Valid @RequestBody NewFacility body) {
        return ResponseEntity.status(201).body(facilities.create(CurrentUser.of(auth), body.facilityCode(), body.type(), body.name(), body.latitude(),
                body.longitude(), body.areaLabel(), body.managingDepartmentId(), body.publicManaged() == null || body.publicManaged()));
    }

    @PatchMapping("/{id}")
    Facility update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody FacilityChange body) {
        return facilities.update(CurrentUser.of(auth), id, body.name(), body.latitude(), body.longitude(), Boolean.TRUE.equals(body.changeCoordinates()),
                body.areaLabel(), body.status(), body.managingDepartmentId(), body.publicManaged(), body.reason(), body.version());
    }
}
