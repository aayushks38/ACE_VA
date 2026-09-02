package com.ace.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.ace.app.ui.theme.*

@Composable
fun AceBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1D1238),
                        Color(0xFF120B25),
                        Color(0xFF080812)
                    )
                )
            )
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {

            // Large purple glow at the top
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF9B6CFF).copy(alpha = 0.42f),
                        Color(0xFF7042D6).copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset(
                        size.width * 0.5f,
                        size.height * 0.05f
                    ),
                    radius = size.width * 0.95f
                ),
                center = Offset(
                    size.width * 0.5f,
                    size.height * 0.05f
                ),
                radius = size.width * 0.95f
            )

            // Soft center glow behind ACE planet
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF7C3AED).copy(alpha = 0.14f),
                        Color(0xFF4C1D95).copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = Offset(
                        size.width * 0.5f,
                        size.height * 0.48f
                    ),
                    radius = size.width * 0.75f
                ),
                center = Offset(
                    size.width * 0.5f,
                    size.height * 0.48f
                ),
                radius = size.width * 0.75f
            )

            // Keep your existing constellation network
            drawConstellationNetwork(this)

            // Keep your existing particles
            drawParticles(this)
        }

        content()
    }
}

private fun drawConstellationNetwork(drawScope: DrawScope) {
    with(drawScope) {

        val nodes = listOf(
            Offset(size.width * 0.18f, size.height * 0.06f),
            Offset(size.width * 0.26f, size.height * 0.10f),
            Offset(size.width * 0.23f, size.height * 0.16f),
            Offset(size.width * 0.30f, size.height * 0.20f),

            Offset(size.width * 0.72f, size.height * 0.05f),
            Offset(size.width * 0.80f, size.height * 0.09f),
            Offset(size.width * 0.86f, size.height * 0.07f),
            Offset(size.width * 0.83f, size.height * 0.14f),
            Offset(size.width * 0.90f, size.height * 0.18f),

            Offset(size.width * 0.06f, size.height * 0.42f),
            Offset(size.width * 0.11f, size.height * 0.46f),
            Offset(size.width * 0.09f, size.height * 0.52f),

            Offset(size.width * 0.90f, size.height * 0.55f),
            Offset(size.width * 0.85f, size.height * 0.60f),
            Offset(size.width * 0.92f, size.height * 0.64f),

            Offset(size.width * 0.05f, size.height * 0.90f),
            Offset(size.width * 0.10f, size.height * 0.94f),

            Offset(size.width * 0.93f, size.height * 0.88f),
            Offset(size.width * 0.88f, size.height * 0.92f),
            Offset(size.width * 0.95f, size.height * 0.95f)
        )

        val connections = listOf(
            0 to 1,
            1 to 2,
            2 to 3,

            4 to 5,
            5 to 6,
            5 to 7,
            7 to 8,

            9 to 10,
            10 to 11,

            12 to 13,
            13 to 14,

            15 to 16,

            17 to 18,
            18 to 19
        )

        connections.forEach { (startIndex, endIndex) ->

            val start = nodes[startIndex]
            val end = nodes[endIndex]

            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(
                        AceNetworkLine.copy(alpha = 0.45f),
                        AceGlowPurple.copy(alpha = 0.25f),
                        AceNetworkLine.copy(alpha = 0.45f)
                    ),
                    start = start,
                    end = end
                ),
                start = start,
                end = end,
                strokeWidth = 1.2f
            )
        }

        nodes.forEach { node ->

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AceGlowPurple.copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    radius = 14f
                ),
                radius = 14f,
                center = node
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = 3.5f,
                center = node
            )
        }
    }
}

private fun drawParticles(drawScope: DrawScope) {
    with(drawScope) {

        val particles = listOf(
            Triple(size.width * 0.25f, size.height * 0.30f, 1.5f),
            Triple(size.width * 0.65f, size.height * 0.35f, 1.2f),
            Triple(size.width * 0.40f, size.height * 0.55f, 1.0f),
            Triple(size.width * 0.72f, size.height * 0.62f, 1.3f),
            Triple(size.width * 0.30f, size.height * 0.70f, 1.1f),
            Triple(size.width * 0.88f, size.height * 0.45f, 1.4f),
            Triple(size.width * 0.15f, size.height * 0.25f, 1.0f),
            Triple(size.width * 0.55f, size.height * 0.20f, 1.2f)
        )

        particles.forEach { (x, y, particleSize) ->

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AceGlowPurple.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    radius = particleSize * 5f
                ),
                center = Offset(x, y),
                radius = particleSize * 5f
            )

            drawCircle(
                color = AceParticleWhite.copy(alpha = 0.75f),
                radius = particleSize,
                center = Offset(x, y)
            )
        }
    }
}