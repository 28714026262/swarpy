package cn.swapy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new Problem("INVALID_JSON", e.getMessage()); }
    }
    public static <T> T read(String text, Class<T> type) {
        try { return MAPPER.readValue(text, type); }
        catch (Exception e) { throw new Problem("INVALID_JSON", e.getMessage()); }
    }
    public static Map<String,Object> object(String text) {
        try { return MAPPER.readValue(text,new TypeReference<>() {}); }
        catch (Exception e) { throw new Problem("INVALID_JSON", e.getMessage()); }
    }
}
