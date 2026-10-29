package com.agentruntime.device;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

/** Resolves links in existing ancestors, including when the output file does not exist yet. */
final class CanonicalFiles {
    private CanonicalFiles() { }

    static File resolve(File file) throws IOException {
        Path path = file.getAbsoluteFile().toPath();
        ArrayDeque<String> missing = new ArrayDeque<>();
        while (path != null && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(path.getFileName().toString());
            path = path.getParent();
        }
        if (path == null) throw new IOException("no existing ancestor");
        Path resolved = path.toRealPath();
        for (String segment : missing) resolved = resolved.resolve(segment);
        return resolved.normalize().toFile();
    }
}
