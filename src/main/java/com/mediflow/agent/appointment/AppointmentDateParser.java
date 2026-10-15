package com.mediflow.agent.appointment;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/** Normalizes common patient date expressions at the tool boundary. */
public final class AppointmentDateParser {
    private static final Pattern MONTH_DAY = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?");

    private AppointmentDateParser() { }

    public static LocalDate parse(String value, LocalDate today) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.matches("最近.*|尽快|近期|最早.*|这几天")) return null;
        if (text.equals("今天")) return today;
        if (text.equals("明天")) return today.plusDays(1);
        if (text.equals("后天")) return today.plusDays(2);
        try {
            var match = MONTH_DAY.matcher(text);
            if (match.matches()) {
                int year = match.group(1) == null ? today.getYear() : Integer.parseInt(match.group(1));
                LocalDate date = LocalDate.of(year, Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
                return match.group(1) == null && date.isBefore(today) ? date.plusYears(1) : date;
            }
            return LocalDate.parse(text);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("没有识别出有效的就诊日期，请换一种说法，例如明天或10月15号");
        }
    }
}
