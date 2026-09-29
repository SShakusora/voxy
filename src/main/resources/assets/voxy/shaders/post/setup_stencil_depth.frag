#version 330 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
layout(location = 2) uniform mat4 invSourceMVP;
layout(location = 3) uniform mat4 targetMVP;

#import <voxy:util/depthutils.glsl>

in vec2 UV;
void main() {
    vec2 sourceUV = UV*scaleFactor;
    float sourceDepth = texture(depthTex, sourceUV).r;
    if (sourceDepth==FAR) {
        discard;
    }

    // Reconstruct the vanilla depth sample in view space, then project it with
    // Voxy's extended-far-plane matrix.  This preserves the actual occluder
    // position instead of treating every non-sky pixel as the near plane.
    vec4 viewPosition = invSourceMVP * vec4(SCREEN2NDC(vec3(sourceUV, sourceDepth)), 1.0);
    viewPosition /= viewPosition.w;
    vec4 targetPosition = targetMVP * viewPosition;
    float targetDepth = targetPosition.z / targetPosition.w;
    targetDepth = NDC2SCREEN_DEPTH(targetDepth);
    gl_FragDepth = gl_DepthRange.diff*targetDepth + gl_DepthRange.near;
}
