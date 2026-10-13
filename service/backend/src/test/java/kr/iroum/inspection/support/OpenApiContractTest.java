package kr.iroum.inspection.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 계약 검증기가 실제로 위반을 잡는지 확인한다(검증이 형식적으로 통과하지 않도록). */
class OpenApiContractTest {

    @Test
    void rejectsMissingFieldsWrongEnumAndUnknownKeys() {
        String ok = """
                {"code":"ACTIVITY_STATE_CONFLICT","message":"m","requestId":"r","fieldErrors":[],
                 "details":{"reason":"NOT_STARTED","activityStatus":"ASSIGNED"}}""";
        assertThatCode(() -> OpenApiContract.assertMatches("ErrorActivityStateConflict", ok)).doesNotThrowAnyException();
        assertThatThrownBy(() -> OpenApiContract.assertMatches("ErrorActivityStateConflict", ok.replace("NOT_STARTED", "LATE")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> OpenApiContract.assertMatches("ErrorActivityStateConflict", ok.replace("\"fieldErrors\":[],", "")))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> OpenApiContract.assertMatches("ErrorActivityStateConflict", ok.replace("\"requestId\":\"r\"",
                "\"requestId\":\"r\",\"extra\":1"))).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> OpenApiContract.assertMatches("Patrol", "{}")).isInstanceOf(AssertionError.class);
    }
}
