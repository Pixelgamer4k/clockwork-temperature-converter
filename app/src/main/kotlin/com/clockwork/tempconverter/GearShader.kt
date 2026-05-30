package com.clockwork.tempconverter

import android.os.Build

object GearShader {
    const val AGSL_CODE = """
        uniform float2 uSize;
        uniform float2 uGyroOffset;
        uniform float uTime;
        uniform float uMetallicColor; // 0.0: Gunmetal, 0.5: Bronze, 1.0: Brass, 2.0: Copper

        half4 main(float2 fragCoord) {
            float2 uv = fragCoord / uSize;
            float2 center = uv - 0.5;
            float dist = length(center);
            
            // Generate normal for a beautiful rounded 3D surface
            float r = 0.5;
            float z = sqrt(max(0.0, r * r - dist * dist));
            float3 normal = normalize(float3(center.x, center.y, z));
            
            // Adjust light source based on gyroscope tilt
            float3 lightDir = normalize(float3(0.35 + uGyroOffset.x * 0.45, -0.45 + uGyroOffset.y * 0.45, 0.85));
            
            // Specular reflections
            float3 viewDir = float3(0.0, 0.0, 1.0);
            float3 halfDir = normalize(lightDir + viewDir);
            float spec = pow(max(0.0, dot(normal, halfDir)), 18.0);
            
            // Metallic base colors
            float3 gunmetal = float3(0.12, 0.14, 0.16);
            float3 bronze = float3(0.56, 0.42, 0.22);
            float3 brass = float3(0.80, 0.65, 0.30);
            float3 copper = float3(0.74, 0.35, 0.20);
            
            float3 baseColor = gunmetal;
            if (uMetallicColor > 1.8) {
                baseColor = copper;
            } else if (uMetallicColor > 0.8) {
                baseColor = brass;
            } else if (uMetallicColor > 0.3) {
                baseColor = bronze;
            }
            
            // Shading elements
            float diffuse = max(0.0, dot(normal, lightDir));
            float ambient = 0.22;
            
            // Brushed metal micro-scratches
            float brushed = sin(dist * 500.0) * 0.05;
            
            // Thin-film interference iridescence (rainbow oil-sheen shift)
            float cosTheta = dot(normal, viewDir);
            float filmThickness = cosTheta * 14.0 + uGyroOffset.x * 3.5 + uGyroOffset.y * 3.5;
            float3 iridescence = float3(
                sin(filmThickness + 0.0 + uTime * 0.5) * 0.12 + 0.12,
                sin(filmThickness + 2.09 + uTime * 0.5) * 0.12 + 0.12,
                sin(filmThickness + 4.18 + uTime * 0.5) * 0.12 + 0.12
            );
            
            // Edge shadow to emphasize depth separation
            float edgeShadow = smoothstep(0.5, 0.42, dist);
            
            // Combine all light components
            float3 color = baseColor * (diffuse * 0.88 + ambient + brushed) + spec * 0.45 + iridescence * 0.25;
            color *= edgeShadow;
            
            return half4(color, 1.0);
        }
    """
    
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
