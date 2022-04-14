package scaffolding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;

public class StringUtils {
    public static String randomStringOfLength(int numberOfCharacters) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(numberOfCharacters);
        for (int i = 0; i < numberOfCharacters; i++) {
            char c = (char) (rng.nextInt(30000) + 33);
            sb.append(c);
        }
        return sb.toString();
    }
    public static String randomAsciiStringOfLength(int numberOfCharacters) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(numberOfCharacters);
        for (int i = 0; i < numberOfCharacters; i++) {
            char c = (char) (rng.nextInt(89) + 33);
            sb.append(c);
        }
        return sb.toString();
    }
    public static byte[] randomBytes(int len) {
        byte[] res = new byte[len];
        Random rng = new Random();
        rng.nextBytes(res);
        return res;
    }

    private static String readFile(String name) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Paths.get("src", "test", "resources", "web", "static", name));
        } catch (IOException e) {
            throw new RuntimeException("Error loading " + name, e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public static final String HELLO_HTML = readFile("hello.html");
    public static final String SMALL_FILE_HTML = readFile("small-file.html");
    public static final String LARGE_TXT = readFile("large-txt-file.txt");

}