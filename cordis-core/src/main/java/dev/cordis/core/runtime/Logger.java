package dev.cordis.core.runtime;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 命名日志器:info / warn / error / debug 四个级别。
 */
public final class Logger {

    private final String name;
    private final LoggerService service;

    Logger(String name, LoggerService service) {
        this.name = name;
        this.service = service;
    }

    public String getName() {
        return name;
    }

    public void info(String format, Object... args) {
        log("I", format, args);
    }

    public void warn(String format, Object... args) {
        log("W", format, args);
    }

    public void error(String format, Object... args) {
        log("E", format, args);
    }

    public void error(Throwable t) {
        log("E", "{}", t);
    }

    public void debug(String format, Object... args) {
        log("D", format, args);
    }

    private void log(String level, String format, Object... args) {
        String message = format;
        for (Object arg : args) {
            String rendered = arg instanceof Throwable t ? stackOf(t) : String.valueOf(arg);
            int idx = message.indexOf("{}");
            message = idx >= 0
                    ? message.substring(0, idx) + rendered + message.substring(idx + 2)
                    : message + " " + rendered;
        }
        service.write(this, level, message);
    }

    private static String stackOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString().replace("\n", "\n  ");
    }

    static String timestamp() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
}
