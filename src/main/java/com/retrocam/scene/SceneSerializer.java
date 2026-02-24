package com.retrocam.scene;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves and loads a {@link SceneEditor} to/from a human-readable JSON file.
 *
 * <p>Uses a minimal hand-written recursive-descent parser so no external JSON
 * library is required.  The format is a flat object with two top-level arrays:
 * {@code "materials"} and {@code "objects"}.
 */
public final class SceneSerializer {

    private SceneSerializer() {} // utility class

    // ═════════════════════════════════════════════════════════════════════════
    // Save
    // ═════════════════════════════════════════════════════════════════════════

    public static void save(SceneEditor editor, String path) throws IOException {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");

        // Materials
        List<Material> mats   = editor.getMaterials();
        List<String>   mnames = editor.getMatNames();
        sb.append("  \"materials\": [\n");
        for (int i = 0; i < mats.size(); i++) {
            Material m = mats.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ")            .append(jStr(mnames.get(i)))           .append(",\n");
            sb.append("      \"albedo\": [")         .append(m.albedo[0]).append(", ").append(m.albedo[1]).append(", ").append(m.albedo[2]).append("],\n");
            sb.append("      \"metallic\": ")        .append(m.metallic)                    .append(",\n");
            sb.append("      \"emission\": [")       .append(m.emission[0]).append(", ").append(m.emission[1]).append(", ").append(m.emission[2]).append("],\n");
            sb.append("      \"emissionStrength\": ").append(m.emissionStrength)            .append(",\n");
            sb.append("      \"roughness\": ")       .append(m.roughness)                   .append(",\n");
            sb.append("      \"ior\": ")             .append(m.ior)                         .append(",\n");
            sb.append("      \"transmission\": ")    .append(m.transmission)                .append("\n");
            sb.append("    }").append(i < mats.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        // Objects
        List<SceneObject> objs = editor.getObjects();
        sb.append("  \"objects\": [\n");
        for (int i = 0; i < objs.size(); i++) {
            SceneObject o = objs.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ")          .append(jStr(o.name))       .append(",\n");
            sb.append("      \"type\": ")          .append(jStr(o.type.name())).append(",\n");
            sb.append("      \"px\": ").append(o.px).append(", \"py\": ").append(o.py).append(", \"pz\": ").append(o.pz).append(",\n");
            sb.append("      \"sx\": ").append(o.sx).append(", \"sy\": ").append(o.sy).append(", \"sz\": ").append(o.sz).append(",\n");
            sb.append("      \"stacks\": ").append(o.stacks).append(", \"slices\": ").append(o.slices).append(",\n");
            sb.append("      \"materialIndex\": ").append(o.materialIndex).append("\n");
            sb.append("    }").append(i < objs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");

        Files.writeString(Path.of(path), sb.toString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Load
    // ═════════════════════════════════════════════════════════════════════════

    public static SceneEditor load(String path) throws IOException {
        String json = Files.readString(Path.of(path));
        SceneEditor editor = new SceneEditor();

        // ── Materials ─────────────────────────────────────────────────────────
        String matsBody = arrayBody(json, "materials");
        if (matsBody != null) {
            for (String obj : splitObjects(matsBody)) {
                Material m  = new Material();
                String name = strField(obj, "name");
                float[] alb = floatArr(obj, "albedo");
                float[] em  = floatArr(obj, "emission");
                if (alb != null && alb.length == 3) m.albedo    = alb;
                if (em  != null && em.length  == 3) m.emission  = em;
                m.metallic         = floatField(obj, "metallic");
                m.emissionStrength = floatField(obj, "emissionStrength");
                m.roughness        = floatField(obj, "roughness");
                m.ior              = floatField(obj, "ior");
                if (m.ior < 0.01f) m.ior = 1.5f;   // guard against missing field
                m.transmission     = floatField(obj, "transmission");
                editor.addMaterial(name != null ? name : "Material", m);
            }
        }

        // ── Objects ───────────────────────────────────────────────────────────
        String objsBody = arrayBody(json, "objects");
        if (objsBody != null) {
            for (String obj : splitObjects(objsBody)) {
                String name   = strField(obj, "name");
                String typeStr= strField(obj, "type");
                float px = floatField(obj, "px"), py = floatField(obj, "py"), pz = floatField(obj, "pz");
                float sx = floatField(obj, "sx"), sy = floatField(obj, "sy"), sz = floatField(obj, "sz");
                int stacks = (int) floatField(obj, "stacks");
                int slices = (int) floatField(obj, "slices");
                int matIdx = (int) floatField(obj, "materialIndex");

                SceneObject.Type t = "SPHERE".equalsIgnoreCase(typeStr)
                        ? SceneObject.Type.SPHERE : SceneObject.Type.BOX;
                SceneObject so = new SceneObject(
                        name != null ? name : "Object", t,
                        px, py, pz, sx, sy, sz,
                        Math.max(0, matIdx));
                if (stacks > 2) so.stacks = stacks;
                if (slices > 2) so.slices = slices;
                editor.addObject(so);
            }
        }

        editor.markDirty();
        return editor;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Minimal JSON helpers
    // ═════════════════════════════════════════════════════════════════════════

    /** Extracts the inner content of the first top-level named JSON array. */
    private static String arrayBody(String json, String key) {
        String token = "\"" + key + "\"";
        int ki = json.indexOf(token);
        if (ki < 0) return null;
        int start = json.indexOf('[', ki + token.length());
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) return json.substring(start + 1, i); }
        }
        return null;
    }

    /** Splits an array body string into individual JSON object strings. */
    private static List<String> splitObjects(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) { out.add(body.substring(start, i + 1)); start = -1; } }
        }
        return out;
    }

    /** Extracts a string value for the given key; returns {@code null} if absent. */
    private static String strField(String obj, String key) {
        String token = "\"" + key + "\"";
        int ki = obj.indexOf(token);
        if (ki < 0) return null;
        int open = obj.indexOf('"', ki + token.length());
        // skip colon and whitespace to find actual opening quote
        int scan = ki + token.length();
        while (scan < obj.length() && obj.charAt(scan) != '"') scan++;
        if (scan >= obj.length()) return null;
        int close = scan + 1;
        while (close < obj.length()) {
            char c = obj.charAt(close);
            if (c == '\\') { close += 2; continue; }
            if (c == '"') break;
            close++;
        }
        return obj.substring(scan + 1, close).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Extracts a numeric value for the given key; returns {@code 0} if absent. */
    private static float floatField(String obj, String key) {
        String token = "\"" + key + "\"";
        int ki = obj.indexOf(token);
        if (ki < 0) return 0f;
        int i = ki + token.length();
        // skip past colon and whitespace
        while (i < obj.length() && (obj.charAt(i) == ':' || obj.charAt(i) == ' ' || obj.charAt(i) == '\t' || obj.charAt(i) == '\n' || obj.charAt(i) == '\r')) i++;
        if (i >= obj.length()) return 0f;
        int end = i;
        while (end < obj.length()) {
            char c = obj.charAt(end);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E') end++;
            else break;
        }
        try { return end > i ? Float.parseFloat(obj.substring(i, end)) : 0f; }
        catch (NumberFormatException x) { return 0f; }
    }

    /** Extracts a JSON float array {@code [a, b, c, ...]} for the given key; returns {@code null} if absent. */
    private static float[] floatArr(String obj, String key) {
        String token = "\"" + key + "\"";
        int ki = obj.indexOf(token);
        if (ki < 0) return null;
        int start = obj.indexOf('[', ki + token.length());
        int end   = obj.indexOf(']', start > 0 ? start : 0);
        if (start < 0 || end < 0) return null;
        String[] parts = obj.substring(start + 1, end).split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Float.parseFloat(parts[i].trim()); }
            catch (NumberFormatException x) { result[i] = 0f; }
        }
        return result;
    }

    /** Wraps a string in JSON quotes with basic escape. */
    private static String jStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}