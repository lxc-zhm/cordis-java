package dev.cordis.core.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日志服务:按名字创建/复用 {@link Logger},并把日志分发给各个输出端(exporter)。
 */
public final class LoggerService {

    /** 日志输出端:接收一行格式化好的日志。 */
    public interface Exporter {
        void export(String timestamp, String level, String loggerName, String message);
    }

    private final Map<String, Logger> loggers = new HashMap<>();
    private final List<Exporter> exporters = new java.util.concurrent.CopyOnWriteArrayList<>();

    public LoggerService() {
        exporters.add((ts, level, name, message) ->
                System.out.printf("%s [%s] %s: %s%n", ts, level, name, message));
    }

    public void addExporter(Exporter exporter) {
        exporters.add(exporter);
    }

    public void removeExporter(Exporter exporter) {
        exporters.remove(exporter);
    }

    /** 取指定名字的日志器;同名复用同一个实例。 */
    public synchronized Logger logger(String name) {
        return loggers.computeIfAbsent(name, n -> new Logger(n, this));
    }

    void write(Logger logger, String level, String message) {
        String ts = Logger.timestamp();
        for (Exporter exporter : exporters) {
            try {
                exporter.export(ts, level, logger.getName(), message);
            } catch (Throwable ignored) {
                // a failing exporter must never break the caller
            }
        }
    }
}
