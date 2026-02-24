package com.retrocam.export;

import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.core.Renderer;
import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.post.PostProcessStack;
import com.retrocam.scene.SceneUploader;
import com.retrocam.scene.SPPMManager;

/**
 * Bundles all GPU/render system dependencies that {@link RenderPipeline} needs
 * to drive a render job. Passed to {@link RenderPipeline#tick} each frame.
 *
 * This is a plain data holder — no logic lives here. It exists solely to avoid
 * a large parameter list on the tick method and to make the dependency graph
 * explicit for future serialization or multi-context support.
 */
public final class RenderContext {

    public final Renderer        renderer;
    public final SPPMManager     sppmManager;
    public final PostProcessStack postStack;
    public final SceneUploader   sceneUploader;
    public final OrbitCamera     camera;
    public final ThinLensCamera  thinLens;
    public final TemporalState   temporal;
    public final RenderSettings  settings;

    public RenderContext(Renderer renderer,
                         SPPMManager sppmManager,
                         PostProcessStack postStack,
                         SceneUploader sceneUploader,
                         OrbitCamera camera,
                         ThinLensCamera thinLens,
                         TemporalState temporal,
                         RenderSettings settings) {
        this.renderer      = renderer;
        this.sppmManager   = sppmManager;
        this.postStack     = postStack;
        this.sceneUploader = sceneUploader;
        this.camera        = camera;
        this.thinLens      = thinLens;
        this.temporal      = temporal;
        this.settings      = settings;
    }
}