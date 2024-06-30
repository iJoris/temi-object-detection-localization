package com.robotemi.sdk.sample.analyse

import com.robotemi.sdk.sample.models.Point
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PositionLogic class to which contains logic for position calculations.
 * */
class PositionLogic {

    /**
     * Calculates the intersection point of two lines.
     *
     * The two lines are provided via (p1, p2) and (p3, p4).
     *
     * @param p1 The starting point of the first line.
     * @param p2 The ending point of the first line.
     * @param p3 The starting point of the second line.
     * @param p4 The ending point of the second line.
     * @return The intersection point of the two lines, or null no intersection exists.
     */
    fun getIntersection(p1: Point, p2: Point, p3: Point, p4: Point): Point? {
        val denominator = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (denominator == 0f) return null

        val intersectX = ((p1.x * p2.y - p1.y * p2.x) * (p3.x - p4.x) - (p1.x - p2.x) * (p3.x * p4.y - p3.y * p4.x)) / denominator
        val intersectY = ((p1.x * p2.y - p1.y * p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x * p4.y - p3.y * p4.x)) / denominator

        return Point(intersectX, intersectY)
    }

    /**
     * Converts a bitmap point (coordinates) to map coordinates.
     *
     *
     * @param mirroredX The X coordinate in the bitmap. Everything is mirrored in the bitmap.
     * @param y The Y coordinate in the bitmap.
     * @param mapOriginX The X coordinate of the origin point.
     * @param mapOriginY The Y coordinate of the origin point in the real world.
     * @param resolution The resolution of the bitmap.
     * @param bitmapWidth The width of the bitmap.
     * @return The real-world coordinates as a Point.
     */
    fun bitmapToRealWorld(mirroredX: Float, y: Float, mapOriginX: Float, mapOriginY: Float, resolution: Float, bitmapWidth: Int): Point {
        val originalX = bitmapWidth - mirroredX  // Reverse the mirroring effect

        val realX = (originalX * resolution) + mapOriginX
        val realY = (y * resolution) + mapOriginY
        return Point(realX, realY)
    }

    /**
     * Merges all the intersections into a single point.
     *
     *
     * @param points The list of points to merge.
     * @return The list of merged points.
     */
    fun mergePoints(points: MutableList<Point>): MutableList<Point> {
        val mergedPoints = mutableListOf<Point>()
        while (points.isNotEmpty()) {
            val base = points.removeAt(0)
            val cluster = mutableListOf(base)

            points.removeAll { point ->
                if (sqrt((base.x - point.x).pow(2) + (base.y - point.y).pow(2)) < 500) {
                    cluster.add(point)
                    true
                } else {
                    false
                }
            }

            val centroidX = cluster.map { it.x }.average()
            val centroidY = cluster.map { it.y }.average()
            mergedPoints.add(Point(centroidX.toFloat(), centroidY.toFloat()))
        }
        return mergedPoints
    }


}