package kr.iroum.inspection.organization;

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
import kr.iroum.inspection.organization.OrganizationService.Organization;

/** 사업 총괄 기관·거점 관리(기능정의서 OPS-01). service/: 원본에 없던 API다. */
@RestController
@RequestMapping("/api/v1/admin/organizations")
public class AdminOrganizationController {

    private final OrganizationService organizations;

    public AdminOrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    public record NewOrganization(@NotBlank String type, @NotBlank @Size(max = 100) String name, UUID parentId) {
    }

    /** parentId를 바꾸려면 changeParent=true (null이면 최상위로) */
    public record OrganizationChange(@Size(max = 100) String name, String status, UUID parentId, Boolean changeParent, @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    List<Organization> list(Authentication auth) {
        return organizations.list(CurrentUser.of(auth));
    }

    @PostMapping
    ResponseEntity<Organization> create(Authentication auth, @Valid @RequestBody NewOrganization body) {
        return ResponseEntity.status(201).body(organizations.create(CurrentUser.of(auth), body.type(), body.name(), body.parentId()));
    }

    @PatchMapping("/{id}")
    Organization update(Authentication auth, @PathVariable UUID id, @Valid @RequestBody OrganizationChange body) {
        return organizations.update(CurrentUser.of(auth), id, body.name(), body.status(), body.parentId(), Boolean.TRUE.equals(body.changeParent()),
                body.version());
    }
}
