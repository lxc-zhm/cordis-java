package dev.cordis.loader;

import dev.cordis.core.Plugin;
import dev.cordis.hmr.ModuleGraph;

import javax.tools.*;
import java.net.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Source-directory modules compiled as an isolated generation with the current JDK.
 * Core Cordis API classes come from the host classloader; source classes come from the generation.
 */
public final class JavaModuleResolver implements PluginResolver, ModuleGraph, AutoCloseable {
    private final Path sourceRoot;
    private final String classpath;
    private final List<Generation> generations = new ArrayList<>();
    private Generation current;
    private Generation pending;
    private boolean closed;

    public JavaModuleResolver(Path sourceRoot) throws IOException {
        this(sourceRoot, hostClasspath());
    }

    private static String hostClasspath() {
        Set<String> paths = new LinkedHashSet<>(List.of(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        try {
            paths.add(Path.of(Plugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (Exception error) {
            throw new IllegalStateException("Cannot locate Cordis API classes", error);
        }
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent())
            if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs())
                if (url.getProtocol().equals("file"))
                    try {
                        paths.add(Path.of(url.toURI()).toString());
                    } catch (URISyntaxException error) {
                        throw new IllegalArgumentException(error);
                    }
        return String.join(File.pathSeparator, paths);
    }

    public JavaModuleResolver(Path sourceRoot, String classpath) throws IOException {
        this.sourceRoot = sourceRoot.toRealPath();
        this.classpath = classpath;
    }

    public Path sourceRoot() {
        return sourceRoot;
    }

    public String url(Path source) {
        try {
            return source.toRealPath().toUri().toString();
        } catch (IOException ignored) {
            return source.toAbsolutePath().normalize().toUri().toString();
        }
    }

    public synchronized Set<String> urls() throws IOException {
        return Set.copyOf(scan().keySet());
    }

    private String canonical(String url) {
        return url.startsWith("file:") ? this.url(Path.of(URI.create(url))) : this.url(sourceRoot.resolve(url));
    }

    @Override
    public synchronized Plugin resolve(String url) throws Exception {
        if (closed) throw new IllegalStateException("Resolver is closed");
        if (current == null && pending == null) current = compile();
        return (pending != null ? pending : current).resolve(canonical(url));
    }

    /**
     * Compile and initialize all sources before the transaction changes any fiber.
     */
    public synchronized Transaction beginReload() throws Exception {
        if (closed || pending != null) throw new IllegalStateException("Resolver closed or reload already running");
        pending = compile();
        return new Transaction(pending);
    }

    public final class Transaction implements AutoCloseable {
        private final Generation generation;
        private boolean finished;

        private Transaction(Generation generation) {
            this.generation = generation;
        }

        public void commit() {
            synchronized (JavaModuleResolver.this) {
                if (finished) return;
                current = generation;
                pending = null;
                finished = true;
            }
        }

        @Override
        public void close() {
            synchronized (JavaModuleResolver.this) {
                if (!finished) {
                    pending = null;
                    finished = true;
                }
            }
        }
    }

    private record Source(Path path, String name, String text) {
    }

    private Map<String, Source> scan() throws IOException {
        Map<String, Source> sources = new LinkedHashMap<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(file);
                Matcher matcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(text);
                String packageName = matcher.find() ? matcher.group(1) + "." : "";
                String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
                sources.put(url(file), new Source(file, packageName + simple, text));
            }
        }
        return sources;
    }

    @Override
    public synchronized Set<String> imports(String url) {
        try {
            Map<String, Source> sources = scan();
            Source source = sources.get(canonical(url));
            if (source == null) return Set.of();
            Set<String> dependencies = new LinkedHashSet<>();
            // Conservative source reference graph. Extra edges cause extra reloads, never stale class reuse.
            for (var entry : sources.entrySet()) {
                if (entry.getValue() == source) continue;
                String name = entry.getValue().name();
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(source.text()).find())
                    dependencies.add(entry.getKey());
            }
            return Set.copyOf(dependencies);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Generation compile() throws Exception {
        Map<String, Source> sources = scan();
        if (sources.isEmpty()) throw new IllegalArgumentException("No Java sources in " + sourceRoot);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("HMR requires a JDK, not a JRE");
        Path output = Files.createTempDirectory("cordis-module-");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(sources.values().stream().map(Source::path).toList());
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "-classpath", classpath, "-d", output.toString()), null, units).call());
            if (!success)
                throw new IllegalArgumentException("Module compilation failed:\n" + String.join("\n", diagnostics.getDiagnostics().stream().map(Object::toString).toList()));
            Set<String> names = new HashSet<>();
            sources.values().forEach(source -> names.add(source.name()));
            URLClassLoader classes = new URLClassLoader(new URL[]{output.toUri().toURL()}, Plugin.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        boolean local = names.stream().anyMatch(n -> name.equals(n) || name.startsWith(n + "$"));
                        if (!local) return super.loadClass(name, resolve);
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
            };
            Generation generation = new Generation(output, classes, sources);
            generations.add(generation);
            return generation;
        } catch (Exception error) {
            delete(output);
            throw error;
        }
    }

    private static final class Generation implements AutoCloseable {
        final Path output;
        final URLClassLoader classes;
        final Map<String, Source> sources;
        final Map<String, Plugin> plugins = new HashMap<>();

        Generation(Path output, URLClassLoader classes, Map<String, Source> sources) {
            this.output = output;
            this.classes = classes;
            this.sources = sources;
        }

        Plugin resolve(String url) throws Exception {
            if (plugins.containsKey(url)) return plugins.get(url);
            Source source = sources.get(url);
            if (source == null) throw new IllegalArgumentException("Module not found: " + url);
            Object instance = Class.forName(source.name(), true, classes).getDeclaredConstructor().newInstance();
            if (!(instance instanceof Plugin plugin))
                throw new IllegalArgumentException("Module does not implement Plugin: " + url);
            plugins.put(url, plugin);
            return plugin;
        }

        @Override
        public void close() throws IOException {
            classes.close();
            delete(output);
        }
    }

    private static void delete(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /**
     * Close only after disposing all fibers loaded from this resolver.
     */
    @Override
    public synchronized void close() throws IOException {
        closed = true;
        IOException failure = null;
        for (Generation generation : generations)
            try {
                generation.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        generations.clear();
        current = pending = null;
        if (failure != null) throw failure;
    }
}
