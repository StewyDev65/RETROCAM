package com.retrocam.export;

import com.retrocam.camera.CameraView;
import com.retrocam.camera.FreeCamera;
import com.retrocam.camera.OrbitCamera;
import com.retrocam.camera.ThinLensCamera;
import com.retrocam.core.Renderer;
import com.retrocam.core.RenderSettings;
import com.retrocam.core.TemporalState;
import com.retrocam.gl.ShaderProgram;
import com.retrocam.post.PostProcessStack;
import com.retrocam.scene.SceneEditor;
import com.retrocam.scene.SceneUploader;
import com.retrocam.scene.SPPMManager;

/**
 * Bundles all GPU/render system dependencies that {@link RenderPipeline} needs.
 *
 * {@link #activeCamera} is mutable — Main.loop() sets it to either
 * {@code orbitCamera} or {@code freeCamera} each frame depending on the
 * active camera mode.
 */
public final class RenderContext {

    public final Renderer         renderer;
    public final SPPMManager      sppmManager;
    public final PostProcessStack postStack;
    public final SceneUploader    sceneUploader;
    public final SceneEditor      sceneEditor;
    public final OrbitCamera      orbitCamera;
    public final FreeCamera       freeCamera;
    public final ThinLensCamera   thinLens;
    public final TemporalState    temporal;
    public final RenderSettings   settings;
    public final ShaderProgram    displayShader;
    public final int              fullscreenVao;

    /**
     * The camera used for the current tick. Updated by Main each frame to
     * either {@link #orbitCamera} or {@link #freeCamera}.
     */
    public CameraView activeCamera;

    public RenderContext(Renderer renderer,
                         SPPMManager sppmManager,
                         PostProcessStack postStack,
                         SceneUploader sceneUploader,
                         SceneEditor sceneEditor,
                         OrbitCamera orbitCamera,
                         FreeCamera freeCamera,
                         ThinLensCamera thinLens,
                         TemporalState temporal,
                         RenderSettings settings,
                         ShaderProgram displayShader,
                         int fullscreenVao) {
        this.renderer       = renderer;
        this.sppmManager    = sppmManager;
        this.postStack      = postStack;
        this.sceneUploader  = sceneUploader;
        this.sceneEditor    = sceneEditor;
        this.orbitCamera    = orbitCamera;
        this.freeCamera     = freeCamera;
        this.thinLens       = thinLens;
        this.temporal       = temporal;
        this.settings       = settings;
        this.displayShader  = displayShader;
        this.fullscreenVao  = fullscreenVao;
        this.activeCamera   = orbitCamera; // default to orbit
    }
}
