package com.robotemi.sdk.sample.helpers

import android.graphics.Canvas
import android.graphics.Paint
import com.robotemi.sdk.sample.models.Point
import kotlin.math.sin


/**
 * Draw a line with a given angle.
 *
 * @param baseAngle The base angle of the line.
 * @param canvas The canvas to draw on.
 * @param mirroredDotX The x coordinate of the dot.
 * @param dotY The y coordinate of the dot.
 * @param angleOffset The offset of the angle.
 * @param lineLength The length of the line.
 * @param linePaint The paint to draw the line with.
 *
 * @return The start and end points of the line.
 */

fun drawLineWithAngle(
    baseAngle: Float,
    canvas: Canvas,
    mirroredDotX: Int,
    dotY: Int,
    angleOffset: Float,
    lineLength: Int,
    linePaint: Paint
): Pair<Point, Point> {
    val angle = baseAngle + angleOffset
    val endX = (mirroredDotX + lineLength * kotlin.math.cos(angle))
    val endY = (dotY + lineLength * sin(angle))
    canvas.drawLine(
        mirroredDotX.toFloat(),
        dotY.toFloat(),
        endX,
        endY,
        linePaint
    )
    return Pair(Point(mirroredDotX.toFloat(), dotY.toFloat()), Point(endX, endY))
}