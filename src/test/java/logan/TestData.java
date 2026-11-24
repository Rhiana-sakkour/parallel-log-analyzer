package logan;

import logan.gen.LogGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates temporary input files for the tests. */
final class TestData {
    private static Path dir;
    private TestData() { }

    static synchronized Path dir() throws IOException {
        if (dir == null) {
            dir = Files.createTempDirectory("logan-tests");
            dir.toFile().deleteOnExit();
        }
        return dir;
    }

    static Path generated(long lines, long seed) throws IOException {
        Path p = dir().resolve("gen_" + lines + "_" + seed + ".log");
        if (!Files.exists(p)) LogGenerator.generate(p, lines, seed, null);
        return p;
    }

    static Path text(String name, String content) throws IOException {
        Path p = dir().resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
        p.toFile().deleteOnExit();
        return p;
    }

    static final String GOOD_LINE =
        "10.0.0.1 - - [05/Mar/2026:14:05:33 +0000] \"GET /api/items/7?x=1 HTTP/1.1\" 200 512 37";
}
