#version 460 core

layout(location = 0) in vec2 v_TexCoord;
layout(location = 0) out vec4 outFinalColor;

layout(binding = 0) uniform sampler2D sceneColorTex;
layout(binding = 1) uniform sampler2D sceneDepthTex;
layout(binding = 2) uniform sampler2D gNormalMetallic;
layout(binding = 3) uniform sampler2D gLightEmission;

void main() {
    outFinalColor = texture(sceneColorTex, v_TexCoord);
}
