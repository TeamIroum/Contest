package kr.iroum.inspection.report;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import kr.iroum.inspection.common.web.CurrentUser;

/** 사업 총괄 기간 보고 문장(OPS-04)과 엑셀 내려받기 기록(OPS-09). service/: 원본에 없던 API다. */
@RestController
public class AdminReportController {

    private final ReportService reports;

    public AdminReportController(ReportService reports) {
        this.reports = reports;
    }

    public record Period(@NotNull LocalDate from, @NotNull LocalDate to) {
    }

    public record ExportRecord(@NotNull LocalDate from, @NotNull LocalDate to, @Size(max = 10) List<@Size(max = 30) String> sheets) {
    }

    @PostMapping("/api/v1/admin/reports/narratives")
    ReportService.Narrative narrative(Authentication auth, @Valid @RequestBody Period body) {
        return reports.generate(CurrentUser.of(auth), body.from(), body.to());
    }

    @PostMapping("/api/v1/admin/exports/records")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void exportRecord(Authentication auth, @Valid @RequestBody ExportRecord body) {
        reports.recordExport(CurrentUser.of(auth), body.from(), body.to(), body.sheets());
    }
}
