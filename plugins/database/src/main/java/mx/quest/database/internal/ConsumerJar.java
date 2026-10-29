package mx.quest.database.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Where a consumer's migration scripts come from: <b>only</b> the jar its {@code DatabaseConsumer} bean was loaded
 * from, never "the first hit" on the class loader every plugin shares. The jar also proves the plugin id: the
 * loader requires a plugin's install folder to be named after its id, and the jar sits directly in it.
 */
public final class ConsumerJar implements ScriptSource {

    private final String pluginId;
    private final Path jar;

    private ConsumerJar(String pluginId, Path jar) {
        this.pluginId = pluginId;
        this.jar = jar;
    }

    /**
     * The jar a bean's class was loaded from, checked against the plugin id it claims.
     *
     * @throws IllegalArgumentException when the class does not come from a jar directly inside a folder named
     *                                  {@code pluginId} (a library in {@code lib/}, a class folder, another plugin)
     */
    public static ConsumerJar of(Class<?> beanClass, String pluginId) {
        CodeSource source = beanClass.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalArgumentException("cannot tell which jar " + beanClass.getName() + " comes from");
        }
        Path path;
        try {
            path = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IllegalArgumentException("cannot tell which jar " + beanClass.getName() + " comes from");
        }
        Path folder = path.getParent();
        if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar") || folder == null
                || folder.getFileName() == null || !folder.getFileName().toString().equals(pluginId)) {
            throw new IllegalArgumentException("the DatabaseConsumer bean " + beanClass.getName()
                    + " is not in the jar of plugin '" + pluginId + "' (it comes from " + path + ")");
        }
        return new ConsumerJar(pluginId, path);
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public Optional<byte[]> read(String entry) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry e = file.getEntry(entry);
            if (e == null || e.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream in = file.getInputStream(e)) {
                byte[] bytes = in.readNBytes(Scripts.MAX_SCRIPT_BYTES + 1);
                if (bytes.length > Scripts.MAX_SCRIPT_BYTES) {
                    throw new IOException(entry + " is larger than " + Scripts.MAX_SCRIPT_BYTES + " bytes");
                }
                return Optional.of(bytes);
            }
        }
    }

    @Override
    public String toString() {
        return jar.toString();
    }
}
