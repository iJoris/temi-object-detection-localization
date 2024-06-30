package com.robotemi.sdk.sample.helpers

import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.sample.models.Point
import java.lang.Math.toRadians
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Checks if two positions are similar. We do this because the position data from the robot is not 100% accurate.
 *
 * @param position1 Pos 1
 * @param position2 Pos 2
 * @param margin The margin of error.
 *
 * @return true if the positions are similar, false otherwise.
 */
fun positionsAreSimilar(position1: Position, position2: Position, margin: Float): Boolean {
    val result = abs(position1.x - position2.x) < margin &&
            abs(position1.y - position2.y) < margin && abs(position1.yaw - position2.yaw) < margin

    //Log.d("LocationUtils", "position1: $position1, position2: $position2")
    //Log.d("LocationUtils", "positionsAreSimilar: $result")
    return result
}

/**
 * Checks if two lines are similar within a margin of error.
 *
 * @param Line1
 * @param Line2
 *
 * @return true if the lines are similar, false otherwise.
 */
fun areLinesSimilar(line1: Pair<Point, Point>, line2: Pair<Point, Point>): Boolean {
    val angle1 = atan2((line1.second.y - line1.first.y).toDouble(), (line1.second.x - line1.first.x).toDouble())
    val angle2 = atan2((line2.second.y - line2.first.y).toDouble(), (line2.second.x - line2.first.x).toDouble())
    return abs(angle1 - angle2) < toRadians(20.0)
}

/**
 * Merges two lines into one line by taking their averages.
 *
 * @param line1
 * @param line2
 *
 * @return The merged line.
 */
fun mergeLines(line1: Pair<Point, Point>, line2: Pair<Point, Point>): Pair<Point, Point> {
    val startX = (line1.first.x + line2.first.x) / 2
    val startY = (line1.first.y + line2.first.y) / 2
    val endX = (line1.second.x + line2.second.x) / 2
    val endY = (line1.second.y + line2.second.y) / 2
    return Pair(Point(startX, startY), Point(endX, endY))
}

/**
 * Consolidates similar lines into one line.
 *
 * @param lines The list of lines to consolidate.
 *
 * @return The consolidated list of lines.
 */
fun consolidateLines(lines: List<Pair<Point, Point>>): MutableList<Pair<Point, Point>> {
    val consolidated = mutableListOf<Pair<Point, Point>>()
    val used = HashSet<Int>()

    lines.forEachIndexed { index, line1 ->
        if (index in used) return@forEachIndexed
        var mergedLine = line1
        for (j in index + 1 until lines.size) {
            val line2 = lines[j]
            if (j !in used && areLinesSimilar(line1, line2)) {
                mergedLine = mergeLines(mergedLine, line2)
                used.add(j)
            }
        }
        consolidated.add(mergedLine)
        used.add(index)
    }

    return consolidated
}
