package com.robotemi.sdk.sample

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.RelativeLayout
import androidx.annotation.CheckResult
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener
import com.robotemi.sdk.map.LayerPose
import com.robotemi.sdk.map.MapDataModel
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.permission.Permission
import com.robotemi.sdk.sample.analyse.Analyse
import com.robotemi.sdk.sample.analyse.PositionLogic
import com.robotemi.sdk.sample.databinding.ActivityPhotoBinding
import com.robotemi.sdk.sample.helpers.consolidateLines
import com.robotemi.sdk.sample.helpers.drawLineWithAngle
import com.robotemi.sdk.sample.helpers.getOutputDirectory
import com.robotemi.sdk.sample.helpers.positionsAreSimilar
import com.robotemi.sdk.sample.helpers.selectEvenlyFromList
import com.robotemi.sdk.sample.interfaces.PhotoSessionCompleteListener
import com.robotemi.sdk.sample.models.MenuItem
import com.robotemi.sdk.sample.models.PhotoPosition
import com.robotemi.sdk.sample.models.PhotoSession
import com.robotemi.sdk.sample.models.Photov2
import com.robotemi.sdk.sample.models.Point
import com.robotemi.sdk.sample.models.SessionQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PhotoActivity : AppCompatActivity(), OnMovementStatusChangedListener,
    PhotoSessionCompleteListener, OnCurrentPositionChangedListener {

    private lateinit var imageCapture: ImageCapture
    private var leftOverlay: View? = null
    private var rightOverlay: View? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var binding: ActivityPhotoBinding

    private var currentRotationAttempt = 0
    private val maxRotationAttempts = 3

    private var movementCheckJob: Job? = null

    private var requestingPhoto = false
    private var takingPhotos = false
    private var photoSessionId = ""
    private var photoNr = 0
    private var photoLocationsTaken = 0
    private var locationsVisiting = mutableListOf<Position>()
    private var rotationsLeft = 0

    private var lastPositionUpdateTime: Long = System.currentTimeMillis()
    private var lastPosition: Position? = null
    private var destination: Position? = null
    private var startPosition: Position? = null
    private var detectedPosition: Position? = null

    private var photos = mutableListOf<Photov2>()

    private lateinit var analyzer: Analyse
    private lateinit var positionLogic: PositionLogic

    private lateinit var robot: Robot

    private var showResultJob: Job? = null

    private var completedRun = false
    private var running = false

    private var startWidth = 0
    private var endWidth = 4000
    private var cropSize = 1.0

    // 0 = focused crop, 1 = split photo
    private var mode = 0

    private val TAG = "PhotoActivity"
    private val REQUEST_CODE_PERMISSIONS = 10
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private var modelLoaded = false

    private var completedList = mutableListOf<String>()

    private var amountOfStops = 0
    private var targetObjectId = 0
    private var amountOfPhotosPerStop = 0

    private var model: Yolov8Ncnn? = null

    @Volatile
    private var mapDataModel: MapDataModel? = null

    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val sessionQueue = mutableListOf<SessionQueue>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        robot = Robot.getInstance()
        robot.addOnMovementStatusChangedListener(this)

        analyzer = Analyse(this)
        positionLogic = PositionLogic()

        addEvents()

        val options1 = arrayOf(MenuItem("3", 3), MenuItem("4", 4), MenuItem("5", 5))
        val options2 = arrayOf(MenuItem("Crop", 0), MenuItem("Split", 1))
        val options3 = arrayOf(MenuItem("8", 8), MenuItem("10", 10), MenuItem("12", 12))
        val options4 = arrayOf(MenuItem("Chair", 56), MenuItem("Bottle", 39)) // object ids are from the YOLOv8 model.

        val adapter1 = ArrayAdapter(this, android.R.layout.simple_spinner_item, options1)
        adapter1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val adapter2 = ArrayAdapter(this, android.R.layout.simple_spinner_item, options2)
        adapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val adapter3 = ArrayAdapter(this, android.R.layout.simple_spinner_item, options3)
        adapter3.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val adapter4 = ArrayAdapter(this, android.R.layout.simple_spinner_item, options4)
        adapter4.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.stops.adapter = adapter1
        binding.method.adapter = adapter2
        binding.rotations.adapter = adapter3
        binding.objectid.adapter = adapter4
    }

    // Add position listener to the onStart
    override fun onStart() {
        super.onStart()
        robot.addOnCurrentPositionChangedListener(this)
    }

    // Remove position listener on exit.
    override fun onStop() {
        robot.removeOnCurrentPositionChangedListener(this)
        super.onStop()
    }


    // Update UI based on if we completed a session.
    private fun setUi() {
        adjustOverlayForCrop()

        // Hide the preview and show the result photos if a run is completed and we have photos loaded.
        if (photos.isNotEmpty() && completedRun) {
            binding.previewView.visibility = View.GONE
            binding.imageViewFace.visibility = View.VISIBLE
        } else {
            binding.previewView.visibility = View.VISIBLE
            binding.imageViewFace.visibility = View.GONE
        }
    }


    // Used to get the map permissions on the temi robot. Do not delete.
    @CheckResult
    private fun requestPermissionIfNeeded(permission: Permission, requestCode: Int): Boolean {
        if (robot.checkSelfPermission(permission) == Permission.GRANTED) {
            return false
        }

        robot.requestPermissions(listOf(permission), requestCode)
        return true
    }

    private fun walkToPosition(position: Position) {
        Log.d(TAG, "Walking to position $position")
        destination = position
        robot.goToPosition(position)
    }

    // Function that is responsible for the map on the right side of the screen.
    private fun mapData() {
        allTemiPermissionsGranted()
        var lines = mutableListOf<Pair<Point, Point>>()

        singleThreadExecutor.execute {
            // Get current map loaded from temi.
            mapDataModel = Robot.getInstance().getMapData() ?: return@execute
            val mapImage = mapDataModel!!.mapImage

            val localBitmap = Bitmap.createBitmap(
                mapImage.data.map { Color.argb((it * 2.55).roundToInt(), 0, 0, 0) }.toIntArray(),
                mapImage.cols,
                mapImage.rows,
                Bitmap.Config.ARGB_8888
            ).copy(Bitmap.Config.ARGB_8888, true)

            // Mirror the bitmap horizontally
            val matrix = Matrix()
            matrix.preScale(-1f, 1f)

            val mirroredBitmap = Bitmap.createBitmap(
                localBitmap,
                0,
                0,
                localBitmap.width,
                localBitmap.height,
                matrix,
                false
            )

            val dotRadius = 5
            val dotColor = Color.RED
            val canvas = Canvas(mirroredBitmap)
            val paintDot = Paint().apply {
                color = dotColor
                isAntiAlias = true
            }
            val currentPositionDot = Paint().apply {
                color = Color.CYAN
                isAntiAlias = true
            }
            val detectionLine = Paint().apply {
                color = Color.GREEN
                strokeWidth = 2f
                isAntiAlias = true
            }
            val normalLine = Paint().apply {
                color = Color.RED
                strokeWidth = 2f
                isAntiAlias = true
            }

            // draw the current position of temi
            if (lastPosition != null) {
                val mapOriginX = mapDataModel!!.mapInfo.originX
                val mapOriginY = mapDataModel!!.mapInfo.originY

                val adjustedX = lastPosition!!.x - mapOriginX
                val adjustedY = lastPosition!!.y - mapOriginY

                val resolution = mapDataModel!!.mapInfo.resolution

                val dotX = (adjustedX / resolution).toInt()
                val dotY = (adjustedY / resolution).toInt()

                val mirroredDotX = mirroredBitmap.width - dotX
                canvas.drawCircle(
                    mirroredDotX.toFloat(),
                    dotY.toFloat(),
                    dotRadius.toFloat(),
                    currentPositionDot
                )
            }

            if (completedRun) {
                for (photo in photos) {
                    // draw regular lines without detecitons
                    if(photo.detectedObjects.isEmpty()){
                        val mapOriginX = mapDataModel!!.mapInfo.originX
                        val mapOriginY = mapDataModel!!.mapInfo.originY

                        val adjustedX = photo.photoPosition.x - mapOriginX
                        val adjustedY = photo.photoPosition.y - mapOriginY

                        val resolution = mapDataModel!!.mapInfo.resolution

                        val dotX = (adjustedX / resolution).toInt()
                        val dotY = (adjustedY / resolution).toInt()

                        val mirroredDotX = mirroredBitmap.width - dotX
                        var angle = photo.photoPosition.yaw
                        angle = (Math.PI - angle).toFloat()

                        val lineLength = 60

                        val endX = (mirroredDotX + lineLength * cos(angle))
                        val endY = (dotY + lineLength * sin(angle))

                        canvas.drawLine(
                            mirroredDotX.toFloat(),
                            dotY.toFloat(),
                            endX,
                            endY,
                            normalLine
                        )

                    } else {
                        // draw detection lines
                        for (detectedObject in photo.detectedObjects) {
                            // Draw lines for each photo's position and angle
                            val mapOriginX = mapDataModel!!.mapInfo.originX
                            val mapOriginY = mapDataModel!!.mapInfo.originY

                            val adjustedX = photo.photoPosition.x - mapOriginX
                            val adjustedY = photo.photoPosition.y - mapOriginY

                            val resolution = mapDataModel!!.mapInfo.resolution

                            val dotX = (adjustedX / resolution).toInt()
                            val dotY = (adjustedY / resolution).toInt()

                            val mirroredDotX = mirroredBitmap.width - dotX

                            if (photo.detectionMode == 0) {
                                var angle = photo.photoPosition.yaw
                                angle = (Math.PI - angle).toFloat()

                                val lineLength = 60

                                val endX = (mirroredDotX + lineLength * cos(angle))
                                val endY = (dotY + lineLength * sin(angle))

                                lines.add(
                                    Pair(
                                        Point(mirroredDotX.toFloat(), dotY.toFloat()),
                                        Point(endX, endY)
                                    )
                                )

                                canvas.drawLine(
                                    mirroredDotX.toFloat(),
                                    dotY.toFloat(),
                                    endX,
                                    endY,
                                    detectionLine
                                )

                                // if using split photo draw with a angle
                            } else if (photo.detectionMode == 1) {
                                val lineLength = 60
                                var baseAngle = photo.photoPosition.yaw
                                baseAngle = (Math.PI - baseAngle).toFloat()

                                val positions = mapOf(
                                    0 to -(Math.PI / 5 * 2).toFloat(), // Left
                                    1 to -(Math.PI / 5).toFloat(),     // Left-Middle
                                    2 to 0f,                           // Center
                                    3 to (Math.PI / 5).toFloat(),      // Right-Middle
                                    4 to (Math.PI / 5 * 2).toFloat()   // Right
                                )

                                positions.forEach { (position, angleOffset) ->
                                    if(detectedObject.position == position){
                                        val line = drawLineWithAngle(
                                            baseAngle,
                                            canvas,
                                            mirroredDotX,
                                            dotY,
                                            angleOffset,
                                            lineLength,
                                            detectionLine
                                        )
                                        lines.add(line)
                                    }
                                }
                            }
                        }

                    }

                    val detectionPoints = mutableListOf<Point>()

                    // add point of detection
                    if (photo.detectedObjects.any()) {
                        detectionPoints.add(Point(photo.photoPosition.x, photo.photoPosition.y))
                    }

                    for (detectionPoint in detectionPoints) {
                        val mapOriginX = mapDataModel!!.mapInfo.originX
                        val mapOriginY = mapDataModel!!.mapInfo.originY

                        val adjustedX = detectionPoint.x - mapOriginX
                        val adjustedY = detectionPoint.y - mapOriginY

                        val resolution = mapDataModel!!.mapInfo.resolution

                        val dotX = (adjustedX / resolution).toInt()
                        val dotY = (adjustedY / resolution).toInt()

                        val mirroredDotX = mirroredBitmap.width - dotX

                        canvas.drawCircle(
                            mirroredDotX.toFloat(),
                            dotY.toFloat(),
                            dotRadius.toFloat(),
                            paintDot
                        )
                    }
                }

                Log.d(TAG, "Lines before filter: ${lines.count()}")

                lines = consolidateLines(lines)

                Log.d(TAG, "Lines after filter: ${lines.count()}")

                val intersectionPoints = mutableListOf<Point>()
                for (i in lines.indices) {
                    for (j in i + 1 until lines.size) {
                        val intersect = positionLogic.getIntersection(
                            lines[i].first, lines[i].second,
                            lines[j].first, lines[j].second
                        )

                        Log.d(TAG, "Intersect: $intersect")

                        if (intersect != null && !intersectionPoints.contains(intersect)) {
                            // Calculate distances from the start of each line to the intersection
                            val distanceFromLine1 = Math.sqrt(
                                Math.pow((intersect.x - lines[i].first.x).toDouble(), 2.0) +
                                        Math.pow((intersect.y - lines[i].first.y).toDouble(), 2.0)
                            )
                            val distanceFromLine2 = Math.sqrt(
                                Math.pow((intersect.x - lines[j].first.x).toDouble(), 2.0) +
                                        Math.pow((intersect.y - lines[j].first.y).toDouble(), 2.0)
                            )

                            // Check if the intersection is too far away of too closeby
                            if (distanceFromLine1 > 10 && distanceFromLine2 > 10
                                && distanceFromLine1 < 100 && distanceFromLine2 < 100) {
                                intersectionPoints.add(intersect)
                            }
                        }
                    }
                }

                // marge all intersections
                val mergedPoints = positionLogic.mergePoints(intersectionPoints)

                val intersectionPaint = Paint().apply {
                    color = Color.YELLOW
                    style = Paint.Style.FILL
                    strokeWidth = 5f
                    isAntiAlias = true
                }

                val realWorldIntersections = mutableListOf<Point>()

                for (point in mergedPoints) {
                    canvas.drawCircle(point.x, point.y, 5f, intersectionPaint)

                    val realWorldPoint = positionLogic.bitmapToRealWorld(
                        point.x, point.y,
                        mapDataModel!!.mapInfo.originX, mapDataModel!!.mapInfo.originY,
                        mapDataModel!!.mapInfo.resolution,
                        mirroredBitmap.width
                    )

                    realWorldIntersections.add(realWorldPoint)
                }

                if (realWorldIntersections.isNotEmpty()) {

                    //log all points
                    for (point in realWorldIntersections) {
                        Log.d(
                            "Intersection",
                            "Intersection point: X: ${point.x}, Y: ${point.y} for run $photoSessionId"
                        )
                    }

                    detectedPosition =
                        Position(realWorldIntersections[0].x, realWorldIntersections[0].y, 0f)

                } else {
                    Log.d(
                        "Intersection",
                        "No intersection found for run"
                    )
                }
            }

            runOnUiThread {
                binding.imageViewMap.setImageBitmap(mirroredBitmap)
            }
        }
    }

    // Function to analyze photos and calculate the intersections manually.
    private fun manualCalculateIntersections() {
        var lines = mutableListOf<Pair<Point, Point>>()

        singleThreadExecutor.execute {
            // Get current map loaded from temi.
            mapDataModel = Robot.getInstance().getMapData() ?: return@execute
            val mapImage = mapDataModel!!.mapImage

            val localBitmap = Bitmap.createBitmap(
                mapImage.data.map { Color.argb((it * 2.55).roundToInt(), 0, 0, 0) }.toIntArray(),
                mapImage.cols,
                mapImage.rows,
                Bitmap.Config.ARGB_8888
            ).copy(Bitmap.Config.ARGB_8888, true)

            // Mirror the bitmap horizontally
            val matrix = Matrix()
            matrix.preScale(-1f, 1f)

            val mirroredBitmap = Bitmap.createBitmap(
                localBitmap,
                0,
                0,
                localBitmap.width,
                localBitmap.height,
                matrix,
                false
            )

            val dotRadius = 5
            val dotColor = Color.RED
            val canvas = Canvas(mirroredBitmap)
            val paintDot = Paint().apply {
                color = dotColor
                isAntiAlias = true
            }
            val detectionLine = Paint().apply {
                color = Color.GREEN
                strokeWidth = 2f // Adjust line width as needed
                isAntiAlias = true
            }

            for (photo in photos) {
                // draw detection lines
                for (detectedObject in photo.detectedObjects) {
                    // Draw lines for each photo's position and angle
                    val mapOriginX = mapDataModel!!.mapInfo.originX
                    val mapOriginY = mapDataModel!!.mapInfo.originY

                    val adjustedX = photo.photoPosition.x - mapOriginX
                    val adjustedY = photo.photoPosition.y - mapOriginY

                    val resolution = mapDataModel!!.mapInfo.resolution

                    val dotX = (adjustedX / resolution).toInt()
                    val dotY = (adjustedY / resolution).toInt()

                    val mirroredDotX = mirroredBitmap.width - dotX

                    if (photo.detectionMode == 0) {
                        var angle = photo.photoPosition.yaw
                        angle = (Math.PI - angle).toFloat()

                        val lineLength = 60

                        val endX = (mirroredDotX + lineLength * cos(angle))
                        val endY = (dotY + lineLength * sin(angle))

                        lines.add(
                            Pair(
                                Point(mirroredDotX.toFloat(), dotY.toFloat()),
                                Point(endX, endY)
                            )
                        )

                        canvas.drawLine(
                            mirroredDotX.toFloat(),
                            dotY.toFloat(),
                            endX,
                            endY,
                            detectionLine
                        )

                        // if using split photo draw with a angle
                    } else if (photo.detectionMode == 1) {
                        val lineLength = 60
                        var baseAngle = photo.photoPosition.yaw
                        baseAngle = (Math.PI - baseAngle).toFloat()

                        val positions = mapOf(
                            0 to -(Math.PI / 5 * 2).toFloat(), // Left
                            1 to -(Math.PI / 5).toFloat(),     // Left-Middle
                            2 to 0f,                           // Center
                            3 to (Math.PI / 5).toFloat(),      // Right-Middle
                            4 to (Math.PI / 5 * 2).toFloat()   // Right
                        )

                        positions.forEach { (position, angleOffset) ->
                            if(detectedObject.position == position){
                                val line = drawLineWithAngle(
                                    baseAngle,
                                    canvas,
                                    mirroredDotX,
                                    dotY,
                                    angleOffset,
                                    lineLength,
                                    detectionLine
                                )

                                lines.add(line)
                            }
                        }
                    }
                }

                val detectionPoints = mutableListOf<Point>()

                // add point of detection
                if (photo.detectedObjects.any()) {
                    detectionPoints.add(Point(photo.photoPosition.x, photo.photoPosition.y))
                }

                for (detectionPoint in detectionPoints) {
                    val mapOriginX = mapDataModel!!.mapInfo.originX
                    val mapOriginY = mapDataModel!!.mapInfo.originY

                    val adjustedX = detectionPoint.x - mapOriginX
                    val adjustedY = detectionPoint.y - mapOriginY

                    val resolution = mapDataModel!!.mapInfo.resolution

                    val dotX = (adjustedX / resolution).toInt()
                    val dotY = (adjustedY / resolution).toInt()

                    val mirroredDotX = mirroredBitmap.width - dotX

                    canvas.drawCircle(
                        mirroredDotX.toFloat(),
                        dotY.toFloat(),
                        dotRadius.toFloat(),
                        paintDot
                    )
                }
            }

            Log.d(TAG, "Lines before filter: ${lines.count()}")

            lines = consolidateLines(lines)

            Log.d(TAG, "Lines after filter: ${lines.count()}")

            val intersectionPoints = mutableListOf<Point>()
            for (i in lines.indices) {
                for (j in i + 1 until lines.size) {
                    val intersect = positionLogic.getIntersection(
                        lines[i].first, lines[i].second,
                        lines[j].first, lines[j].second
                    )
                    if (intersect != null && !intersectionPoints.contains(intersect)) {
                        // Calculate distances from the start of each line to the intersection
                        val distanceFromLine1 = Math.sqrt(
                            Math.pow((intersect.x - lines[i].first.x).toDouble(), 2.0) +
                                    Math.pow((intersect.y - lines[i].first.y).toDouble(), 2.0)
                        )
                        val distanceFromLine2 = Math.sqrt(
                            Math.pow((intersect.x - lines[j].first.x).toDouble(), 2.0) +
                                    Math.pow((intersect.y - lines[j].first.y).toDouble(), 2.0)
                        )

                        // Check if the intersection is too far away of too closeby
                        if (distanceFromLine1 > 10 && distanceFromLine2 > 10
                            && distanceFromLine1 < 100 && distanceFromLine2 < 100) {
                            intersectionPoints.add(intersect)
                        }
                    }
                }
            }

            // marge all intersections
            val mergedPoints = positionLogic.mergePoints(intersectionPoints)

            val intersectionPaint = Paint().apply {
                color = Color.YELLOW
                style = Paint.Style.FILL
                strokeWidth = 5f
                isAntiAlias = true
            }

            val realWorldIntersections = mutableListOf<Point>()

            for (point in mergedPoints) {
                canvas.drawCircle(point.x, point.y, 5f, intersectionPaint)

                val realWorldPoint = positionLogic.bitmapToRealWorld(
                    point.x, point.y,
                    mapDataModel!!.mapInfo.originX, mapDataModel!!.mapInfo.originY,
                    mapDataModel!!.mapInfo.resolution,
                    mirroredBitmap.width
                )

                realWorldIntersections.add(realWorldPoint)
            }

            Log.d("Intersection", "${photoSessionId} Method: $mode, Stops: $amountOfStops, Rotations: $amountOfPhotosPerStop, Target: $targetObjectId lines: ${lines.count()}")

            if (realWorldIntersections.isNotEmpty()) {
                //log all points
                for (point in realWorldIntersections) {
                    Log.d(
                        "Intersection",
                        "Intersection point: X: ${point.x}, Y: ${point.y}"
                    )
                }

                detectedPosition =
                    Position(realWorldIntersections[0].x, realWorldIntersections[0].y, 0f)

            } else {
                Log.d(
                    "Intersection",
                    "$photoSessionId No intersection found"
                )
            }
        }
    }

    // Function to start a new photo session.
    private fun startPathSession(
        pathId: String,
        paths: List<LayerPose>,
        stops: Int,
        rotations: Int
    ) {
        photoSessionId = System.currentTimeMillis().toString() + "-" + pathId // id based on current time + path id.
        running = true
        completedRun = false
        photoLocationsTaken = 0
        rotationsLeft = 0
        photoNr = 0
        photos.clear()
        showResultJob?.cancel()

        setUi()
        adjustOverlayForCrop()

        locationsVisiting = paths.map { Position(it.x, it.y, 1.0f) }.toMutableList()

        Log.d(
            TAG,
            "Starting photo session $photoSessionId with $stops stops and $rotations rotations target object $targetObjectId and method $mode"
        )

        lifecycleScope.launch {
            Log.d(TAG, "Tilting head START")

            // Set the robot's head straight to take photos.
            robot.tiltAngle(0)

            Log.d(TAG, "Tilting head DONE")

            val firstPosition = paths.first()

            Log.d(TAG, "Moving to first position ($firstPosition)")

            walkToPosition(locationsVisiting[0])

            // Setting this variable to true that will be checked in the movement listener.
            requestingPhoto = true
        }
    }

    private fun addEvents() {
        binding.start.setOnClickListener {
            val method = (binding.method.selectedItem as MenuItem).id
            val stops = (binding.stops.selectedItem as MenuItem).id
            val rotations = (binding.rotations.selectedItem as MenuItem).id
            val objectId = (binding.objectid.selectedItem as MenuItem).id

            startNextSession(method, stops, rotations, objectId)
        }

        // Function to do multiple sessions in a queue.
        /* binding.queueBtn.setOnClickListener {
             // mode / stops / rotation / object class (56 chair, 39 bottle)
             sessionQueue.add(SessionQueue(1, 4, 10, 56))
             sessionQueue.add(SessionQueue(1, 4, 12, 56))

             val first = sessionQueue.removeAt(0)
             startNextSession(first.method, first.stops, first.rotations, first.objectClass)
         }*/

        // Function to manually analyze photos. Used for evaluation.
       /*binding.analyze.setOnClickListener {
           val photoDataPaths = arrayOf(
               "1718887490020-path_1717501499263_2017-photo-data.json",
               "1718888877148-path_1717501499263_2017-photo-data.json",
               "1718889315132-path_1717501499263_2017-photo-data.json",
               "1718889868098-path_1717501499263_2017-photo-data.json",
               "1718890511418-path_1717501499263_2017-photo-data.json",
               "1719232173319-path_1717501499263_2017-photo-data.json",
               "1719232915355-path_1717501499263_2017-photo-data.json",
               "1718893615197-path_1717501499263_2017-photo-data.json",
               "1718892626401-path_1717501499263_2017-photo-data.json",
               "1718893075317-path_1717501499263_2017-photo-data.json",
               "1719230671808-path_1717501499263_2017-photo-data.json",
               "1719231237790-path_1717501499263_2017-photo-data.json",
               "1718894735696-path_1717501499263_2017-photo-data.json",
               "1718895986560-path_1717501499263_2017-photo-data.json",
               "1718896456261-path_1717501499263_2017-photo-data.json",
               "1718897001655-path_1717501499263_2017-photo-data.json",
               "1718976880919-path_1717501499263_2017-photo-data.json",
               "1719221298314-path_1717501499263_2017-photo-data.json",
               "1719223127972-path_1717501499263_2017-photo-data.json"
           )

            for(id in photoDataPaths){
                var jsonFile = id

                Log.d(TAG, "Analyzing $jsonFile")

                val gson = Gson()

                val json = getOutputDirectory(this)
                    .resolve(jsonFile).readText()
                val session = gson.fromJson(json, PhotoSession::class.java)

                photos = session.photosTaken.filter { it.detectedObjects.isNotEmpty() }.toMutableList()

                Log.d(TAG, "Photos: ${photos.count()}")

                photoSessionId = session.sessionId
                amountOfStops = session.stops
                targetObjectId = session.targetObject
                mode = session.methodId
                amountOfPhotosPerStop = session.rotations

                manualCalculateIntersections()

                // wait 2 sec
                Thread.sleep(1000)
            }
        }
        // function to manually analyze a single session
        binding.btnAnalyzePhotosCrop.setOnClickListener {
            lifecycleScope.launch {
                if (photos.count() < 2) {
                    val gson = Gson()

                    val json = withContext(Dispatchers.IO) {
                        getOutputDirectory(this@PhotoActivity)
                        .resolve("1719584731206-path_1717501499263_2017-photo-data.json").readText()
                    }
                    val session = gson.fromJson(json, PhotoSession::class.java)

                    photos = session.photosTaken.toMutableList()
                    photos.forEach {
                        it.detectedObjects = mutableListOf()
                    }
                }
                completedRun = true

                showResult()
            }
        }

        */

        binding.moveObjectPos.setOnClickListener {
            if (detectedPosition != null) {
                walkToPosition(detectedPosition!!)
            }
        }
    }

    private fun showResult() {
        // if there is another session in the queue, start it.
        if (sessionQueue.isNotEmpty()) {
            val nextSession = sessionQueue.removeAt(0)
            startNextSession(nextSession.method, nextSession.stops, nextSession.rotations, nextSession.objectClass)
        } else {
            Log.d(TAG, "Done taking all photos")

            //loadModel()

            for (id in completedList) {
                var jsonFile = "$id-photo-data.json"

                val gson = Gson()

                val json = getOutputDirectory(this)
                    .resolve(jsonFile).readText()
                val session = gson.fromJson(json, PhotoSession::class.java)

                photos = session.photosTaken.toMutableList()

                for (photo in photos) {
                    photo.originalPhoto = null
                }

                photos = analyzer.analysePhotos(model as Yolov8Ncnn, photos, session.targetObject).toMutableList()
            }

            // unloadModel()

            showResultJob?.cancel()
            setUi()

            showResultJob = lifecycleScope.launch {
                Log.d(TAG, "Showing result")
                mapData()

                // Loop forever.
                while(true){
                    for (photo in photos) {
                        withContext(Dispatchers.Main) {
                            binding.imageViewFace.setImageURI(Uri.parse(photo.analysedUri))

                            if(photo.detectionMode == 1 && photo.detectedObjects.any()){
                                val firstObject = photo.detectedObjects.first()
                                var objectPosition = "left"

                                when (firstObject.position) {
                                    0 -> objectPosition = "left"
                                    1 -> objectPosition = "left-middle"
                                    2 -> objectPosition = "middle"
                                    3 -> objectPosition = "right-middle"
                                    4 -> objectPosition = "right"
                                }

                                binding.objectPlacement.text = "Object: ${firstObject.label} - Position: ${objectPosition}"
                            } else {
                                binding.objectPlacement.text = "";
                            }
                        }

                        delay(3000)
                    }
                }
            }

        }
    }

    // Load the YOLOv8 model when needed.
    // Important is that you unload the model when you are done with it, otherwise you get memory problems.
    private fun loadModel() {
        if (!modelLoaded || model == null) {
            model = Yolov8Ncnn()
            val success = model!!.loadModel(assets, 1, 0)
            if (!success) {
                Log.e(TAG, "Failed to load model")
            } else {
                Log.d(TAG, "Yolo v8 Model loaded")
                modelLoaded = true
            }
        }
    }

    // Unload the model when done analyzing photos.
    private fun unloadModel() {
        if (modelLoaded && model != null) {
            model!!.unloadModel()
            model = null
        }

        modelLoaded = false
    }

    // Start the camera and bind it to the preview view.
    private fun startCamera() {
        setUi()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // We do not want flash.
            imageCapture = ImageCapture.Builder()
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()

            try {
                cameraProvider.unbindAll()

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            // Show camera preview & hide camera preview
            binding.previewView.visibility = View.VISIBLE
            binding.imageViewFace.visibility = View.GONE

        }, ContextCompat.getMainExecutor(this))
    }


    // Rotate temi
    private fun rotateTemi() {
        lifecycleScope.launch {
            robot.turnBy(360 / amountOfPhotosPerStop - 1, 0.7f)
        }
    }

    // Internal function to take a photo
    // We added a delay of 5 seconds before taking the photo to ensure the robot is focused.
    // We retry upon failure to take the photo, because the camera can be locked.
    private suspend fun takePhotoInternally(): Boolean {
        val maxRetries = 3
        var attempt = 0

        while (attempt < maxRetries) {
            attempt++
            val result = withTimeoutOrNull(10000) {
                takePhotoWithCallback()
            }

            if (result == true) {
                return true
            } else {
                Log.d(TAG, "Retry attempt $attempt failed.")
            }
        }

        return false
    }

    // Function to take a photo with a callback so we know if it failed.
    private suspend fun takePhotoWithCallback(): Boolean {
        val photoCaptureCompletion = CompletableDeferred<Boolean>()

        val outputdir = getOutputDirectory(this)
        val photoFile = File(
            outputdir,
            "$photoSessionId-$photoLocationsTaken-$photoNr.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        Log.d(TAG, "Sleeping for 5 seconds before taking photo")
        delay(5000)
        Log.d(TAG, "Sleeping done for 5 seconds before taking photo")

        Log.d(TAG, "Taking photo $photoSessionId-$photoLocationsTaken-$photoNr")

        try {
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        photoCaptureCompletion.complete(false)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri = Uri.fromFile(photoFile)

                        val rawPhotoBitmap = BitmapFactory.decodeFile(photoFile.absolutePath)

                        val croppedWidth = endWidth - startWidth
                        val croppedBitmap = Bitmap.createBitmap(
                            rawPhotoBitmap,
                            startWidth,
                            0,
                            croppedWidth,
                            rawPhotoBitmap.height
                        )

                        val width = croppedBitmap.width
                        val height = croppedBitmap.height

                        try {
                            FileOutputStream(photoFile).use { out ->
                                croppedBitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    100,
                                    out
                                )

                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                            Log.e(TAG, "Error saving cropped image: ${e.localizedMessage}")
                            photoCaptureCompletion.complete(false)
                            return
                        } finally {
                            rawPhotoBitmap.recycle()
                            croppedBitmap.recycle()
                        }

                        Log.d(TAG, "Resolution of the saved image is: ${width}x$height - $savedUri")

                        val photoFileName =
                            photoFile.name.substring(0, photoFile.name.lastIndexOf('.'))

                        val newPhoto = Photov2().apply {
                            originalUri = savedUri.toString()
                            fileName = photoFileName
                            detectionMode = mode
                            photoLocation = photoLocation
                            index = photoNr
                            photoPosition = PhotoPosition().apply {
                                x = lastPosition?.x ?: 0f
                                y = lastPosition?.y ?: 0f
                                yaw = lastPosition?.yaw ?: 0f
                            }
                            session = photoSessionId
                        }

                        photos.add(newPhoto)

                        photoNr++

                        photoCaptureCompletion.complete(true)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking photo: ${e.localizedMessage}")
            photoCaptureCompletion.complete(false)
        }

        return withContext(Dispatchers.IO) {
            photoCaptureCompletion.await()
        }
    }

    // Request permissions for the map.
    //https://github.com/robotemi/sdk/wiki/permission#currentPermissions

    private fun allTemiPermissionsGranted(): Boolean {
        if (robot.checkSelfPermission(Permission.MAP) == Permission.GRANTED) {
            return false
        }

        robot.requestPermissions(listOf(Permission.MAP), 3)
        robot.requestPermissions(listOf(Permission.MAP), 8)
        robot.requestPermissions(listOf(Permission.MAP), 9)

        return true
    }

    // check if all permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }


    // Clean up the camera and executor for the movement check. Otherwise the camera will not be released.
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        singleThreadExecutor.shutdown()
        movementCheckJob?.cancel()

    }

    // event that is triggered when the movement status of the robot changes.
    // We use this to listen or abort (bug -> retry) and rotation completed (turnBy).
    override fun onMovementStatusChanged(type: String, status: String) {
        Log.d(TAG, "Movement status changed: $type, $status")
        Log.d(TAG, "Requesting photo: $requestingPhoto, rotations left: $rotationsLeft")

        if (status == "abort") {
            Log.d(TAG, "Movement aborted")

            if (type == "turnBy") {
                currentRotationAttempt++
                if (currentRotationAttempt <= maxRotationAttempts) {
                    Log.d(TAG, "Retrying turnBy attempt $currentRotationAttempt")
                    lifecycleScope.launch {
                        delay(12000)
                        robot.turnBy(360 / amountOfPhotosPerStop - 1, 0.7f)
                    }
                } else {
                    Log.e(TAG, "Max turnBy attempts reached, cannot retry further")
                    currentRotationAttempt = 0
                }
            }
            return
        }

        // We are done rotating the robot and all the photos are taken for the current pos.
        if (type == "turnBy" && status == "complete" && !requestingPhoto && rotationsLeft == 0 && photoLocationsTaken < locationsVisiting.count()) {
            Log.d(TAG, "Robot rotation complete, no rotations left. On photos taken.")
            currentRotationAttempt = 0
            onPhotosTaken()
        }

        // We are done rotating the robot and we are ready to take a photo.
        if (type == "turnBy" && status == "complete" && requestingPhoto && rotationsLeft > 0) {
            Log.d(
                TAG,
                "Robot rotation complete, attempting to take photo $photoSessionId-$photoLocationsTaken-$photoNr.."
            )
            rotationsLeft--
            currentRotationAttempt = 0

            takePhoto()
        }
    }

    // Function that takes a photo. It will retry if the photo was not taken successfully.
    // During development and testing this was necessary as the camera would sometimes fail to take a photo.
    private fun takePhoto() {
        lifecycleScope.launch {
            delay(3000)
            val photoTaken = takePhotoInternally()
            if (photoTaken) {
                Log.d(TAG, "Successfully took photo $photoSessionId-$photoLocationsTaken-$photoNr")

                if (photoNr >= amountOfPhotosPerStop) {
                    Log.d(TAG, "Took $amountOfPhotosPerStop photos on location $photoLocationsTaken.")

                    requestingPhoto = false
                    takingPhotos = false
                    photoNr = 0

                    onPhotosTaken()
                } else {
                    Log.d(TAG, "Rotating robot for next photo")
                    rotateTemi()
                }

            } else {
                Log.e(TAG, "Failed to take photo. Retrying..")
                takePhoto()
            }
        }
    }

    // Function that is called when all photos have been taken for the current position.
    // If it was the last position, the session is complete and we move home.
    // Otherwise, we move to the next position.
    override fun onPhotosTaken() {
        lifecycleScope.launch {
            Log.d(TAG, "Photos taken for current position.")

            if (photoLocationsTaken + 1 == amountOfStops) {
                Log.d(TAG, "All photos taken, session done..")
                walkToPosition(startPosition as Position)
                startPosition = null

                onPhotoSessionComplete()
            } else {
                val nextLocationNumber = photoLocationsTaken + 1
                Log.d(
                    TAG,
                    "Moving to next location.. ($photoLocationsTaken -> $nextLocationNumber) ($lastPosition) -> (${locationsVisiting[nextLocationNumber]})"
                )

                photoLocationsTaken++

                walkToPosition(locationsVisiting[nextLocationNumber])
                requestingPhoto = true
            }
        }

    }

    // Function that is called when the photo session is complete.
    override fun onPhotoSessionComplete() {
        Log.d(TAG, "Photo session $photoSessionId complete")
        running = false
        requestingPhoto = false

        completedList.add(photoSessionId)

        val session = PhotoSession().apply {
            sessionId = photoSessionId
            targetObject = targetObjectId
            stops = amountOfStops
            objectLocation = 0 // variable used in evaluation
            rotations = amountOfPhotosPerStop
            methodId = mode
            methodName = if (mode == 0) "crop" else "split"
            photosTaken = photos.toTypedArray()
        }

        photoLocationsTaken = 0
        photoNr = 0

        loadModel()

        completedRun = true

        photos = analyzer.analysePhotos(model as Yolov8Ncnn, photos, targetObjectId).toMutableList()

        val gson = Gson()
        val json = gson.toJson(session)

        val file = File(getOutputDirectory(this), "$photoSessionId-photo-data.json")
        file.writeText(json)

        Log.d(TAG, "Photo session data saved to: ${file.absolutePath}")

        for (photo in photos) {
            Log.d(
                TAG,
                "Photo: ${photo.fileName} - ${photo.detectedObjects.size} objects detected. Mode: ${photo.detectionMode}"
            )
            Log.d(TAG, "Detected objects: ${photo.detectedObjects}")
        }

        mapData()

        showResult()
    }

    // Add the overlays to the preview view when we use the focused crop method.
    private fun adjustOverlayForCrop() {
        val previewLayout =
            findViewById<RelativeLayout>(R.id.previewLayout)
        val previewView = findViewById<PreviewView>(R.id.previewView)

        // remove overlays if not in crop mode or not running anymore
        if (!running || mode != 0) {
            leftOverlay?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                leftOverlay = null
            }
            rightOverlay?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                rightOverlay = null
            }
            return
        }

        previewView.post {
            val previewWidth = previewView.width

            val cropWidth = (previewWidth * cropSize).toInt()
            val paddingHorizontal = (previewWidth - cropWidth) / 2

            // Create left overlay
            leftOverlay = View(this).apply {
                layoutParams = RelativeLayout.LayoutParams(
                    paddingHorizontal,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#80000000"))
            }

            // Create right overlay
            rightOverlay = View(this).apply {
                layoutParams = RelativeLayout.LayoutParams(
                    paddingHorizontal,
                    RelativeLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                }
                setBackgroundColor(Color.parseColor("#80000000"))
            }

            previewLayout.addView(leftOverlay)
            previewLayout.addView(rightOverlay)
        }
    }

    // Event called when Temi stopped moving.
    private fun onMovementStopped() {
        Log.d(TAG, "Movement stopped")

        if (requestingPhoto && !takingPhotos) {
            takingPhotos = true
            Log.d(TAG, "Robot has stopped moving. Taking $amountOfPhotosPerStop photos.")
            rotationsLeft = amountOfPhotosPerStop - 1

            takePhoto()
        }
    }

    // Function to check if the robot is standing still.
    private fun checkIfMovementHasStopped() {
        movementCheckJob?.cancel()
        movementCheckJob = lifecycleScope.launch {
            delay(3000) // Wait for 3 seconds
            if (running && System.currentTimeMillis() - lastPositionUpdateTime >= 3000) {
                onMovementStopped()
            }
        }
    }

    // Event called when the current position of the robot changes.
    override fun onCurrentPositionChanged(position: Position) {
        Log.d(TAG, "position: $position")
        lastPosition = position

        if (startPosition == null) {
            startPosition = position
        }

        mapData()

        // fix to dest
        if (running && !takingPhotos
            && (positionsAreSimilar(destination!!, lastPosition!!, 0.2f))
        ) {
            lastPositionUpdateTime = System.currentTimeMillis()
            checkIfMovementHasStopped()
        } else {
            movementCheckJob?.cancel()
        }
    }

    // Function to start a photo session.
    private fun startNextSession(photoMode: Int, stops: Int, amountOfPhotos: Int, objectClass: Int) {
        val path = robot.getMapData()?.greenPaths?.firstOrNull()
        if (path == null) {
            Log.e(TAG, "No path found")
            return
        }

        mode = photoMode
        amountOfPhotosPerStop = amountOfPhotos
        targetObjectId = objectClass
        amountOfStops = stops

        val paths = selectEvenlyFromList(path.layerPoses as List<LayerPose>, stops)
        Log.d(TAG, "Selected stops: $paths")

        if(mode == 0){
            if (amountOfPhotosPerStop > 4) {
                val baseWidth = 4000
                val divider = Math.ceil(amountOfPhotosPerStop.toDouble() / 3).toInt()
                val sectionSize = baseWidth / divider

                startWidth = (baseWidth / 2) - (sectionSize / 2)
                endWidth = (baseWidth / 2) + (sectionSize / 2)

                cropSize = (sectionSize.toDouble() / baseWidth.toDouble())

                Log.d(
                    TAG,
                    "Cropping photos with width: $sectionSize startWidth: $startWidth, endWidth: $endWidth. Crop size: $cropSize, amount of photos: $amountOfPhotosPerStop, amount of stops $stops, object class: $objectClass"
                )
            }
        } else {
            startWidth = 0
            endWidth = 4000

            cropSize = 1.0

            Log.d(TAG, "Using split photo method. amount of photos: $amountOfPhotosPerStop, amount of stops $stops, object class: $objectClass")
        }

        startPathSession(path.layerId, paths, stops, amountOfPhotosPerStop)
    }
}
