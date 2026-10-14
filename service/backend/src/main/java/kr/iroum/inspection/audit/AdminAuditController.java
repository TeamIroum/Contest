package kr.iroum.inspection.audit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.patrol.PatrolViews.Page;

/** 감사 이력 조회(기능정의서 OPS-06, 업무 API GET /admin/audit-events). service/: 원본에 없던 API다. */
@RestController
public class AdminAuditController {

    private final AuditQueryService audits;

    public AdminAuditController(AuditQueryService audits) {
        this.audits = audits;
    }

    @GetMapping("/api/v1/admin/audit-events")
    Page<AuditQueryService.Event> list(Authentication auth, @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) UUID resourceId, @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return audits.list(CurrentUser.of(auth), resourceType, resourceId, action, from, to, page, size);
    }

    @GetMapping("/api/v1/admin/audit-events/actions")
    List<Map<String, Object>> actions(Authentication auth) {
        return audits.actions(CurrentUser.of(auth));
    }
}
