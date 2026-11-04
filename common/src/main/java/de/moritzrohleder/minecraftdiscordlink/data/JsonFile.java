package de.moritzrohleder.minecraftdiscordlink.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A JSON data file of the mod in {@code minecraftdiscordlink/}, such as the links or the player statistics.
 * <p>
 * Files are written atomically (to a temporary file that then replaces the old one), so a crash never leaves a
 * half-written file. A file that exists but cannot be read is never overwritten: an error is logged and the data
 * starts empty, so nothing stored is lost until the server admin fixes or removes the file.
 *
 * @param <T> the type of the file content, a record or class Gson can (de)serialize
 */
public final class JsonFile<T> {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Class<T> type;
    /** Whether the file may be written; {@code false} if it exists but could not be read. */
    private boolean writable = true;

    /**
     * Creates access to a data file; nothing is read before {@link #read()}.
     *
     * @param file the file
     * @param type the type of the file content
     */
    public JsonFile(Path file, Class<T> type) {
        this.file = file;
        this.type = type;
    }

    /**
     * Reads the file.
     *
     * @return the content, {@code null} if the file does not exist, is empty or cannot be read
     */
    public T read() {
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            writable = false;
            Constants.LOG.error("Could not read {}: {}. The data starts empty and the file is not changed until it "
                    + "is fixed or removed.", file, e.getMessage());
            return null;
        }
    }

    /**
     * Writes the file atomically, creating missing directories. Does nothing if the file could not be read before.
     * Errors are logged.
     *
     * @param content the content to write
     */
    public void write(T content) {
        if (!writable) {
            return;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(content, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Constants.LOG.error("Could not write {}: {}", file, e.getMessage());
        }
    }
}
