package kr.iroum.inspection.inspection;

import static kr.iroum.inspection.support.OpenApiContract.assertMatches;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.jayway.jsonpath.JsonPath;

/** 시설 문제 목록·상세(업무 API 10절, ISSUE-01): 현재 관할·역할, 등록순·필터, 연결 사진·판정 이력, 등급 비노출. mock AI. */
class IssueQueryIntegrationTest extends IssueTestBase {

    @Test
    void listAndDetailShowCurrentJurisdictionIssueWithEvidenceAndDecision() throws Exception {
        String list = api.get(manager, "/api/v1/admin/issues").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].id").value(issue.toString()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN")).andExpect(jsonPath("$.items[0].defectCode").value("covered"))
                .andExpect(jsonPath("$.items[0].facility.type").value("DRAIN")).andExpect(jsonPath("$.items[0].linkedInspectionCount").value(1))
                .andExpect(jsonPath("$.items[0].assignedDepartment.id").value(f.department.toString()))
                .andReturn().getResponse().getContentAsString();
        assertMatches("Issues", list);
        assertThat(list).doesNotContain("grade", "severity", "priority");
        String detail = api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(status().isOk())
                .andExpect(jsonPath("$.facility.managingDepartment.id").value(f.department.toString()))
                .andExpect(jsonPath("$.inspections[0].inspectionId").value(inspection.toString()))
                .andExpect(jsonPath("$.inspections[0].media.contentPath").value(org.hamcrest.Matchers.startsWith("/api/v1/media/")))
                .andExpect(jsonPath("$.inspections[0].linkedBy.name").value("정○○")).andExpect(jsonPath("$.inspections[0].unlinkedAt").isEmpty())
                .andExpect(jsonPath("$.reviews[0].mode").value("CREATE")).andExpect(jsonPath("$.reviews[0].analysisResultId").value(analysis.toString()))
                .andExpect(jsonPath("$.reviews[0].classificationVersion").value(0))
                .andExpect(jsonPath("$.repairEvents.length()").value(0)).andExpect(jsonPath("$.reinspectionRequests.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertMatches("Issue", detail);
        assertThat(detail).doesNotContain("grade", "severity", "/images/");
    }

    @Test
    void filtersAndPagingKeepRegistrationOrder() throws Exception {
        UUID second = jdbc.queryForObject("""
                INSERT INTO issues (program_id, facility_id, defect_code, summary, status, assigned_department_id, opened_at)
                VALUES (?, ?, 'lifted', '보도블록 들뜸', 'REPAIR_REQUESTED', ?, (SELECT opened_at + interval '1 hour' FROM issues WHERE id = ?))
                RETURNING id""", UUID.class, f.program, f.facilities.get(0), f.department, issue);
        List<String> ids = JsonPath.read(api.get(manager, "/api/v1/admin/issues").andReturn().getResponse().getContentAsString(), "$.items[*].id");
        assertThat(ids).containsExactly(issue.toString(), second.toString());
        api.get(manager, "/api/v1/admin/issues?status=REPAIR_REQUESTED").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()));
        api.get(manager, "/api/v1/admin/issues?facilityId=" + f.facilities.get(1)).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(issue.toString()));
        api.get(manager, "/api/v1/admin/issues?departmentId=" + UUID.randomUUID()).andExpect(jsonPath("$.totalElements").value(0));
        api.get(manager, "/api/v1/admin/issues?size=1&page=1").andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second.toString())).andExpect(jsonPath("$.totalElements").value(2));
        api.get(manager, "/api/v1/admin/issues?status=FIXED").andExpect(status().isBadRequest());
    }

    @Test
    void otherDepartmentAndOtherRolesCannotSeeIssue() throws Exception {
        UUID otherDepartment = f.org("DEPARTMENT", null, "가상 공원과");
        UUID other = f.user("한○○");
        f.role(other, "FACILITY_MANAGER", otherDepartment);
        api.get(other, "/api/v1/admin/issues").andExpect(jsonPath("$.totalElements").value(0));
        api.get(other, "/api/v1/admin/issues/" + issue).andExpect(status().isNotFound());
        api.get(f.manager, "/api/v1/admin/issues").andExpect(status().isForbidden());
        api.get(p.users().get(0), "/api/v1/admin/issues/" + issue).andExpect(status().isForbidden());
        api.get(manager, "/api/v1/admin/issues/" + UUID.randomUUID()).andExpect(status().isNotFound());
        // 관할 이관은 다음 요청부터 반영된다(현재 관할 기준).
        jdbc.update("UPDATE facilities SET managing_department_id = ? WHERE id = ?", otherDepartment, f.facilities.get(1));
        api.get(other, "/api/v1/admin/issues/" + issue).andExpect(status().isOk());
        api.get(manager, "/api/v1/admin/issues/" + issue).andExpect(status().isNotFound());
    }
}
