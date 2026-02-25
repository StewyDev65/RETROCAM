package com.retrocam.io;

import com.retrocam.camera.CameraAnimation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a HitFilm Composite Shot (.hfcs) file and extracts the camera animation
 * as a {@link CameraAnimation}.
 *
 * <h3>HFCS format notes</h3>
 * <ul>
 *   <li>The file is an XML document rooted at {@code <BiffCompositeShot>}.</li>
 *   <li>Camera data lives under {@code CameraLayer > LayerBase > PropertyManager}.</li>
 *   <li>Time values on keyframes are in <strong>milliseconds</strong>.</li>
 *   <li>Position keys use {@code <FXPoint3_32f X=… Y=… Z=…/>} elements.</li>
 *   <li>Orientation keys use {@code <Orientation3D X=… Y=… Z=…/>} (degrees).</li>
 *   <li>Zoom keys use {@code <float>} text content (focal length in pixels).</li>
 *   <li>All three tracks share identical time indices for iPhone-sourced data.</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * A {@link HFCSParseException} is thrown for any structural problem.
 * Callers should display the message to the user in the UI status bar.
 */
public final class HFCSImporter {

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Loads and parses the given .hfcs file, returning the first CameraLayer's
     * animation data.
     *
     * @throws HFCSParseException if the file is missing, malformed, or contains
     *                            no animated camera layer
     */
    public static CameraAnimation load(String filePath) {
        File file = new File(filePath);
        if (!file.exists())
            throw new HFCSParseException("File not found: " + filePath);

        try {
            Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(file);
            doc.getDocumentElement().normalize();
            return parse(doc);
        } catch (HFCSParseException e) {
            throw e;
        } catch (Exception e) {
            throw new HFCSParseException("Failed to parse HFCS: " + e.getMessage(), e);
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private static CameraAnimation parse(Document doc) {
        // ── Composition metadata ─────────────────────────────────────────────
        Element avSettings = requireElement(doc, "AudioVideoSettings");
        int   compHeight = Integer.parseInt(requireChild(avSettings, "Height").getTextContent().trim());
        float frameRate  = Float.parseFloat(requireChild(avSettings, "FrameRate").getTextContent().trim());

        // ── Camera layer ──────────────────────────────────────────────────────
        NodeList cameraLayers = doc.getElementsByTagName("CameraLayer");
        if (cameraLayers.getLength() == 0)
            throw new HFCSParseException("No CameraLayer found in file");

        Element cameraLayer = (Element) cameraLayers.item(0);
        Element propManager = requireDescendant(cameraLayer, "PropertyManager");

        // ── Position track ────────────────────────────────────────────────────
        Element posElem  = requireChild(propManager, "position");
        Element posAnim  = requireChild(posElem, "Animation");
        NodeList posKeys = posAnim.getElementsByTagName("Key");

        if (posKeys.getLength() == 0)
            throw new HFCSParseException("No position keyframes found in camera layer");

        int n = posKeys.getLength();
        float[] times  = new float[n];
        float[] posX   = new float[n];
        float[] posY   = new float[n];
        float[] posZ   = new float[n];
        float[] eulerX = new float[n];
        float[] eulerY = new float[n];
        float[] eulerZ = new float[n];
        float[] zoom   = new float[n];

        for (int i = 0; i < n; i++) {
            Element key  = (Element) posKeys.item(i);
            // Time is in milliseconds; convert to seconds
            times[i] = Integer.parseInt(key.getAttribute("Time")) / 1000f;

            Element value = requireChild(key, "Value");
            Element pt    = requireChild(value, "FXPoint3_32f");
            posX[i] = Float.parseFloat(pt.getAttribute("X"));
            posY[i] = Float.parseFloat(pt.getAttribute("Y"));
            posZ[i] = Float.parseFloat(pt.getAttribute("Z"));
        }

        // ── Orientation track ─────────────────────────────────────────────────
        Element oriElem  = requireChild(propManager, "orientation");
        Element oriAnim  = requireChild(oriElem, "Animation");
        NodeList oriKeys = oriAnim.getElementsByTagName("Key");

        // Orientation may have the same count or 0 if static
        int nOri = oriKeys.getLength();
        for (int i = 0; i < n; i++) {
            if (nOri > 0) {
                // Find matching key by time index (they share the same time table)
                int j = Math.min(i, nOri - 1);
                Element key   = (Element) oriKeys.item(j);
                Element value = requireChild(key, "Value");
                Element ori   = requireChild(value, "Orientation3D");
                eulerX[i] = Float.parseFloat(ori.getAttribute("X"));
                eulerY[i] = Float.parseFloat(ori.getAttribute("Y"));
                eulerZ[i] = Float.parseFloat(ori.getAttribute("Z"));
            } else {
                // Fallback: read static orientation if available
                Element staticOri = oriElem.getElementsByTagName("Static").getLength() > 0
                    ? (Element) oriElem.getElementsByTagName("Static").item(0) : null;
                if (staticOri != null) {
                    NodeList pts = staticOri.getElementsByTagName("Orientation3D");
                    if (pts.getLength() > 0) {
                        Element ori = (Element) pts.item(0);
                        float sx = Float.parseFloat(ori.getAttribute("X"));
                        float sy = Float.parseFloat(ori.getAttribute("Y"));
                        float sz = Float.parseFloat(ori.getAttribute("Z"));
                        eulerX[i] = sx; eulerY[i] = sy; eulerZ[i] = sz;
                    }
                }
                // else all zeros (identity orientation)
            }
        }

        // ── Zoom track ───────────────────────────────────────────────────────
        Element zoomElem = requireChild(propManager, "zoom");
        Element zoomAnim = requireChild(zoomElem, "Animation");
        NodeList zoomKeys = zoomAnim.getElementsByTagName("Key");
        int nZoom = zoomKeys.getLength();

        // Default zoom fallback from static value
        float staticZoom = 1000f;
        NodeList zStatic = zoomElem.getElementsByTagName("Static");
        if (zStatic.getLength() > 0) {
            NodeList floats = ((Element) zStatic.item(0)).getElementsByTagName("float");
            if (floats.getLength() > 0) {
                String txt = floats.item(0).getTextContent().trim();
                if (!txt.isEmpty()) staticZoom = Float.parseFloat(txt);
            }
        }

        for (int i = 0; i < n; i++) {
            if (nZoom > 0) {
                int j = Math.min(i, nZoom - 1);
                Element key   = (Element) zoomKeys.item(j);
                Element value = requireChild(key, "Value");
                NodeList floats = value.getElementsByTagName("float");
                if (floats.getLength() > 0) {
                    String txt = floats.item(0).getTextContent().trim();
                    zoom[i] = txt.isEmpty() ? staticZoom : Float.parseFloat(txt);
                } else {
                    zoom[i] = staticZoom;
                }
            } else {
                zoom[i] = staticZoom;
            }
        }

        System.out.printf("[HFCSImporter] Loaded %d keyframes | %.2fs | %.0f fps | %dpx comp height%n",
            n, times[n - 1], frameRate, compHeight);

        return new CameraAnimation(compHeight, frameRate,
            times, posX, posY, posZ, eulerX, eulerY, eulerZ, zoom);
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private static Element requireElement(Document doc, String tag) {
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0)
            throw new HFCSParseException("Missing element: <" + tag + ">");
        return (Element) list.item(0);
    }

    private static Element requireChild(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0)
            throw new HFCSParseException("Missing child element <" + tag + "> under <" + parent.getTagName() + ">");
        return (Element) list.item(0);
    }

    private static Element requireDescendant(Element root, String tag) {
        NodeList list = root.getElementsByTagName(tag);
        if (list.getLength() == 0)
            throw new HFCSParseException("Missing descendant <" + tag + ">");
        return (Element) list.item(0);
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    public static final class HFCSParseException extends RuntimeException {
        public HFCSParseException(String msg)                    { super(msg); }
        public HFCSParseException(String msg, Throwable cause)   { super(msg, cause); }
    }
}
