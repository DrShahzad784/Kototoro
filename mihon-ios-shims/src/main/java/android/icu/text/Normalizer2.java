package android.icu.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

public final class Normalizer2 {
    private final Normalizer.Form form;
    private final boolean casefold;

    private Normalizer2(Normalizer.Form form) {
        this(form, false);
    }

    private Normalizer2(Normalizer.Form form, boolean casefold) {
        this.form = form;
        this.casefold = casefold;
    }

    public static Normalizer2 getNFCInstance() {
        return new Normalizer2(Normalizer.Form.NFC);
    }

    public static Normalizer2 getNFDInstance() {
        return new Normalizer2(Normalizer.Form.NFD);
    }

    public static Normalizer2 getNFKCInstance() {
        return new Normalizer2(Normalizer.Form.NFKC);
    }

    public static Normalizer2 getNFKDInstance() {
        return new Normalizer2(Normalizer.Form.NFKD);
    }

    public static Normalizer2 getNFKCCasefoldInstance() {
        return new Normalizer2(Normalizer.Form.NFC, true);
    }

    public String normalize(CharSequence text) {
        if (!casefold) return Normalizer.normalize(text, form);
        StringBuilder mapped = new StringBuilder();
        text.codePoints()
                .forEach(
                        code -> {
                            String replacement = Casefold.TABLE.get(code);
                            if (replacement == null) mapped.appendCodePoint(code);
                            else mapped.append(replacement);
                        });
        return Normalizer.normalize(mapped, Normalizer.Form.NFC);
    }

    public StringBuilder normalize(CharSequence text, StringBuilder destination) {
        if (text == destination)
            throw new IllegalArgumentException("Source and destination must differ");
        destination.setLength(0);
        return destination.append(normalize(text));
    }

    public Appendable normalize(CharSequence text, Appendable destination) {
        try {
            return destination.append(normalize(text));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public StringBuilder normalizeSecondAndAppend(StringBuilder first, CharSequence second) {
        return normalize(first.toString() + second, first);
    }

    public StringBuilder append(StringBuilder first, CharSequence second) {
        return normalizeSecondAndAppend(first, second);
    }

    public boolean isNormalized(CharSequence text) {
        return casefold ? normalize(text).contentEquals(text) : Normalizer.isNormalized(text, form);
    }

    public String getDecomposition(int codePoint) {
        String original = new String(Character.toChars(codePoint));
        String decomposed =
                Normalizer.normalize(
                        original,
                        form == Normalizer.Form.NFKC || form == Normalizer.Form.NFKD
                                ? Normalizer.Form.NFKD
                                : Normalizer.Form.NFD);
        return original.equals(decomposed) ? null : decomposed;
    }

    private static final class Casefold {
        static final Map<Integer, String> TABLE = load();

        private static Map<Integer, String> load() {
            Map<Integer, String> table = new HashMap<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    java.util.Objects.requireNonNull(
                                            Normalizer2.class.getResourceAsStream(
                                                    "/org/skepsun/kototoro/ios/shims/nfkc-casefold-17.txt")),
                                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    String[] fields = line.split(";", -1), range = fields[0].split("\\.\\.");
                    int first = Integer.parseInt(range[0], 16),
                            last = range.length == 1 ? first : Integer.parseInt(range[1], 16);
                    StringBuilder output = new StringBuilder();
                    if (!fields[1].isEmpty())
                        for (String point : fields[1].split(" "))
                            output.appendCodePoint(Integer.parseInt(point, 16));
                    for (int code = first; code <= last; code++) table.put(code, output.toString());
                }
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
            return table;
        }
    }
}
