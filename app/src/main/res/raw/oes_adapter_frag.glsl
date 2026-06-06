#extension GL_OES_EGL_image_external : require
precision highp float;
precision highp samplerExternalOES;

varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;

void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
