package com.robotemi.sdk.sample.helpers

import com.robotemi.sdk.map.LayerPose

/**
 * Selects a number of elements from a list evenly distributed across the list.
 *
 * @param list The list to select from.
 * @param numToSelect The number of elements to select.
 *
 * @return The selected elements.
 */
fun selectEvenlyFromList(list: List<LayerPose>, numToSelect: Int): List<LayerPose> {
    if (list.isEmpty() || numToSelect <= 0) {
        return emptyList()
    }
    if (numToSelect >= list.size) {
        return list
    }

    val resultList = mutableListOf<LayerPose>()
    if (numToSelect == 1) {
        // If only 1 selection, select the middle element
        resultList.add(list[list.size / 2])
        return resultList
    }

    // Calculate the step size differently based on the number of selections
    val stepSize = if (numToSelect == 2) {
        // For 2 selections, divide the list into 3
        (list.size / 3.0)
    } else {
        // For 3 or more selections, evenly distribute across the list including both ends to include start/end
        (list.size - 1).toDouble() / (numToSelect - 1)
    }

    for (i in 0 until numToSelect) {
        val index = Math.round(i * stepSize).toInt()
            .coerceIn(0, list.size - 1)
        resultList.add(list[index])
    }

    return resultList
}
