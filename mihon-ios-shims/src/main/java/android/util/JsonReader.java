package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayDeque;

/**
 * Streaming JSON pull reader. It keeps one token and the container stack, never the whole response.
 */
public final class JsonReader implements Closeable {
    private static final int DOCUMENT = 0,
            ARRAY_FIRST = 1,
            ARRAY_NEXT = 2,
            OBJECT_FIRST = 3,
            OBJECT_NEXT = 4,
            VALUE = 5,
            DONE = 6;
    private final Reader reader;
    private final ArrayDeque<Integer> stack = new ArrayDeque<>();
    private int lookahead = -2;
    private boolean lenient, closed;
    private JsonToken token;
    private String value;

    public JsonReader(Reader reader) {
        this.reader = java.util.Objects.requireNonNull(reader);
        stack.push(DOCUMENT);
    }

    public void setLenient(boolean lenient) {
        this.lenient = lenient;
    }

    public boolean isLenient() {
        return lenient;
    }

    private int take() throws IOException {
        int c = lookahead;
        lookahead = -2;
        return c == -2 ? reader.read() : c;
    }

    private int charPeek() throws IOException {
        if (lookahead == -2) lookahead = reader.read();
        return lookahead;
    }

    private int nonspace() throws IOException {
        int c;
        while ((c = take()) != -1) {
            if (Character.isWhitespace(c)) continue;
            if (lenient && c == '/') {
                int next = charPeek();
                if (next == '/') {
                    take();
                    while ((c = take()) != -1 && c != '\n')
                        ;
                    continue;
                }
                if (next == '*') {
                    take();
                    int previous = 0;
                    while ((c = take()) != -1 && !(previous == '*' && c == '/')) previous = c;
                    if (c == -1) throw malformed("Unterminated comment");
                    continue;
                }
            }
            return c;
        }
        return -1;
    }

    private IOException malformed(String message) {
        return new IOException("Malformed JSON: " + message);
    }

    private void state(int state) {
        stack.pop();
        stack.push(state);
    }

    public JsonToken peek() throws IOException {
        if (closed) throw new IllegalStateException("Reader is closed");
        if (token != null) return token;
        int state = stack.peek(), c = nonspace();
        if (state == DONE) {
            if (c == -1) return token = JsonToken.END_DOCUMENT;
            if (!lenient) throw malformed("Trailing data");
        }
        if (state == ARRAY_FIRST && c == ']') return token = JsonToken.END_ARRAY;
        if (state == ARRAY_NEXT) {
            if (c == ']') return token = JsonToken.END_ARRAY;
            if (c != ',' && !(lenient && c == ';')) throw malformed("Expected ','");
            c = nonspace();
        }
        if (state == OBJECT_FIRST || state == OBJECT_NEXT) {
            if (c == '}') return token = JsonToken.END_OBJECT;
            if (state == OBJECT_NEXT) {
                if (c != ',' && !(lenient && c == ';')) throw malformed("Expected ','");
                c = nonspace();
            }
            if (c == '"' || (lenient && c == '\'')) value = quoted(c);
            else if (lenient && c >= 0) value = literal(c);
            else throw malformed("Expected object name");
            return token = JsonToken.NAME;
        }
        if (state == VALUE) {
            if (c != ':') throw malformed("Expected ':'");
            c = nonspace();
        }
        if (c == '[') return token = JsonToken.BEGIN_ARRAY;
        if (c == '{') return token = JsonToken.BEGIN_OBJECT;
        if (c == '"' || (lenient && c == '\'')) {
            value = quoted(c);
            return token = JsonToken.STRING;
        }
        if (c == -1) throw malformed("Unexpected end of input");
        value = literal(c);
        if (value.equals("true") || value.equals("false")) return token = JsonToken.BOOLEAN;
        if (value.equals("null")) return token = JsonToken.NULL;
        if (value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            return token = JsonToken.NUMBER;
        if (lenient) return token = JsonToken.STRING;
        throw malformed("Invalid literal " + value);
    }

    private String literal(int first) throws IOException {
        StringBuilder out = new StringBuilder().append((char) first);
        int c;
        while ((c = charPeek()) >= 0 && !Character.isWhitespace(c) && ",;]}:=/#".indexOf(c) < 0)
            out.append((char) take());
        return out.toString();
    }

    private String quoted(int quote) throws IOException {
        StringBuilder out = new StringBuilder();
        int c;
        while ((c = take()) != -1) {
            if (c == quote) return out.toString();
            if (c < 32 && !lenient) throw malformed("Control character in string");
            if (c == '\\') {
                c = take();
                switch (c) {
                    case '"':
                    case '\\':
                    case '/':
                        break;
                    case 'b':
                        c = '\b';
                        break;
                    case 'f':
                        c = '\f';
                        break;
                    case 'n':
                        c = '\n';
                        break;
                    case 'r':
                        c = '\r';
                        break;
                    case 't':
                        c = '\t';
                        break;
                    case 'u':
                        c = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(take(), 16);
                            if (digit < 0) throw malformed("Invalid Unicode escape");
                            c = c * 16 + digit;
                        }
                        break;
                    default:
                        if (!lenient || c == -1) throw malformed("Invalid escape");
                }
            }
            out.append((char) c);
        }
        throw malformed("Unterminated string");
    }

    private void expect(JsonToken expected) throws IOException {
        if (peek() != expected)
            throw new IllegalStateException("Expected " + expected + " but was " + token);
    }

    private void consumed() {
        token = null;
        value = null;
        int s = stack.peek();
        state(s == ARRAY_FIRST || s == ARRAY_NEXT ? ARRAY_NEXT : s == VALUE ? OBJECT_NEXT : DONE);
    }

    public void beginArray() throws IOException {
        expect(JsonToken.BEGIN_ARRAY);
        consumed();
        stack.push(ARRAY_FIRST);
    }

    public void endArray() throws IOException {
        expect(JsonToken.END_ARRAY);
        token = null;
        stack.pop();
    }

    public void beginObject() throws IOException {
        expect(JsonToken.BEGIN_OBJECT);
        consumed();
        stack.push(OBJECT_FIRST);
    }

    public void endObject() throws IOException {
        expect(JsonToken.END_OBJECT);
        token = null;
        stack.pop();
    }

    public boolean hasNext() throws IOException {
        JsonToken t = peek();
        return t != JsonToken.END_ARRAY && t != JsonToken.END_OBJECT && t != JsonToken.END_DOCUMENT;
    }

    public String nextName() throws IOException {
        expect(JsonToken.NAME);
        String out = value;
        token = null;
        value = null;
        state(VALUE);
        return out;
    }

    public String nextString() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.STRING && t != JsonToken.NUMBER)
            throw new IllegalStateException("Expected string");
        String out = value;
        consumed();
        return out;
    }

    public boolean nextBoolean() throws IOException {
        expect(JsonToken.BOOLEAN);
        boolean out = Boolean.parseBoolean(value);
        consumed();
        return out;
    }

    public void nextNull() throws IOException {
        expect(JsonToken.NULL);
        consumed();
    }

    private String number() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.NUMBER && t != JsonToken.STRING)
            throw new IllegalStateException("Expected number");
        return value;
    }

    public double nextDouble() throws IOException {
        double out = Double.parseDouble(number());
        if (!lenient && !Double.isFinite(out)) throw malformed("Non-finite number");
        consumed();
        return out;
    }

    public long nextLong() throws IOException {
        try {
            long out = new BigDecimal(number()).longValueExact();
            consumed();
            return out;
        } catch (ArithmeticException error) {
            throw new NumberFormatException(error.getMessage());
        }
    }

    public int nextInt() throws IOException {
        try {
            int out = new BigDecimal(number()).intValueExact();
            consumed();
            return out;
        } catch (ArithmeticException error) {
            throw new NumberFormatException(error.getMessage());
        }
    }

    public void skipValue() throws IOException {
        switch (peek()) {
            case BEGIN_ARRAY:
                beginArray();
                while (hasNext()) skipValue();
                endArray();
                break;
            case BEGIN_OBJECT:
                beginObject();
                while (hasNext()) {
                    nextName();
                    skipValue();
                }
                endObject();
                break;
            case NAME:
                nextName();
                skipValue();
                break;
            case END_ARRAY:
            case END_OBJECT:
            case END_DOCUMENT:
                throw new IllegalStateException("No value to skip");
            default:
                consumed();
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        token = null;
        stack.clear();
        reader.close();
    }
}
