package io.unlogged.util;

import io.unlogged.runner.AtomicRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class FileUtils {

    private void writeToFile(File file, AtomicRecord atomicRecord) {
        File parentDir = file.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (FileOutputStream resourceFile = new FileOutputStream(file)) {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(atomicRecord);
            resourceFile.write(json.getBytes(StandardCharsets.UTF_8));
            resourceFile.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
