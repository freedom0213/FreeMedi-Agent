package com.mediflow.agent.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AppointmentDateParserTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void acceptsPatientDateExpressionsAndKeepsExplicitYear() {
        assertThat(AppointmentDateParser.parse("10月15号", TODAY)).isEqualTo(TODAY.plusDays(1));
        assertThat(AppointmentDateParser.parse("明天", TODAY)).isEqualTo(TODAY.plusDays(1));
        assertThat(AppointmentDateParser.parse("后天", TODAY)).isEqualTo(TODAY.plusDays(2));
        assertThat(AppointmentDateParser.parse("1月2号", TODAY)).isEqualTo(LocalDate.of(2027, 1, 2));
        assertThat(AppointmentDateParser.parse("2026年10月13号", TODAY)).isEqualTo(TODAY.minusDays(1));
        assertThat(AppointmentDateParser.parse("最近几天", TODAY)).isNull();
    }

    @Test
    void impossibleDateGetsPatientReadableErrorInsteadOfBeingSilentlyChanged() {
        assertThatThrownBy(() -> AppointmentDateParser.parse("2月30号", TODAY))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("有效的就诊日期")
                .hasMessageNotContaining("yyyy");
    }
}
