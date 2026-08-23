package com.paperjump.ai

/**
 * What the model is asked to send back: a level, in the drawing's own coordinates.
 *
 * Every coordinate is a fraction of the page — `0,0` top-left, `1,1` bottom-right — so the
 * plan is independent of how big the drawing was, and [PlanPainter] can paint it at any
 * size. The plan is deliberately made of the same five things the app already understands,
 * because it is painted in the app's own ink colours and then read by the app's own
 * detector: the model designs the level, it never gets to invent new rules.
 */
data class PlanLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val thickness: Float = DEFAULT_THICKNESS,
) {
    companion object {
        const val DEFAULT_THICKNESS = 0.012f
    }
}

data class PlanPoint(val x: Float, val y: Float)

data class LevelPlan(
    val title: String,
    val platforms: List<PlanLine>,
    val lava: List<PlanLine>,
    val coins: List<PlanPoint>,
    val enemies: List<PlanPoint>,
    val start: PlanPoint?,
    val goal: PlanPoint?,
) {
    /** Nothing to stand on means nothing to play, whatever else came back. */
    val isPlayable: Boolean get() = platforms.isNotEmpty()

    companion object {

        /**
         * The instructions sent with the picture.
         *
         * Written to be read by a model that has never seen this app: it says what the
         * game is, what the pieces are, and that the answer has to be JSON and nothing
         * else. The size hints matter more than they look — without them models place a
         * flag at the very top of the page with no way up to it.
         */
        val PROMPT: String = """
            You are a level designer for a 2D side-view platformer.

            Look at this hand-drawn picture and turn what is drawn into a playable level.
            Follow the shapes in the drawing: whatever the picture shows — a castle, a
            face, a car, a mountain — becomes the ground, walls and ledges of the level.
            Do not invent a generic level; the player must be able to recognise their own
            drawing in it.

            Reply with ONE JSON object and no other text, in exactly this shape:
            {
              "title": "a short name for the level",
              "platforms": [{"x1":0.0,"y1":0.9,"x2":1.0,"y2":0.9,"thickness":0.012}],
              "lava": [{"x1":0.3,"y1":0.95,"x2":0.5,"y2":0.95,"thickness":0.02}],
              "coins": [{"x":0.4,"y":0.6}],
              "enemies": [{"x":0.6,"y":0.8}],
              "start": {"x":0.08,"y":0.8},
              "goal": {"x":0.92,"y":0.3}
            }

            Rules:
            - All coordinates are fractions of the picture: x 0 (left) to 1 (right),
              y 0 (top) to 1 (bottom).
            - "platforms" are solid lines the player stands on and bumps into. Trace the
              lines of the drawing with them. Use between 6 and 40 of them.
            - Always include one long platform near the bottom (y about 0.9) as the ground,
              unless the drawing clearly has its own floor.
            - The player can jump about 0.2 of the page upwards and 0.25 across. Never
              leave a gap bigger than that between platforms the player must cross, and put
              the platforms in a chain the player can climb from "start" to "goal".
            - "lava" is deadly. Use it sparingly, 0 to 6 lines.
            - "coins" are collectibles, 0 to 12 of them, above a platform.
            - "enemies" are creatures that patrol, 0 to 6 of them, standing on a platform.
            - "start" is where the player begins: on the left, just above a platform.
            - "goal" is the flag to reach: far from the start, just above a platform.
            - Nothing may be outside 0..1.
        """.trimIndent()

        /** How many of each kind to accept, so a runaway reply cannot build a huge level. */
        private const val MAX_LINES = 80
        private const val MAX_POINTS = 30

        /**
         * Reads a plan out of whatever the model actually said.
         *
         * Everything here is defensive by design. The reply is generated text: fields go
         * missing, numbers arrive as strings, coordinates land outside the page, and lists
         * occasionally run to hundreds of entries. None of that should do more than cost
         * the level one platform.
         */
        fun parse(reply: String): LevelPlan? {
            val root = Json.parseFirstObject(reply) ?: return null
            // Some models wrap the answer once more, in "level" or "response".
            val body = listOf(root, root["level"], root["response"])
                .firstOrNull { it["platforms"] != null } ?: root

            val plan = LevelPlan(
                title = body["title"].asText()?.trim()?.take(40).orEmpty().ifBlank { "AI level" },
                platforms = body["platforms"].toLines(),
                lava = body["lava"].toLines(),
                coins = body["coins"].toPoints(),
                enemies = body["enemies"].toPoints(),
                start = body["start"].toPoint(),
                goal = body["goal"].toPoint(),
            )
            return if (plan.isPlayable) plan else null
        }

        private fun JsonValue?.toLines(): List<PlanLine> = asList()
            .take(MAX_LINES)
            .mapNotNull { item ->
                val x1 = item["x1"].asFloatOrNull() ?: return@mapNotNull null
                val y1 = item["y1"].asFloatOrNull() ?: return@mapNotNull null
                val x2 = item["x2"].asFloatOrNull() ?: return@mapNotNull null
                val y2 = item["y2"].asFloatOrNull() ?: return@mapNotNull null
                val thickness = item["thickness"].asFloatOrNull() ?: PlanLine.DEFAULT_THICKNESS
                PlanLine(
                    x1 = x1.onPage(),
                    y1 = y1.onPage(),
                    x2 = x2.onPage(),
                    y2 = y2.onPage(),
                    thickness = thickness.coerceIn(0.004f, 0.06f),
                )
            }

        private fun JsonValue?.toPoints(): List<PlanPoint> = asList()
            .take(MAX_POINTS)
            .mapNotNull { it.toPoint() }

        private fun JsonValue?.toPoint(): PlanPoint? {
            val x = this["x"].asFloatOrNull() ?: return null
            val y = this["y"].asFloatOrNull() ?: return null
            return PlanPoint(x.onPage(), y.onPage())
        }

        /** Models overshoot the page, and a platform at y = 1.4 is simply not there. */
        private fun Float.onPage(): Float = if (isNaN()) 0.5f else coerceIn(0f, 1f)
    }
}
