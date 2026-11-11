package lightide.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
    private Json() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.parse();
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0, false);
        out.append('\n');
        return out.toString();
    }

    public static Map<String, Object> object(Object value) {
        if (value instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) value;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return map;
        }
        throw new IllegalArgumentException("需要 JSON 对象");
    }

    public static List<Object> array(Object value) {
        if (value instanceof List) {
            List<?> raw = (List<?>) value;
            return new ArrayList<Object>(raw);
        }
        throw new IllegalArgumentException("需要 JSON 数组");
    }

    public static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public static int integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    public static long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("需要数字");
    }

    public static boolean bool(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        throw new IllegalArgumentException("需要布尔值");
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, int indent, boolean pretty) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            out.append('{');
            int i = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (i++ > 0) {
                    out.append(',');
                }
                if (pretty) {
                    newline(out, indent + 1);
                }
                writeString(out, entry.getKey());
                out.append(pretty ? ": " : ":");
                write(out, entry.getValue(), indent + 1, pretty);
            }
            if (pretty && i > 0) {
                newline(out, indent);
            }
            out.append('}');
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                if (pretty) {
                    newline(out, indent + 1);
                }
                write(out, list.get(i), indent + 1, pretty);
            }
            if (pretty && !list.isEmpty()) {
                newline(out, indent);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("无法写成 JSON: " + value.getClass().getName());
        }
    }

    public static String writePretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0, true);
        out.append('\n');
        return out.toString();
    }

    private static void newline(StringBuilder out, int indent) {
        out.append('\n');
        for (int i = 0; i < indent; i++) {
            out.append("  ");
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = readValue();
            skip();
            if (index != text.length()) {
                throw error("JSON 末尾有多余内容");
            }
            return value;
        }

        private Object readValue() {
            skip();
            if (index >= text.length()) {
                throw error("JSON 意外结束");
            }
            char c = text.charAt(index);
            if (c == '{') {
                return readObject();
            }
            if (c == '[') {
                return readArray();
            }
            if (c == '"') {
                return readString();
            }
            if (c == 't') {
                return readLiteral("true", Boolean.TRUE);
            }
            if (c == 'f') {
                return readLiteral("false", Boolean.FALSE);
            }
            if (c == 'n') {
                return readLiteral("null", null);
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumber();
            }
            throw error("无法识别的 JSON");
        }

        private Map<String, Object> readObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                skip();
                if (index >= text.length() || text.charAt(index) != '"') {
                    throw error("对象键必须是字符串");
                }
                String key = readString();
                skip();
                expect(':');
                map.put(key, readValue());
                skip();
                if (peek('}')) {
                    index++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            index++;
            List<Object> list = new ArrayList<Object>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(',');
            }
        }

        private String readString() {
            index++;
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw error("字符串转义未结束");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            out.append(escaped);
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (index + 4 > text.length()) {
                                throw error("\\u 转义不完整");
                            }
                            int code = Integer.parseInt(text.substring(index, index + 4), 16);
                            out.append((char) code);
                            index += 4;
                            break;
                        default:
                            throw error("未知转义");
                    }
                } else if (c < 0x20) {
                    throw error("字符串里有未转义的控制字符");
                } else {
                    out.append(c);
                }
            }
            throw error("字符串没有结束");
        }

        private Object readNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                index++;
            }
            boolean decimal = false;
            if (index < text.length() && text.charAt(index) == '.') {
                decimal = true;
                index++;
                while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                    index++;
                }
            }
            String literal = text.substring(start, index);
            if (decimal) {
                return Double.valueOf(literal);
            }
            return Long.valueOf(literal);
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw error("无法识别的 JSON");
            }
            index += literal.length();
            return value;
        }

        private void skip() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private boolean peek(char c) {
            return index < text.length() && text.charAt(index) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("缺少 '" + c + "'");
            }
            index++;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + "，位置 " + index);
        }
    }
}
