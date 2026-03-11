package cn.net.rms.serverflashback.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import cn.net.rms.serverflashback.record.FlashbackMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ReplayExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("serverflashback");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void export(Path recordFolder, Path outputFile, String name) {
        LOGGER.info("Exporting {} to {}", recordFolder, outputFile);

        FlashbackMeta meta = tryReadMeta(recordFolder.resolve("metadata.json"));
        if (meta == null) {
            meta = tryReadMeta(recordFolder.resolve("metadata.json.old"));
        }
        if (meta == null) {
            LOGGER.error("Cannot export, both metadata files are invalid");
            return;
        }

        if (name != null) {
            meta.name = name;
        }

        meta.chunks.keySet().removeIf(chunkName -> {
            Path chunkPath = recordFolder.resolve(chunkName);
            if (!Files.exists(chunkPath)) {
                LOGGER.warn("Cannot find chunk path: {}, skipping", chunkPath);
                return true;
            }
            return false;
        });

        if (meta.chunks.isEmpty()) {
            LOGGER.error("Cannot export, no chunk files exist");
            return;
        }

        try {
            Files.createDirectories(outputFile.getParent());
        } catch (IOException e) {
            LOGGER.error("Unable to create parent directories", e);
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             ZipOutputStream zipOut = new ZipOutputStream(bos)) {

            zipOut.setLevel(Deflater.BEST_SPEED);

            zipOut.putNextEntry(new ZipEntry("metadata.json"));
            zipOut.write(GSON.toJson(meta.toJson()).getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            Path levelChunkCaches = recordFolder.resolve("level_chunk_caches");
            if (Files.exists(levelChunkCaches) && Files.isDirectory(levelChunkCaches)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(levelChunkCaches)) {
                    for (Path path : ds) {
                        zipOut.putNextEntry(new ZipEntry("level_chunk_caches/" + path.getFileName()));
                        Files.copy(path, zipOut);
                        zipOut.closeEntry();
                    }
                }
            }

            Path levelChunkCachePath = recordFolder.resolve("level_chunk_cache");
            if (Files.exists(levelChunkCachePath)) {
                zipOut.putNextEntry(new ZipEntry("level_chunk_cache"));
                Files.copy(levelChunkCachePath, zipOut);
                zipOut.closeEntry();
            }

            for (String chunkName2 : meta.chunks.keySet()) {
                Path chunkPath = recordFolder.resolve(chunkName2);
                zipOut.putNextEntry(new ZipEntry(chunkName2));
                Files.copy(chunkPath, zipOut);
                zipOut.closeEntry();
            }

            deleteDirectory(recordFolder);
        } catch (Exception e) {
            LOGGER.error("Exception exporting replay", e);
        }
    }

    private static FlashbackMeta tryReadMeta(Path file) {
        if (!Files.exists(file)) return null;
        try {
            String s = Files.readString(file);
            if (s.isBlank()) return null;
            JsonObject obj = GSON.fromJson(s, JsonObject.class);
            if (obj.size() == 0) return null;
            return FlashbackMeta.fromJson(obj);
        } catch (Exception e) {
            LOGGER.error("Exception reading metadata", e);
            return null;
        }
    }

    private static void deleteDirectory(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                    for (Path entry : entries) deleteDirectory(entry);
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOGGER.error("Failed to delete: {}", dir, e);
        }
    }
}
