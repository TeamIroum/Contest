package kr.iroum.inspection.issue.web;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.issue.IssueQueryService;
import kr.iroum.inspection.issue.IssueViews.IssueDetail;
import kr.iroum.inspection.issue.IssueViews.IssueItem;
import kr.iroum.inspection.review.ReviewViews.Page;

/** 시설 담당자 문제 조회(업무 API 10절). */
@RestController
@RequestMapping("/api/v1/admin/issues")
public class AdminIssueController {

    enum IssueStatus {
        OPEN, REPAIR_REQUESTED, IN_REPAIR, AWAITING_VERIFICATION, CLOSED
    }

    private final IssueQueryService queries;

    public AdminIssueController(IssueQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    Page<IssueItem> list(Authentication auth, @RequestParam(required = false) IssueStatus status, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID facilityId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queries.list(CurrentUser.of(auth), status == null ? null : status.name(), departmentId, facilityId, page, size);
    }

    @GetMapping("/{issueId}")
    IssueDetail detail(Authentication auth, @PathVariable UUID issueId) {
        return queries.detail(CurrentUser.of(auth), issueId);
    }
}
