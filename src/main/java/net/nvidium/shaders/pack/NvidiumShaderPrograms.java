package net.nvidium.shaders.pack;

import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.gl.shader.ShaderType;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.config.PipelineConfig;

import java.util.EnumMap;
import java.util.Map;

/**
 * Builds Nvidium {@link Shader} programs (TASK + MESH + FRAGMENT) for opaque terrain G-Buffer,
 * translucent water/glass, and cascaded shadow map passes.
 */
public final class NvidiumShaderPrograms {

    private static final String WAVING_MESH_LIBRARY = """
            layout(std140, binding = 3) uniform NvidiumMeshSceneUniforms {
                mat4 u_MeshViewMatrix;
                mat4 u_MeshProjMatrix;
                mat4 u_MeshViewProjMatrix;
                mat4 u_MeshInvViewProjMatrix;
                mat4 u_MeshPrevViewProjMatrix;
                mat4 u_MeshCascadeVP0;
                mat4 u_MeshCascadeVP1;
                mat4 u_MeshCascadeVP2;
                vec4 u_MeshCascadeSplits;
                vec4 u_MeshSunDir;
                vec4 u_MeshSunColor;
                vec4 u_MeshMoonDir;
                vec4 u_MeshMoonColor;
                vec4 u_MeshShadowLightDir;
                vec4 u_MeshSkyColor;
                vec4 u_MeshHorizonColor;
                vec4 u_MeshBlockLightColor;
                vec4 u_MeshCameraPos;
                vec4 u_MeshScreenSize;
                vec4 u_MeshTime;
                vec4 u_MeshWeather;
            };
            layout(std140, binding = 4) uniform NvidiumMeshPackOptions {
                vec4 u_MeshPackOptions[16];
            };
            layout(location = 5) out NvidiumVertexData {
                vec2 v_uv;
                vec3 v_color;
                vec2 v_lightUV;
            } nv_out[];

            vec3 applyNvidiumVertexDisplacement(Vertex v, vec3 subchunkRelativePos) {
            #ifdef NS_WAVING_TERRAIN
                uint matId = decodeVertexMaterial(v);
                if (matId != 3u && matId != 4u && matId != 1u) {
                    return subchunkRelativePos;
                }
                float windSpeedOpt = u_MeshPackOptions[3].z;
                if (windSpeedOpt <= 0.001) {
                    windSpeedOpt = 1.0;
                }
                vec3 worldPos = subchunkRelativePos + subchunkOffset.xyz + u_MeshCameraPos.xyz;
                float t = u_MeshTime.x * (1.65 * windSpeedOpt);
                float rainBoost = 1.0 + u_MeshWeather.x * 0.65;

                if (matId == 3u) {
                    // Waving foliage / grass / crops / vines
                    float phase = dot(worldPos.xz, vec2(0.73, 0.59)) + worldPos.y * 0.45;
                    float fractY = fract(worldPos.y + 0.001);
                    float anchor = clamp(fractY * 1.35, 0.15, 1.0);
                    float sx = sin(t * 1.45 + phase) * 0.042 + cos(t * 2.35 + phase * 1.7) * 0.018;
                    float sz = cos(t * 1.25 + phase * 1.3) * 0.038 + sin(t * 2.05 + phase * 0.8) * 0.016;
                    subchunkRelativePos.xz += vec2(sx, sz) * anchor * rainBoost;
                } else if (matId == 4u) {
                    // Tree leaves flutter
                    float phase = dot(worldPos.xyz, vec3(0.85, 0.65, 0.77));
                    float sx = sin(t * 1.35 + phase) * 0.026 + cos(t * 2.6 + phase * 1.4) * 0.012;
                    float sy = sin(t * 1.90 + phase * 1.1) * 0.012;
                    float sz = cos(t * 1.45 + phase * 0.9) * 0.026;
                    subchunkRelativePos += vec3(sx, sy, sz) * rainBoost;
                } else if (matId == 1u) {
                    // Water surface wave displacement
                    float waveAmp = u_MeshPackOptions[2].x;
                    if (waveAmp <= 0.001) waveAmp = 1.0;
                    float w1 = sin(worldPos.x * 1.35 + worldPos.z * 0.85 + u_MeshTime.x * 2.1);
                    float w2 = cos(worldPos.x * 0.75 - worldPos.z * 1.45 + u_MeshTime.x * 1.65);
                    float w3 = sin((worldPos.x + worldPos.z) * 2.4 - u_MeshTime.x * 2.8) * 0.45;
                    subchunkRelativePos.y += (w1 + w2 + w3) * 0.018 * waveAmp - 0.015;
                }
            #endif
                return subchunkRelativePos;
            }
            """;

    private NvidiumShaderPrograms() {}

    public static Shader compileTerrainGBufferShader(ShaderPack pack) {
        String taskSrc = ShaderSourceLoader.loadNvidiumCoreShader("terrain/task.glsl");
        String meshSrc = injectWavingIntoMeshShader(ShaderSourceLoader.loadNvidiumCoreShader("terrain/mesh.glsl"), false);
        return compileMeshPipelineWithFallback(pack, "terrain", taskSrc, meshSrc);
    }

    public static Shader compileTemporalTerrainGBufferShader(ShaderPack pack) {
        String taskSrc = ShaderSourceLoader.loadNvidiumCoreShader("terrain/temporal_task.glsl");
        String meshSrc = injectWavingIntoMeshShader(ShaderSourceLoader.loadNvidiumCoreShader("terrain/mesh.glsl"), false);
        return compileMeshPipelineWithFallback(pack, "terrain", taskSrc, meshSrc);
    }

    public static Shader compileTranslucentShader(ShaderPack pack) {
        String taskSrc = ShaderSourceLoader.loadNvidiumCoreShader("terrain/translucent/task.glsl");
        String meshSrc = injectWavingIntoMeshShader(ShaderSourceLoader.loadNvidiumCoreShader("terrain/translucent/mesh.glsl"), true);
        return compileMeshPipelineWithFallback(pack, "translucent", taskSrc, meshSrc);
    }

    public static Shader compileShadowDepthShader(ShaderPack pack) {
        String taskSrc = ShaderSourceLoader.loadNvidiumCoreShader("terrain/task.glsl")
                .replace(
                        "(sectionVisibility[sectionId]&uint8_t(1)) != uint8_t(0)",
                        "!sectionEmpty(sectionData[sectionId].header) && (sectionData[sectionId].header.y & (1 << 17)) == 0"
                )
                .replace(
                        "origin = vec3(chunk<<4);",
                        """
                        origin = vec3(chunk<<4);
                            vec4 shadowClipCenter = MVP * (transformationArray[transformationId] * vec4(origin + vec3(8.0), 1.0));
                            vec3 shadowHalfExt = (abs(MVP[0].xyz) + abs(MVP[1].xyz) + abs(MVP[2].xyz)) * 9.5;
                            if (any(greaterThan(abs(shadowClipCenter.xyz), vec3(1.05) + shadowHalfExt))) {
                                gl_TaskCountNV = 0;
                                return;
                            }"""
                )
                .replaceAll("relChunkPos\\.[xyz]\\s*[<>]=\\s*0\\s*&&\\s*", "");
        String meshSrc = injectWavingIntoMeshShader(ShaderSourceLoader.loadNvidiumCoreShader("terrain/mesh.glsl"), false)
                .replace("t0draw = all(notEqual(round(t0min),round(t0max)));", "t0draw = true;")
                .replace("t1draw = all(notEqual(round(t1min),round(t1max)));", "t1draw = true;");
        return compileMeshPipelineWithFallback(pack, "shadow", taskSrc, meshSrc);
    }

    private static Shader compileMeshPipelineWithFallback(ShaderPack pack, String stageKey, String taskSrc, String meshSrc) {
        try {
            String fragSrc = ShaderSourceLoader.loadStageSource(pack, stageKey, true);
            return buildNvidiumShader(taskSrc, meshSrc, fragSrc);
        } catch (Exception e) {
            ShaderPackManager.get().recordError("Stage '" + stageKey + "' compile failed", e.getMessage());
            if (pack != null && !pack.isBuiltin()) {
                NvidiumShaderPipeline.LOGGER.warn("Falling back to built-in '{}' shader after error in pack '{}'",
                        stageKey, pack.getName());
                String builtinFragSrc = ShaderSourceLoader.loadBuiltinStageSource(stageKey, true);
                return buildNvidiumShader(taskSrc, meshSrc, builtinFragSrc);
            }
            throw e;
        }
    }

    private static Shader buildNvidiumShader(String taskSrc, String meshSrc, String fragSrc) {
        Map<ShaderType, String> sources = new EnumMap<>(ShaderType.class);
        sources.put(ShaderType.TASK, taskSrc);
        sources.put(ShaderType.MESH, meshSrc);
        sources.put(ShaderType.FRAGMENT, fragSrc);

        Shader.Builder builder = Shader.make();
        for (Map.Entry<ShaderType, String> entry : sources.entrySet()) {
            builder.addSource(entry.getKey(), entry.getValue());
        }
        return builder.compile();
    }

    private static String injectWavingIntoMeshShader(String meshSrc, boolean translucent) {
        String anchor = translucent ? "void emitVertex(" : "vec4 transformVertex(";
        String target = translucent
                ? "vec3 pos = decodeVertexPosition(V)+originAndBaseData.xyz;"
                : "vec3 pos = decodeVertexPosition(V)+origin;";
        String replacement = PipelineConfig.get().wavingTerrain
                ? (translucent
                        ? "vec3 pos = applyNvidiumVertexDisplacement(V, decodeVertexPosition(V)+originAndBaseData.xyz);"
                        : "vec3 pos = applyNvidiumVertexDisplacement(V, decodeVertexPosition(V)+origin);")
                : target;

        int anchorIdx = meshSrc.indexOf(anchor);
        if (anchorIdx > 0 && meshSrc.contains(target)) {
            String modified = meshSrc.substring(0, anchorIdx)
                    + WAVING_MESH_LIBRARY + "\n"
                    + meshSrc.substring(anchorIdx).replace(target, replacement);
            if (translucent) {
                modified = modified.replace(
                        "OUT[outId].uv = decodeVertexUV(V);",
                        "OUT[outId].uv = decodeVertexUV(V);\n    nv_out[outId].v_uv = OUT[outId].uv;\n    nv_out[outId].v_color = decodeVertexColour(V).rgb;\n    nv_out[outId].v_lightUV = decodeLightUV(V);"
                );
            } else {
                modified = modified.replace(
                        "void putVertex(uint id, Vertex V) {",
                        "void putVertex(uint id, Vertex V) {\n    nv_out[id].v_uv = decodeVertexUV(V);\n    nv_out[id].v_color = decodeVertexColour(V).rgb;\n    nv_out[id].v_lightUV = decodeLightUV(V);"
                );
            }
            return modified;
        }
        return meshSrc;
    }
}
