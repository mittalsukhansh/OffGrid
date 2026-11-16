package com.example.offgrid;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/** Guard every untrusted JSON document, including decrypted controls and identity backups. */
public final class JsonBounds {
    private JsonBounds() {}

    public static JSONObject object(String json, int maxBytes) throws JSONException {
        if (json == null
                || json.length() > maxBytes
                || json.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new JSONException("JSON exceeds size bound");
        int depth = 0;
        boolean quoted = false, escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 16) throw new JSONException("JSON nesting exceeds bound");
            } else if (c == '}' || c == ']') depth--;
        }
        return new JSONObject(json);
    }
}
