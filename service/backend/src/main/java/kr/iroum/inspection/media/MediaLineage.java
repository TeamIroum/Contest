package kr.iroum.inspection.media;

/**
 * 사진 사본 계보(media.source_media_id). 재가림·보관본 재처리로 만든 새 사본은 점검 시도·구도 포인터가 옮겨지지 않을 수 있다
 * (보류 중에는 승인 포인터가 풀려 있음). 그래서 점검·시설·관할 연결은 자기 자신과 모든 조상 사본을 따라 찾는다.
 * 이 연결은 조회·권한·잠금에만 쓰며 구도 채택·사람 판정을 복원하지 않는다.
 */
public final class MediaLineage {

    /** 매개변수 1개(media ID). 결과 CTE 이름은 lineage(id). WITH RECURSIVE 뒤에 붙여 쓴다. */
    public static final String CTE_BODY = """
            lineage(id) AS (
                SELECT CAST(? AS uuid)
                UNION
                SELECT m.source_media_id FROM media m JOIN lineage l ON m.id = l.id WHERE m.source_media_id IS NOT NULL
            )
            """;

    public static final String CTE = "WITH RECURSIVE " + CTE_BODY;

    /** AccessPolicy 범위 CTE(scoped) 뒤에 계보 CTE를 잇는다. 매개변수: (사용자, 역할, media ID). */
    public static String afterScoped(String scopedCte) {
        return scopedCte.stripTrailing() + ",\n" + CTE_BODY;
    }

    private MediaLineage() {
    }
}
