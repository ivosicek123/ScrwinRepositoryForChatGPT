// ELFALL – Metal shadery pro UIKit vrstvu (QR textura, graf zůstatku).
#include <metal_stdlib>
using namespace metal;

// ---------- Textura (QR) – nearest-neighbour, aby moduly zůstaly ostré při libovolném měřítku ----------
struct TexOut { float4 pos [[position]]; float2 uv; };

vertex TexOut tex_vertex(uint vid [[vertex_id]]) {
    const float2 p[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    TexOut o; o.pos = float4(p[vid], 0, 1); o.uv = float2((p[vid].x + 1) * 0.5, 1 - (p[vid].y + 1) * 0.5);
    return o;
}

fragment float4 tex_fragment(TexOut in [[stage_in]], texture2d<float> t [[texture(0)]]) {
    constexpr sampler s(filter::nearest, address::clamp_to_edge);
    return t.sample(s, in.uv);
}

// ---------- Graf: body v datových souřadnicích, transformace simd float4x4 (data → clip) ----------
struct ChartUniforms { float4x4 transform; float4 lineColor; float4 fillTop; float4 fillBottom; float progress; };
struct ChartOut { float4 pos [[position]]; float t; float x; };

// Pole vrcholů: pro výplň (triangle strip) se střídá bod křivky a bod na základně (y = minimum).
vertex ChartOut chart_vertex(uint vid [[vertex_id]], const device float2 *pts [[buffer(0)]],
                             constant ChartUniforms &u [[buffer(1)]]) {
    ChartOut o; float2 p = pts[vid];
    o.pos = u.transform * float4(p, 0, 1);
    o.t = (vid % 2 == 0) ? 1.0 : 0.0;  // 1 = křivka, 0 = základna (gradient)
    o.x = o.pos.x * 0.5 + 0.5;
    return o;
}

fragment float4 chart_fill(ChartOut in [[stage_in]], constant ChartUniforms &u [[buffer(1)]]) {
    if (in.x > u.progress) discard_fragment();              // „odkrývací“ animace zleva doprava
    return mix(u.fillBottom, u.fillTop, in.t);
}

fragment float4 chart_line(ChartOut in [[stage_in]], constant ChartUniforms &u [[buffer(1)]]) {
    if (in.x > u.progress) discard_fragment();
    return u.lineColor;
}

// ---------- Pozadí obrazovky: gradient + SDF zaoblené „karty“ (pozice z UIKit layoutu, simd) ----------
struct Card { float4 rect; float radius; float elevation; float2 _pad; };   // rect = x, y, w, h v bodech
struct SceneUniforms { float2 viewport; float time; uint cardCount; float4 top; float4 bottom; float4 card; };

vertex TexOut scene_vertex(uint vid [[vertex_id]]) {
    const float2 p[4] = { {-1,-1}, {1,-1}, {-1,1}, {1,1} };
    TexOut o; o.pos = float4(p[vid], 0, 1); o.uv = float2((p[vid].x + 1) * 0.5, 1 - (p[vid].y + 1) * 0.5);
    return o;
}

static float sdRoundRect(float2 p, float2 c, float2 halfSize, float r) {
    float2 q = abs(p - c) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

fragment float4 scene_fragment(TexOut in [[stage_in]], constant SceneUniforms &u [[buffer(0)]],
                               constant Card *cards [[buffer(1)]]) {
    float2 px = in.uv * u.viewport;
    // jemně se pohybující gradient (čas z CADisplayLink)
    float wave = 0.04 * sin(in.uv.x * 3.0 + u.time * 0.5);
    float4 col = mix(u.top, u.bottom, saturate(in.uv.y + wave));
    for (uint i = 0; i < u.cardCount; i++) {
        float4 r = cards[i].rect;
        float d = sdRoundRect(px, r.xy + r.zw * 0.5, r.zw * 0.5, cards[i].radius);
        float shadow = (1.0 - smoothstep(0.0, 18.0 * cards[i].elevation, d)) * 0.18 * cards[i].elevation;
        col = mix(col, float4(0, 0, 0, 1), shadow * step(0.0, d));
        col = mix(col, u.card, 1.0 - smoothstep(-1.0, 1.0, d));      // antialiasovaný okraj
    }
    return col;
}

// ---------- Zero-copy graf z Rustu (ELFALL-GPU-CHART-v1) – značka posledního bodu ----------
// Vrcholy jsou v [0,1]² (normalizuje Rust); transformace stejná ChartUniforms. Bod = SDF kruh s halo.
struct DotOut { float4 pos [[position]]; float size [[point_size]]; };

vertex DotOut chart_dot_vertex(uint vid [[vertex_id]], const device float2 *pts [[buffer(0)]],
                               constant ChartUniforms &u [[buffer(1)]], constant uint &index [[buffer(2)]]) {
    DotOut o; o.pos = u.transform * float4(pts[index], 0, 1); o.size = 18.0; return o;
}

fragment float4 chart_dot_fragment(DotOut in [[stage_in]], float2 pc [[point_coord]], constant ChartUniforms &u [[buffer(1)]]) {
    float d = length(pc - 0.5) * 2.0;                          // 0 střed … 1 okraj
    float core = 1.0 - smoothstep(0.42, 0.5, d);
    float halo = (1.0 - smoothstep(0.5, 1.0, d)) * 0.35 * u.progress;
    float a = max(core, halo);
    if (a <= 0.0) discard_fragment();
    return float4(mix(u.lineColor.rgb, float3(1.0), core * 0.6), a * u.lineColor.a);
}
