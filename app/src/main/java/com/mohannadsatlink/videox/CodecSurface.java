package com.mohannadsatlink.videox;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class CodecSurface implements SurfaceTexture.OnFrameAvailableListener {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private final Object frameSync = new Object();
    private boolean frameAvailable;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture texture;
    private Surface decoderSurface;
    private int program, textureId, aPosition, aTexCoord, uMatrix;
    private final float[] matrix = new float[16];
    private final FloatBuffer vertices;

    private final int outputWidth, outputHeight;
    private final HandlerThread callbackThread = new HandlerThread("VideoX-frames");

    CodecSurface(Surface encoderInput, int outputWidth, int outputHeight, int sourceWidth, int sourceHeight) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        float left = 0f, right = 1f, top = 0f, bottom = 1f;
        float sourceAspect = sourceWidth / (float) sourceHeight;
        float outputAspect = outputWidth / (float) outputHeight;
        if (Math.abs(sourceAspect - outputAspect) > 0.01f) {
            if (sourceAspect > outputAspect) {
                float visible = outputAspect / sourceAspect;
                left = (1f - visible) / 2f; right = 1f - left;
            } else {
                float visible = sourceAspect / outputAspect;
                top = (1f - visible) / 2f; bottom = 1f - top;
            }
        }
        float[] data = {
                -1f, -1f, 0f, left, top,
                 1f, -1f, 0f, right, top,
                -1f,  1f, 0f, left, bottom,
                 1f,  1f, 0f, right, bottom
        };
        vertices = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertices.put(data).position(0);
        setupEgl(encoderInput);
        makeCurrent();
        setupGl();
    }

    private void setupEgl(Surface surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) fail("eglInitialize");
        int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0);
        int[] ctx = {EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctx, 0);
        int[] surf = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surf, 0);
    }

    private void setupGl() {
        String vertex = "attribute vec4 aPosition;attribute vec4 aTexCoord;uniform mat4 uMatrix;varying vec2 vTex;" +
                "void main(){gl_Position=aPosition;vTex=(uMatrix*aTexCoord).xy;}";
        String fragment = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;" +
                "varying vec2 vTex;uniform samplerExternalOES sTexture;void main(){gl_FragColor=texture2D(sTexture,vTex);}";
        program = GLES20.glCreateProgram();
        int vs = shader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment);
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
        int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) fail("GL link: " + GLES20.glGetProgramInfoLog(program));
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix");
        int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); textureId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texture = new SurfaceTexture(textureId);
        callbackThread.start();
        texture.setOnFrameAvailableListener(this, new Handler(callbackThread.getLooper()));
        decoderSurface = new Surface(texture);
    }

    private int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) fail("GL shader: " + GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    Surface decoderSurface() { return decoderSurface; }
    void makeCurrent() { if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) fail("eglMakeCurrent"); }

    void awaitAndDraw() {
        synchronized (frameSync) {
            long end = System.currentTimeMillis() + 2500;
            while (!frameAvailable && System.currentTimeMillis() < end) {
                try { frameSync.wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (!frameAvailable) fail("Video frame timeout");
            frameAvailable = false;
        }
        texture.updateTexImage(); texture.getTransformMatrix(matrix);
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        vertices.position(0); GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, vertices); GLES20.glEnableVertexAttribArray(aPosition);
        vertices.position(3); GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 20, vertices); GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    void setPresentationTime(long ns) { EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns); }
    void swap() { if (!EGL14.eglSwapBuffers(display, eglSurface)) fail("eglSwapBuffers"); }

    @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameSync) { frameAvailable = true; frameSync.notifyAll(); }
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, eglSurface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
        decoderSurface.release(); texture.release();
        callbackThread.quitSafely();
    }

    private static void fail(String message) { throw new RuntimeException(message); }
}
