package kr.iroum.inspection.issue.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.issue.IssueLinkService;

/** 사건 ↔ 점검 사진 연결 해제(기능정의서 FAC-06). service/: 원본에 없던 API다. */
@RestController
public class AdminIssueLinkController {

    private final IssueLinkService links;

    public AdminIssueLinkController(IssueLinkService links) {
        this.links = links;
    }

    public record Unlink(@NotBlank @Size(max = 500) String reason, @NotNull @PositiveOrZero Long version) {
    }

    @DeleteMapping("/api/v1/admin/issues/{issueId}/inspection-links/{inspectionId}")
    IssueLinkService.Unlinked unlink(Authentication auth, @PathVariable UUID issueId, @PathVariable UUID inspectionId, @Valid @RequestBody Unlink body) {
        return links.unlink(CurrentUser.of(auth), issueId, inspectionId, body.reason(), body.version());
    }
}
