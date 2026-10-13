package kr.iroum.inspection.stats;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;

/** 사업 총괄 기간 집계(기능정의서 OPS-03). service/: 원본에 없던 API다. */
@RestController
public class AdminStatsController {

    private final StatsService stats;

    public AdminStatsController(StatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/api/v1/admin/stats")
    StatsService.Report report(Authentication auth, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return stats.report(CurrentUser.of(auth), from, to);
    }
}
