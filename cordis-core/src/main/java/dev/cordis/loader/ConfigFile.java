package dev.cordis.loader;

import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * YAML (including JSON) configuration I/O, with safe tags and atomic write-back.
 */
public final class ConfigFile {
    private ConfigFile() {
    }

    public static List<Entry> read(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(file)) {
            return entries(new Yaml(new SafeConstructor(options)).load(reader));
        }
    }

    public static List<Entry> entries(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Configuration must be an entry list");
        List<Entry> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : list) {
            if (item instanceof Entry entry) {
                if (!ids.add(entry.id)) throw new IllegalArgumentException("Duplicate id: " + entry.id);
                result.add(entry);
                continue;
            }
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("Entry must be a map");
            String id = required(map, "id");
            Object rawUrl = map.containsKey("url") ? map.get("url") : map.get("name");
            if (!(rawUrl instanceof String url) || url.isBlank())
                throw new IllegalArgumentException("Entry requires url/name: " + id);
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate id: " + id);
            Entry.Builder builder = Entry.of(id, url).config(map.get("config"));
            if (map.containsKey("disabled") && !(map.get("disabled") instanceof Boolean))
                throw new IllegalArgumentException("disabled must be boolean");
            builder.disabled(Boolean.TRUE.equals(map.get("disabled")));
            if (map.get("isolate") instanceof Map<?, ?> scopes)
                scopes.forEach((k, v) -> builder.isolateShared((String) k, v));
            if (map.get("intercept") instanceof Map<?, ?> intercepts)
                intercepts.forEach((k, v) -> builder.intercept((String) k, v));
            result.add(builder.build());
        }
        return List.copyOf(result);
    }

    private static String required(Map<?, ?> map, String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("Entry requires " + key);
        return value;
    }

    public static void write(Path file, List<Entry> entries) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(target.toString().endsWith(".json") ? DumperOptions.FlowStyle.FLOW : DumperOptions.FlowStyle.BLOCK);
        // JSON is emitted explicitly: YAML flow style alone does not guarantee JSON quoting.
        String text = target.toString().endsWith(".json") ? json(serialize(entries)) : new Yaml(options).dump(serialize(entries));
        Path temporary = Files.createTempFile(target.getParent(), ".cordis-", ".tmp");
        try {
            Files.writeString(temporary, text);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Object serialize(Object value) {
        if (value instanceof Entry entry) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", entry.id);
            map.put("url", entry.url);
            if (entry.disabled) map.put("disabled", true);
            if (!entry.isolate.isEmpty()) map.put("isolate", serialize(entry.isolate));
            if (!entry.intercept.isEmpty()) map.put("intercept", serialize(entry.intercept));
            if (entry.config != null) map.put("config", serialize(entry.config));
            return map;
        }
        if (value instanceof List<?> list) return list.stream().map(ConfigFile::serialize).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put((String) k, serialize(v)));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean)
            return value;
        throw new IllegalArgumentException("Configuration cannot serialize " + value.getClass());
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder escaped = new StringBuilder("\"");
            text.codePoints().forEach(c -> {
                switch (c) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (c < 32) escaped.append(String.format("\\u%04x", c));
                        else escaped.appendCodePoint(c);
                    }
                }
            });
            return escaped.append('"').toString();
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite JSON number");
            return n.toString();
        }
        if (value instanceof Boolean) return value.toString();
        if (value instanceof List<?> list)
            return "[" + String.join(",", list.stream().map(ConfigFile::json).toList()) + "]";
        if (value instanceof Map<?, ?> map)
            return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    public static ComponentLoader open(dev.cordis.core.runtime.Context parent, PluginResolver resolver, Path file) throws IOException {
        ComponentLoader loader = new ComponentLoader(parent, BuiltinPlugins.resolver(resolver, file.toAbsolutePath().getParent()));
        loader.onChange(entries -> {
            try {
                write(file, entries);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
        try {
            loader.load(read(file));
            return loader;
        } catch (Throwable error) {
            loader.close();
            throw error;
        }
    }
}
