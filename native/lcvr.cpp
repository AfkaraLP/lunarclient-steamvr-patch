// JNI bridge between the Java agent and OpenVR, plus the few raw GL operations the
// agent needs (FBO blits, HUD capture, desktop mirror). GL entry points are resolved
// from the libGL that LWJGL already loaded, so this library does not link against GL.
//
// Every GL helper saves and restores the state it touches (glPushAttrib + explicit
// FBO/program/texture bindings) because Minecraft's GlStateManager caches GL state
// on the Java side and would desync otherwise.

#include <jni.h>
#include <dlfcn.h>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdarg>
#include <string>

#include <openvr.h>

// Native messages are queued and drained into the Java log (Native.drainLog), because the
// game's stderr does not reliably end up in Lunar's log files.
static std::string g_log;
static void lcvrLog(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    fprintf(stderr, "[lcvr] %s\n", buf);
    if (g_log.size() < 16384) {
        g_log += buf;
        g_log += '\n';
    }
}

typedef unsigned int GLenum;
typedef unsigned int GLuint;
typedef int GLint;
typedef int GLsizei;
typedef float GLfloat;
typedef double GLdouble;
typedef unsigned int GLbitfield;
typedef unsigned char GLboolean;

#define GL_TRUE 1
#define GL_FALSE 0
#define GL_TEXTURE_2D 0x0DE1
#define GL_RGBA 0x1908
#define GL_RGBA8 0x8058
#define GL_UNSIGNED_BYTE 0x1401
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_LINEAR 0x2601
#define GL_NEAREST 0x2600
#define GL_COLOR_BUFFER_BIT 0x00004000
#define GL_DEPTH_BUFFER_BIT 0x00000100
#define GL_STENCIL_BUFFER_BIT 0x00000400
#define GL_ALL_ATTRIB_BITS 0x000FFFFF
#define GL_FRAMEBUFFER 0x8D40
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#define GL_READ_FRAMEBUFFER_BINDING 0x8CAA
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_CURRENT_PROGRAM 0x8B8D
#define GL_TEXTURE_BINDING_2D 0x8069
#define GL_ACTIVE_TEXTURE 0x84E0
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE1 0x84C1
#define GL_BLEND 0x0BE2
#define GL_DEPTH_TEST 0x0B71
#define GL_CULL_FACE 0x0B44
#define GL_ALPHA_TEST 0x0BC0
#define GL_LIGHTING 0x0B50
#define GL_FOG 0x0B60
#define GL_SCISSOR_TEST 0x0C11
#define GL_STENCIL_TEST 0x0B90
#define GL_ONE 1
#define GL_ZERO 0
#define GL_ONE_MINUS_DST_ALPHA 0x0305
#define GL_PROJECTION 0x1701
#define GL_MODELVIEW 0x1700
#define GL_TEXTURE 0x1702
#define GL_QUADS 0x0007
#define GL_ARRAY_BUFFER_BINDING 0x8894
#define GL_ARRAY_BUFFER 0x8892

#define GLFN(ret, name, args) typedef ret (*PFN_##name) args; static PFN_##name p_##name;
GLFN(void, glPushAttrib, (GLbitfield))
GLFN(void, glPopAttrib, ())
GLFN(void, glGetIntegerv, (GLenum, GLint*))
GLFN(void, glBindTexture, (GLenum, GLuint))
GLFN(void, glGenTextures, (GLsizei, GLuint*))
GLFN(void, glDeleteTextures, (GLsizei, const GLuint*))
GLFN(void, glTexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*))
GLFN(void, glTexParameteri, (GLenum, GLenum, GLint))
GLFN(void, glEnable, (GLenum))
GLFN(void, glDisable, (GLenum))
GLFN(void, glBlendFunc, (GLenum, GLenum))
GLFN(void, glColor4f, (GLfloat, GLfloat, GLfloat, GLfloat))
GLFN(void, glBegin, (GLenum))
GLFN(void, glEnd, ())
GLFN(void, glTexCoord2f, (GLfloat, GLfloat))
GLFN(void, glVertex2f, (GLfloat, GLfloat))
GLFN(void, glVertex3f, (GLfloat, GLfloat, GLfloat))
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
GLFN(void, glMatrixMode, (GLenum))
GLFN(void, glPushMatrix, ())
GLFN(void, glPopMatrix, ())
GLFN(void, glLoadIdentity, ())
GLFN(void, glOrtho, (GLdouble, GLdouble, GLdouble, GLdouble, GLdouble, GLdouble))
GLFN(void, glViewport, (GLint, GLint, GLsizei, GLsizei))
GLFN(void, glClearColor, (GLfloat, GLfloat, GLfloat, GLfloat))
GLFN(void, glClear, (GLbitfield))
GLFN(void, glColorMask, (GLboolean, GLboolean, GLboolean, GLboolean))
GLFN(void, glDepthMask, (GLboolean))
GLFN(void, glMultMatrixf, (const GLfloat*))
GLFN(void, glRotatef, (GLfloat, GLfloat, GLfloat, GLfloat))
GLFN(void, glTranslatef, (GLfloat, GLfloat, GLfloat))
GLFN(void, glActiveTexture, (GLenum))
GLFN(void, glUseProgram, (GLuint))
GLFN(void, glBindBuffer, (GLenum, GLuint))
GLFN(void, glGenFramebuffers, (GLsizei, GLuint*))
GLFN(void, glDeleteFramebuffers, (GLsizei, const GLuint*))
GLFN(void, glBindFramebuffer, (GLenum, GLuint))
GLFN(void, glFramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))
GLFN(GLenum, glCheckFramebufferStatus, (GLenum))
GLFN(void, glBlitFramebuffer, (GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum))
GLFN(void, glGetTexImage, (GLenum, GLint, GLenum, GLenum, void*))
GLFN(GLenum, glGetError, ())

typedef void* (*PFN_glXGetProcAddressARB)(const unsigned char*);

static bool glLoaded = false;

static void* glSym(void* lib, PFN_glXGetProcAddressARB gpa, const char* name) {
    void* p = dlsym(lib, name);
    if (!p && gpa) p = gpa((const unsigned char*)name);
    if (!p) lcvrLog("missing GL function %s", name);
    return p;
}

static bool loadGL() {
    if (glLoaded) return true;
    void* lib = dlopen("libGL.so.1", RTLD_LAZY | RTLD_NOLOAD);
    if (!lib) lib = dlopen("libGL.so.1", RTLD_LAZY);
    if (!lib) lib = dlopen("libGL.so", RTLD_LAZY);
    if (!lib) return false;
    PFN_glXGetProcAddressARB gpa = (PFN_glXGetProcAddressARB)dlsym(lib, "glXGetProcAddressARB");
    bool ok = true;
#define LOAD(name) ok &= (p_##name = (PFN_##name)glSym(lib, gpa, #name)) != nullptr;
    LOAD(glPushAttrib) LOAD(glPopAttrib) LOAD(glGetIntegerv) LOAD(glBindTexture) LOAD(glGenTextures)
    LOAD(glDeleteTextures) LOAD(glTexImage2D) LOAD(glTexParameteri) LOAD(glEnable) LOAD(glDisable)
    LOAD(glBlendFunc) LOAD(glColor4f) LOAD(glBegin) LOAD(glEnd) LOAD(glTexCoord2f) LOAD(glVertex2f) LOAD(glVertex3f)
    LOAD(glMatrixMode) LOAD(glPushMatrix) LOAD(glPopMatrix) LOAD(glLoadIdentity) LOAD(glOrtho)
    LOAD(glViewport) LOAD(glClearColor) LOAD(glClear) LOAD(glColorMask) LOAD(glDepthMask)
    LOAD(glMultMatrixf) LOAD(glRotatef) LOAD(glTranslatef) LOAD(glActiveTexture) LOAD(glUseProgram)
    LOAD(glBindBuffer) LOAD(glGenFramebuffers) LOAD(glDeleteFramebuffers) LOAD(glBindFramebuffer)
    LOAD(glFramebufferTexture2D) LOAD(glCheckFramebufferStatus) LOAD(glBlitFramebuffer) LOAD(glGetTexImage) LOAD(glGetError)
#undef LOAD
    glLoaded = ok;
    return ok;
}

// Logs GL errors around our helpers (a limited number of times) so a broken frame can be
// attributed: "pending before" errors come from the game, "after" errors from lcvr.
static int g_glErrorReports = 0;
static void checkGL(const char* where, bool before) {
    GLenum e;
    while ((e = p_glGetError()) != 0) {
        if (g_glErrorReports < 20) {
            lcvrLog("GL error 0x%x %s %s", e, before ? "pending before" : "raised by", where);
            if (++g_glErrorReports == 20) lcvrLog("further GL errors not reported");
        }
    }
}

// Saves FBO bindings, program, VBO, active texture and unit-0 texture binding.
struct SavedBindings {
    GLint drawFbo, readFbo, program, arrayBuffer, activeTex, tex0;
    const char* where = "";
    void save(const char* fn) {
        where = fn;
        checkGL(where, true);
        p_glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
        p_glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
        p_glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        p_glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        p_glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTex);
        p_glActiveTexture(GL_TEXTURE0);
        p_glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex0);
        p_glPushAttrib(GL_ALL_ATTRIB_BITS);
    }
    void restore() {
        p_glPopAttrib();
        p_glActiveTexture(GL_TEXTURE0);
        p_glBindTexture(GL_TEXTURE_2D, (GLuint)tex0);
        p_glActiveTexture((GLenum)activeTex);
        p_glBindBuffer(GL_ARRAY_BUFFER, (GLuint)arrayBuffer);
        p_glUseProgram((GLuint)program);
        p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFbo);
        p_glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)readFbo);
        checkGL(where, false);
    }
};

// Plain state for drawing 2D textured quads in a pixel-space ortho projection.
static void begin2D(int w, int h) {
    p_glUseProgram(0);
    p_glBindBuffer(GL_ARRAY_BUFFER, 0);
    p_glViewport(0, 0, w, h);
    p_glDisable(GL_DEPTH_TEST);
    p_glDisable(GL_CULL_FACE);
    p_glDisable(GL_ALPHA_TEST);
    p_glDisable(GL_LIGHTING);
    p_glDisable(GL_FOG);
    p_glDisable(GL_SCISSOR_TEST);
    p_glDisable(GL_STENCIL_TEST);
    p_glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    p_glDepthMask(GL_FALSE);
    p_glActiveTexture(GL_TEXTURE1);
    p_glDisable(GL_TEXTURE_2D);
    p_glActiveTexture(GL_TEXTURE0);
    p_glMatrixMode(GL_TEXTURE);
    p_glPushMatrix();
    p_glLoadIdentity();
    p_glMatrixMode(GL_PROJECTION);
    p_glPushMatrix();
    p_glLoadIdentity();
    p_glOrtho(0, w, 0, h, -1, 1);
    p_glMatrixMode(GL_MODELVIEW);
    p_glPushMatrix();
    p_glLoadIdentity();
}

static void end2D() {
    p_glMatrixMode(GL_TEXTURE);
    p_glPopMatrix();
    p_glMatrixMode(GL_PROJECTION);
    p_glPopMatrix();
    p_glMatrixMode(GL_MODELVIEW);
    p_glPopMatrix();
}

static void rect(float x0, float y0, float x1, float y1) {
    p_glVertex2f(x0, y0); p_glVertex2f(x1, y0); p_glVertex2f(x1, y1); p_glVertex2f(x0, y1);
}

// ---------------------------------------------------------------------------------------

static vr::IVRSystem* g_sys = nullptr;
static GLuint g_hudTex = 0, g_hudFbo = 0;
static int g_hudW = 0, g_hudH = 0;
static bool g_submitWarned = false;

static const char* initError(vr::EVRInitError e) {
    return vr::VR_GetVRInitErrorAsEnglishDescription(e);
}

extern "C" {

JNIEXPORT jstring JNICALL Java_lcvr_Native_init(JNIEnv* env, jclass) {
    if (!loadGL()) return env->NewStringUTF("could not resolve OpenGL entry points");
    if (g_sys) return nullptr;
    if (!vr::VR_IsRuntimeInstalled()) return env->NewStringUTF("SteamVR runtime is not installed/registered");
    if (!vr::VR_IsHmdPresent()) return env->NewStringUTF("no headset detected (is SteamVR running with the Steam Frame connected?)");

    vr::EVRInitError err = vr::VRInitError_None;
    g_sys = vr::VR_Init(&err, vr::VRApplication_Scene);
    if (err != vr::VRInitError_None) {
        g_sys = nullptr;
        return env->NewStringUTF(initError(err));
    }
    if (!vr::VRCompositor()) {
        vr::VR_Shutdown();
        g_sys = nullptr;
        return env->NewStringUTF("SteamVR compositor unavailable");
    }
    vr::VRCompositor()->SetTrackingSpace(vr::TrackingUniverseSeated);

    return nullptr;
}

JNIEXPORT jintArray JNICALL Java_lcvr_Native_recommendedSize(JNIEnv* env, jclass) {
    uint32_t w = 1440, h = 1440;
    if (g_sys) g_sys->GetRecommendedRenderTargetSize(&w, &h);
    jint v[2] = {(jint)w, (jint)h};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

// Blocks until the compositor wants the next frame. Writes the HMD pose (3x4, row-major)
// into out. Returns 1 if the pose is valid, 2 if valid and the headset was just put on,
// 0 if not valid, -1 if SteamVR asked us to quit.
JNIEXPORT jint JNICALL Java_lcvr_Native_waitGetPoses(JNIEnv* env, jclass, jfloatArray out) {
    if (!g_sys) return -1;
    vr::VREvent_t ev;
    bool putOn = false;
    while (g_sys->PollNextEvent(&ev, sizeof(ev))) {
        if (ev.eventType == vr::VREvent_TrackedDeviceUserInteractionStarted && ev.trackedDeviceIndex == vr::k_unTrackedDeviceIndex_Hmd)
            putOn = true;
        if (ev.eventType == vr::VREvent_Quit) {
            g_sys->AcknowledgeQuit_Exiting();
            vr::VR_Shutdown();
            g_sys = nullptr;
            return -1;
        }
    }
    vr::TrackedDevicePose_t poses[vr::k_unMaxTrackedDeviceCount];
    vr::VRCompositor()->WaitGetPoses(poses, vr::k_unMaxTrackedDeviceCount, nullptr, 0);
    const vr::TrackedDevicePose_t& hmd = poses[vr::k_unTrackedDeviceIndex_Hmd];
    if (!hmd.bPoseIsValid) return 0;
    jfloat m[12];
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 4; c++) m[r * 4 + c] = hmd.mDeviceToAbsoluteTracking.m[r][c];
    env->SetFloatArrayRegion(out, 0, 12, m);
    return putOn ? 2 : 1;
}

// Column-major projection * inverse(eyeToHead) for glMultMatrixf. Folding the eye offset
// into the projection makes every Minecraft perspective (world, hand, clouds) stereo
// without touching the modelview code paths.
JNIEXPORT void JNICALL Java_lcvr_Native_projection(JNIEnv* env, jclass, jint eye, jfloat zNear, jfloat zFar, jfloatArray out) {
    if (!g_sys) return;
    vr::EVREye e = eye == 0 ? vr::Eye_Left : vr::Eye_Right;
    // GetProjectionMatrix is already GL-ready. (GetProjectionRaw's "top"/"bottom" are named
    // backwards, see docs/Driver_API_Documentation.md; do not "fix" the sign when using it.)
    vr::HmdMatrix44_t p = g_sys->GetProjectionMatrix(e, zNear, zFar);
    vr::HmdMatrix34_t eh = g_sys->GetEyeToHeadTransform(e);
    // inverse of rigid transform [R|t] is [R^T | -R^T t]
    float inv[4][4] = {};
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) inv[r][c] = eh.m[c][r];
        inv[r][3] = -(eh.m[0][r] * eh.m[0][3] + eh.m[1][r] * eh.m[1][3] + eh.m[2][r] * eh.m[2][3]);
    }
    inv[3][3] = 1;
    float m[4][4];
    for (int r = 0; r < 4; r++)
        for (int c = 0; c < 4; c++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += p.m[r][k] * inv[k][c];
            m[r][c] = s;
        }
    jfloat cm[16];
    for (int r = 0; r < 4; r++)
        for (int c = 0; c < 4; c++) cm[c * 4 + r] = m[r][c];
    env->SetFloatArrayRegion(out, 0, 16, cm);
}

JNIEXPORT void JNICALL Java_lcvr_Native_multMatrix(JNIEnv* env, jclass, jfloatArray m) {
    if (!loadGL()) return;
    jfloat v[16];
    env->GetFloatArrayRegion(m, 0, 16, v);
    p_glMultMatrixf(v);
}

// Same matrix as org.lwjgl.util.glu.Project.gluPerspective.
JNIEXPORT void JNICALL Java_lcvr_Native_perspective(JNIEnv*, jclass, jfloat fovy, jfloat aspect, jfloat zNear, jfloat zFar) {
    if (!loadGL()) return;
    float rad = fovy / 2 * (float)M_PI / 180;
    float dz = zFar - zNear, s = sinf(rad);
    if (dz == 0 || s == 0 || aspect == 0) return;
    float cot = cosf(rad) / s;
    float m[16] = {cot / aspect, 0, 0, 0,
                   0, cot, 0, 0,
                   0, 0, -(zFar + zNear) / dz, -1,
                   0, 0, -2 * zNear * zFar / dz, 0};
    p_glMultMatrixf(m);
}

JNIEXPORT void JNICALL Java_lcvr_Native_rotate(JNIEnv*, jclass, jfloat a, jfloat x, jfloat y, jfloat z) {
    if (loadGL()) p_glRotatef(a, x, y, z);
}

JNIEXPORT void JNICALL Java_lcvr_Native_translate(JNIEnv*, jclass, jfloat x, jfloat y, jfloat z) {
    if (loadGL()) p_glTranslatef(x, y, z);
}

JNIEXPORT void JNICALL Java_lcvr_Native_clearFbo(JNIEnv*, jclass, jint fbo, jint w, jint h) {
    if (!loadGL()) return;
    SavedBindings s;
    s.save(__func__);
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo < 0 ? 0 : (GLuint)fbo);
    p_glDisable(GL_SCISSOR_TEST);
    p_glViewport(0, 0, w, h);
    p_glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    p_glDepthMask(GL_TRUE);
    p_glClearColor(0, 0, 0, 0);
    p_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    s.restore();
}

// Copies the GUI-only main framebuffer into the HUD texture and optionally draws
// a mouse cursor on it (Minecraft relies on the OS cursor, which the headset never sees).
JNIEXPORT void JNICALL Java_lcvr_Native_hudCapture(JNIEnv*, jclass, jint srcFbo, jint w, jint h,
                                                  jboolean cursor, jint mx, jint my) {
    if (!loadGL() || w <= 0 || h <= 0) return;
    SavedBindings s;
    s.save(__func__);
    if (g_hudW != w || g_hudH != h || !g_hudTex) {
        if (!g_hudTex) p_glGenTextures(1, &g_hudTex);
        if (!g_hudFbo) p_glGenFramebuffers(1, &g_hudFbo);
        p_glBindTexture(GL_TEXTURE_2D, g_hudTex);
        p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        p_glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        p_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        p_glBindFramebuffer(GL_FRAMEBUFFER, g_hudFbo);
        p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, g_hudTex, 0);
        if (p_glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
            lcvrLog("HUD framebuffer incomplete");
        g_hudW = w;
        g_hudH = h;
    }
    p_glDisable(GL_SCISSOR_TEST);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo < 0 ? 0 : (GLuint)srcFbo);
    p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, g_hudFbo);
    p_glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);

    if (cursor) {
        p_glBindFramebuffer(GL_FRAMEBUFFER, g_hudFbo);
        begin2D(w, h);
        p_glDisable(GL_TEXTURE_2D);
        p_glDisable(GL_BLEND);
        float u = h / 360.0f; // scale with resolution
        if (u < 1) u = 1;
        float x = (float)mx, y = (float)my;
        p_glBegin(GL_QUADS);
        p_glColor4f(0, 0, 0, 1);
        rect(x - 7 * u, y - 2 * u, x + 7 * u, y + 2 * u);
        rect(x - 2 * u, y - 7 * u, x + 2 * u, y + 7 * u);
        p_glColor4f(1, 1, 1, 1);
        rect(x - 6 * u, y - 1 * u, x + 6 * u, y + 1 * u);
        rect(x - 1 * u, y - 6 * u, x + 1 * u, y + 6 * u);
        p_glEnd();
        end2D();
    }
    s.restore();
}

// Draws an eye texture *underneath* the existing (premultiplied, transparent) GUI in the
// main framebuffer so the desktop window shows a normal-looking mirror. The eye image is
// center-cropped to the window aspect ratio.
JNIEXPORT void JNICALL Java_lcvr_Native_mirrorUnder(JNIEnv*, jclass, jint dstFbo, jint w, jint h,
                                                   jint eyeTex, jint ew, jint eh) {
    if (!loadGL() || w <= 0 || h <= 0 || ew <= 0 || eh <= 0) return;
    SavedBindings s;
    s.save(__func__);
    p_glBindFramebuffer(GL_FRAMEBUFFER, dstFbo < 0 ? 0 : (GLuint)dstFbo);
    begin2D(w, h);
    p_glEnable(GL_TEXTURE_2D);
    p_glBindTexture(GL_TEXTURE_2D, (GLuint)eyeTex);
    p_glEnable(GL_BLEND);
    p_glBlendFunc(GL_ONE_MINUS_DST_ALPHA, GL_ONE);
    float win = (float)w / h, eyeA = (float)ew / eh;
    float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
    if (win > eyeA) { float vis = eyeA / win; v0 = 0.5f - vis / 2; v1 = 0.5f + vis / 2; }
    else { float vis = win / eyeA; u0 = 0.5f - vis / 2; u1 = 0.5f + vis / 2; }
    p_glColor4f(1, 1, 1, 1);
    p_glBegin(GL_QUADS);
    p_glTexCoord2f(u0, v0); p_glVertex2f(0, 0);
    p_glTexCoord2f(u1, v0); p_glVertex2f((float)w, 0);
    p_glTexCoord2f(u1, v1); p_glVertex2f((float)w, (float)h);
    p_glTexCoord2f(u0, v1); p_glVertex2f(0, (float)h);
    p_glEnd();
    end2D();
    s.restore();
}

// Debug readback: RGBA8 pixels of a texture (bottom-up rows) into out. tex 0 = HUD texture.
JNIEXPORT void JNICALL Java_lcvr_Native_readTexture(JNIEnv* env, jclass, jint tex, jint w, jint h, jbyteArray out) {
    if (!loadGL() || !p_glGetTexImage) return;
    GLuint t = tex == 0 ? g_hudTex : (GLuint)tex;
    if (!t || env->GetArrayLength(out) < w * h * 4) return;
    SavedBindings s;
    s.save(__func__);
    p_glBindTexture(GL_TEXTURE_2D, t);
    jbyte* buf = env->GetByteArrayElements(out, nullptr);
    p_glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
    env->ReleaseByteArrayElements(out, buf, 0);
    s.restore();
}

JNIEXPORT jintArray JNICALL Java_lcvr_Native_hudSize(JNIEnv* env, jclass) {
    jint v[2] = {g_hudW, g_hudH};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}

// Draws the captured GUI as a head-locked quad into an eye framebuffer. proj16 is that eye's
// column-major projection (eye offset included), so the quad gets correct stereo depth.
// The GUI texture holds premultiplied color over transparent black, hence ONE/ONE_MINUS_SRC_ALPHA.
JNIEXPORT void JNICALL Java_lcvr_Native_drawHud(JNIEnv* env, jclass, jint fbo, jint w, jint h,
                                               jfloatArray proj16, jfloat width, jfloat dist) {
    if (!loadGL() || !g_hudTex || g_hudW <= 0 || g_hudH <= 0) return;
    jfloat proj[16];
    env->GetFloatArrayRegion(proj16, 0, 16, proj);
    SavedBindings s;
    s.save(__func__);
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo < 0 ? 0 : (GLuint)fbo);
    begin2D(w, h); // resets state; replace its ortho with the eye projection
    p_glMatrixMode(GL_PROJECTION);
    p_glLoadIdentity();
    p_glMultMatrixf(proj);
    p_glMatrixMode(GL_MODELVIEW);
    p_glEnable(GL_TEXTURE_2D);
    p_glBindTexture(GL_TEXTURE_2D, g_hudTex);
    p_glEnable(GL_BLEND);
    p_glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    p_glColor4f(1, 1, 1, 1);
    float hw = width / 2, hh = hw * g_hudH / g_hudW, z = -dist;
    p_glBegin(GL_QUADS);
    p_glTexCoord2f(0, 0); p_glVertex3f(-hw, -hh, z);
    p_glTexCoord2f(1, 0); p_glVertex3f(hw, -hh, z);
    p_glTexCoord2f(1, 1); p_glVertex3f(hw, hh, z);
    p_glTexCoord2f(0, 1); p_glVertex3f(-hw, hh, z);
    p_glEnd();
    end2D();
    s.restore();
}

// SteamVR's view of the last frame: [0] scene GPU ms (WaitGetPoses..Submit), [1] ms between our
// WaitGetPoses calls, [2] dropped frames, [3] mispresented frames, [4] reprojection flags,
// [5] GPU ms after Submit (desktop window etc.), [6] ms inside Submit. False if unavailable.
JNIEXPORT jboolean JNICALL Java_lcvr_Native_frameTiming(JNIEnv* env, jclass, jfloatArray out) {
    if (!g_sys) return JNI_FALSE;
    vr::Compositor_FrameTiming t = {};
    t.m_nSize = sizeof(t);
    if (!vr::VRCompositor()->GetFrameTiming(&t, 0)) return JNI_FALSE;
    jfloat v[7] = {t.m_flPreSubmitGpuMs, t.m_flClientFrameIntervalMs, (float)t.m_nNumDroppedFrames,
                   (float)t.m_nNumMisPresented, (float)t.m_nReprojectionFlags, t.m_flPostSubmitGpuMs,
                   t.m_flSubmitFrameMs};
    env->SetFloatArrayRegion(out, 0, 7, v);
    return JNI_TRUE;
}

// Re-attaches OptiFine's G-buffer color textures to its framebuffer for a second eye pass,
// the same thing Shaders.beginRender does once per frame. Leaves fbo bound, like OptiFine.
JNIEXPORT void JNICALL Java_lcvr_Native_attachColorTextures(JNIEnv* env, jclass, jint fbo, jintArray textures, jint n) {
    if (!loadGL() || n <= 0) return;
    jint tex[16];
    if (n > 16) n = 16;
    env->GetIntArrayRegion(textures, 0, n, tex);
    checkGL("attachColorTextures", true);
    p_glBindFramebuffer(GL_FRAMEBUFFER, (GLuint)fbo);
    for (int i = 0; i < n; i++)
        p_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, (GLuint)tex[i], 0);
    checkGL("attachColorTextures", false);
}

JNIEXPORT jstring JNICALL Java_lcvr_Native_drainLog(JNIEnv* env, jclass) {
    if (g_log.empty()) return nullptr;
    jstring s = env->NewStringUTF(g_log.c_str());
    g_log.clear();
    return s;
}

JNIEXPORT void JNICALL Java_lcvr_Native_submit(JNIEnv*, jclass, jint texL, jint texR) {
    if (!g_sys) return;
    vr::IVRCompositor* comp = vr::VRCompositor();
    vr::Texture_t l = {(void*)(uintptr_t)texL, vr::TextureType_OpenGL, vr::ColorSpace_Gamma};
    vr::Texture_t r = {(void*)(uintptr_t)texR, vr::TextureType_OpenGL, vr::ColorSpace_Gamma};
    if (glLoaded) checkGL("Submit", true);
    vr::EVRCompositorError e1 = comp->Submit(vr::Eye_Left, &l);
    vr::EVRCompositorError e2 = comp->Submit(vr::Eye_Right, &r);
    if (glLoaded) checkGL("Submit", false);
    if ((e1 || e2) && !g_submitWarned) {
        lcvrLog("compositor Submit failed: %d / %d", (int)e1, (int)e2);
        g_submitWarned = true;
    }
}

} // extern "C"
