package kr.iroum.inspection.media.web;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;
import kr.iroum.inspection.media.MediaContentService;

/** 권한 검사되는 사진 스트리밍(업무 API 9절). 브라우저·중간 캐시에 남기지 않는다(Cache-Control: no-store). */
@RestController
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaContentService media;

    public MediaController(MediaContentService media) {
        this.media = media;
    }

    @GetMapping("/{mediaId}/content")
    ResponseEntity<byte[]> content(Authentication auth, @PathVariable UUID mediaId) {
        MediaContentService.Content c = media.content(CurrentUser.of(auth), mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.contentType())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(c.bytes());
    }
}
