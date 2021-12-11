/*
 * Copyright (C) 2020 Wacom.
 * Use of this source code is governed by the MIT License that can be found in the LICENSE file.
 */
package com.wacom.will3.ink.vector.rendering.demo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wacom.ink.format.InkModel
import com.wacom.ink.format.input.SensorChannel
import com.wacom.ink.format.serialization.Will3Codec
import com.wacom.ink.format.tree.data.Stroke
import com.wacom.ink.format.tree.groups.StrokeGroupNode
import com.wacom.ink.format.tree.nodes.StrokeNode
import com.wacom.ink.manipulation.SpatialModel
import com.wacom.ink.model.StrokeAttributes
import com.wacom.ink.rendering.VectorBrush
import com.wacom.will3.ink.vector.rendering.demo.databinding.ActivityMainBinding
import com.wacom.will3.ink.vector.rendering.demo.serialization.InkEnvironmentModel
import com.wacom.will3.ink.vector.rendering.demo.tools.Tool
import com.wacom.will3.ink.vector.rendering.demo.tools.vector.*
import com.wacom.will3.ink.vector.rendering.demo.vector.WillStroke
import com.wacom.will3.ink.vector.rendering.demo.vector.WillStrokeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import top.defaults.colorpicker.ColorPickerPopup
import top.defaults.colorpicker.ColorPickerPopup.ColorPickerObserver
import java.io.FileInputStream
import java.io.FileOutputStream


class MainActivity : AppCompatActivity() {

    val OPEN_FILE_ACTION = 1
    val CREATE_FILE_ACTION = 2

    //-- Variables For serialisation

    private var loadedInkModel: InkModel? = null

    // Environment information to save in the ink model
    private lateinit var inkEnvironmentModel: InkEnvironmentModel

    //-- End serialisation

    // This is used for inking vectors.
    // Note: This is only necessary when we want to removing on vector inking
    private lateinit var spatialModel: SpatialModel

    private var drawingTool: Tool? = null

    private var popupWindow: PopupWindow? = null

    private var drawingColor: Int = Color.argb(255, 74, 74, 74)

    private var currentBackground: Int = 0

    private val scope = CoroutineScope(newSingleThreadContext("loadingPool"))

    val anim: Animation = AlphaAnimation(0.0f, 1.0f).also {
        it.setDuration(1000)
        it.setStartOffset(20)
        it.setRepeatMode(Animation.REVERSE)
        it.setRepeatCount(Animation.INFINITE)
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        inkEnvironmentModel =
            InkEnvironmentModel(this) // Initializes the environment data for serialization

        spatialModel = SpatialModel(WillStrokeFactory()) // Initializes the spatial model
        binding.vectorDrawingView.setSpatialModel(spatialModel)
        binding.vectorDrawingView.activity = this

        setColor(drawingColor) //set default color

        binding.deleteBackgroundWaiting.setOnTouchListener { _, event ->
            true
        }

        binding.vectorDrawingView.setOnTouchListener { _, event ->
            binding.vectorDrawingView.onTouch(event)
            true
        }

        binding.vectorDrawingView.inkEnvironmentModel = inkEnvironmentModel

        setTool(binding.btnPen, PenTool()) // Set default tool
        selectPaper(currentBackground)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Make sure the request was successful
        if (resultCode == Activity.RESULT_OK) {
            // Check which request we're responding to
            if (requestCode == OPEN_FILE_ACTION) {
                scope.launch {
                    load(data!!.data!!)
                }
            } else if (requestCode == CREATE_FILE_ACTION) {
                save(data!!.data!!)
            }
        }
    }

    fun selectColor(view: View) {
        ColorPickerPopup.Builder(this)
            .initialColor(drawingColor) // Set initial color
            .enableBrightness(true) // Enable brightness slider or not
            .enableAlpha(false) // Enable alpha slider or not
            .okTitle("Choose")
            .cancelTitle("Cancel")
            .showIndicator(false)
            .showValue(false)
            .build()
            .show(view, object : ColorPickerObserver() {
                override fun onColorPicked(color: Int) {
                    this@MainActivity.setColor(color)
                }

                fun onColor(color: Int, fromUser: Boolean) {}
            })
    }

    fun setColor(color: Int) {
        drawingColor = color
        binding.btnColor.setColorFilter(drawingColor, PorterDuff.Mode.SRC_ATOP)
        binding.vectorDrawingView.setColor(drawingColor)
    }

    fun selectTool(view: View) {
        when (view.id) {
            R.id.btn_pen -> setTool(view, PenTool())
            R.id.btn_felt -> setTool(view, FeltTool())
            R.id.btn_brush -> setTool(view, BrushTool())
            R.id.btn_marker -> setTool(view, MarkerTool())
            R.id.btn_eraser_partial_stroke -> setTool(view, EraserVectorTool())
            R.id.btn_eraser_whole_stroke -> setTool(view, EraserWholeStrokeTool())
            R.id.btn_selector -> setTool(view, SelectorPartialStrokeTool())
            R.id.btn_selector_whole_stroke -> setTool(view, SelectorWholeStrokeTool())
        }
    }

    fun setTool(view: View, tool: Tool) {
        drawingTool = tool
        binding.vectorDrawingView.setTool(drawingTool as VectorTool)
        highlightTool(view)
    }

    fun highlightTool(view: View) {
        binding.btnPen.setActivated(false)
        binding.btnFelt.setActivated(false)
        binding.btnBrush.setActivated(false)
        binding.btnMarker.setActivated(false)
        binding.btnEraserPartialStroke.setActivated(false)
        binding.btnEraserWholeStroke.setActivated(false)
        binding.btnSelector.setActivated(false)
        binding.btnSelectorWholeStroke.setActivated(false)
        binding.root.isActivated = true
    }

    fun load(view: View) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_FILE_ACTION)
    }

    fun load(path: Uri) {
        this@MainActivity.runOnUiThread(java.lang.Runnable {
            openBackground(getString(R.string.loading), true)
            // Before loading we clear the screen
            clear(null)
        })

        var zOrder = 0

        try {
            getContentResolver().openFileDescriptor(path, "r").use { pfd ->
                FileInputStream(pfd?.fileDescriptor).use { fileInputStream ->

                    val bytes = fileInputStream.readBytes()
                    loadedInkModel = Will3Codec.decode(bytes)

                    val it = loadedInkModel?.inkTree!!.root!!.iterator()
                    while (it.hasNext()) {
                        val stroke = it.next()
                        if (stroke is StrokeNode) {
                            val brushUri = stroke.data.style?.brushURI
                            if (brushUri != null) {
                                val brush = loadedInkModel?.brushRepository?.getBrush(brushUri)
                                if (brush is VectorBrush) {
                                    val spline = stroke.data.spline

                                    val willStroke = WillStroke(spline, brush, zOrder++)
                                    willStroke.strokeNode = stroke

                                    if (stroke.data.sensorDataID != null) {
                                        willStroke.sensorData =
                                            loadedInkModel?.sensorDataRepository?.get(stroke.data.sensorDataID!!)
                                    }

                                    willStroke.strokeAttributes = object : StrokeAttributes {
                                        override var size: Float =
                                            stroke.data.style?.props?.size ?: 10f
                                        override var rotation: Float =
                                            stroke.data.style?.props?.rotation ?: 0.0f

                                        override var scaleX: Float =
                                            stroke.data.style?.props?.scaleX ?: 1.0f
                                        override var scaleY: Float =
                                            stroke.data.style?.props?.scaleY ?: 1.0f
                                        override var scaleZ: Float =
                                            stroke.data.style?.props?.scaleZ ?: 1.0f

                                        override var offsetX: Float =
                                            stroke.data.style?.props?.offsetX ?: 0.0f
                                        override var offsetY: Float =
                                            stroke.data.style?.props?.offsetY ?: 0.0f
                                        override var offsetZ: Float =
                                            stroke.data.style?.props?.offsetZ ?: 0.0f

                                        override var red: Float =
                                            stroke.data.style?.props?.red ?: 0f
                                        override var green: Float =
                                            stroke.data.style?.props?.green ?: 0f
                                        override var blue: Float =
                                            stroke.data.style?.props?.blue ?: 0f
                                        override var alpha: Float =
                                            stroke.data.style?.props?.alpha ?: 1f
                                    }

                                    synchronized(spatialModel) {
                                        var sensorChannelList: List<SensorChannel>? = null
                                        val inputContextId = willStroke.sensorData?.inputContextId
                                        if (inputContextId != null) {
                                            val sensorContextId =
                                                loadedInkModel?.inputConfiguration?.getInputContext(
                                                    inputContextId
                                                )
                                                    ?.sensorContextId
                                            if (sensorContextId != null) {
                                                sensorChannelList =
                                                    loadedInkModel?.inputConfiguration?.getSensorContext(
                                                        sensorContextId
                                                    )?.getSensorChannelsContexts()?.first()
                                                        ?.getAll()
                                            }
                                        }

                                        binding.vectorDrawingView.buildStroke(willStroke, sensorChannelList)
                                        spatialModel.add(willStroke)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

       binding.vectorDrawingView.setZOrder(zOrder)
       binding.vectorDrawingView.createBmps()
       binding.vectorDrawingView.updateDrawing()

        this@MainActivity.runOnUiThread(java.lang.Runnable {
            Toast.makeText(this, "Loading completed.", Toast.LENGTH_SHORT).show()
            closeBackground()
        })
    }

    fun save(uri: Uri) {
        Toast.makeText(this, "Saving..", Toast.LENGTH_SHORT).show()

        val inkModel = InkModel()
        val root = StrokeGroupNode(com.wacom.ink.model.Identifier())
        inkModel.inkTree.root = root
        val mainGroup = StrokeGroupNode(com.wacom.ink.model.Identifier())
        root.add(mainGroup)

        inkEnvironmentModel.registerInModel(inkModel)
        inkModel.knowledgeGraph.add(
            inkModel.inkTree!!.root!!.id.toUUIDString(),
            "created",
            "" + System.currentTimeMillis()
        )
        inkModel.knowledgeGraph.add(inkModel.inkTree!!.root!!.id.toUUIDString(), "author", "WILL 3")

        if (loadedInkModel != null) {
            loadedInkModel?.inputConfiguration?.getAllEnvironments()?.forEach{ (id, environment) ->
                if (inkModel.inputConfiguration.getEnvironment(environment.id) == null) {
                    inkModel.inputConfiguration.add(environment)
                }
            }
            loadedInkModel?.inputConfiguration?.getAllInkInputProviders()?.forEach{ (id, inputProvider) ->
                if (inkModel.inputConfiguration.getInputProvider(inputProvider.id) == null) {
                    inkModel.inputConfiguration.add(inputProvider)
                }
            }
            loadedInkModel?.inputConfiguration?.getAllInputContexts()?.forEach{ (id, inputContext) ->
                if (inkModel.inputConfiguration.getInputContext(inputContext.id) == null) {
                    inkModel.inputConfiguration.add(inputContext)
                }
            }
            loadedInkModel?.inputConfiguration?.getAllInputDevices()?.forEach{ (id, inputDevices) ->
                if (inkModel.inputConfiguration.getInputDevice(inputDevices.id) == null) {
                    inkModel.inputConfiguration.add(inputDevices)
                }
            }
            loadedInkModel?.inputConfiguration?.getAllSensorContexts()?.forEach{ (id, sensorContext) ->
                if (inkModel.inputConfiguration.getSensorContext(sensorContext.id) == null) {
                    inkModel.inputConfiguration.add(sensorContext)
                }
            }
        }

        // For vector serialization
        val sortedStrokes = spatialModel.getStrokes().values.toList().sortedBy { (it as WillStroke).zOrder }

        for (stroke in sortedStrokes) {
            try {
                stroke as WillStroke
                if (stroke.sensorData != null) {
                    if (!inkModel.sensorDataRepository.contains(stroke?.sensorData?.id!!)) {
                        inkModel.sensorDataRepository.add(stroke.sensorData!!)
                    }
                }

                if (inkModel.brushRepository.getBrush(stroke.vectorBrush.name) == null) {
                    inkModel.brushRepository.addVectorBrush(stroke.vectorBrush)
                }

                if (stroke.strokeNode == null) {
                    stroke.strokeNode = StrokeNode(
                        Stroke(
                            stroke.id,
                            stroke.spline,
                            stroke.createStyle(),
                            stroke.sensorData?.id,
                            stroke.sensorDataOffset
                        )
                    )
                }

                if (!mainGroup.contains(stroke.strokeNode!!)) {
                    mainGroup.add(stroke.strokeNode!!)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            getContentResolver().openFileDescriptor(uri, "w").use { pfd ->
                FileOutputStream(pfd?.fileDescriptor).use { fileOutputStream ->
                    Will3Codec.encode(inkModel, fileOutputStream)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clear(view: View?) {
        spatialModel = SpatialModel(WillStrokeFactory()) // Initializes the spatial model
        binding.vectorDrawingView.setSpatialModel(spatialModel)
        binding.vectorDrawingView.clear()
    }

    fun undo(view: View?) {
        binding.vectorDrawingView.undo()
    }

    fun redo(view: View?) {
        binding.vectorDrawingView.redo()
    }

    fun openPaperDialog(view: View) {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView: View = inflater.inflate(R.layout.background_selector, null)

        // create the popup window
        val width = LinearLayout.LayoutParams.WRAP_CONTENT
        val height = LinearLayout.LayoutParams.WRAP_CONTENT
        val focusable = true // lets taps outside the popup also dismiss it
        popupWindow = PopupWindow(popupView, width, height, focusable)

        // show the popup window
        // which view you pass in doesn't matter, it is only used for the window tolken
        val screenPos = IntArray(2)
        view.getLocationOnScreen(screenPos)
        popupWindow!!.showAtLocation(
            view,
            Gravity.NO_GRAVITY,
            screenPos[0],
            screenPos[1] + binding.navbarContainer.height
        )
    }

    fun selectPaper(pos: Int) {
        if (pos == 0) {
            changeBackground(R.drawable.btn_paper_01, R.drawable.background1)
        } else if (pos == 1) {
            changeBackground(R.drawable.btn_paper_02, R.drawable.background2)
        } else {
            changeBackground(R.drawable.btn_paper_03, R.drawable.btn_paper_03)
        }
    }

    fun selectPaper(view: View) {
        when (view.id) {
            R.id.btnBackground1 -> {
                changeBackground(R.drawable.btn_paper_01, R.drawable.background1)
                currentBackground = 0
            }
            R.id.btnBackground2 -> {
                changeBackground(R.drawable.btn_paper_02, R.drawable.background2)
                currentBackground = 1
            }
            R.id.btnBackground3 -> {
                changeBackground(R.drawable.btn_paper_03, R.drawable.btn_paper_03)
                currentBackground = 2
            }
        }
    }

    fun changeBackground(background: Int, paper: Int) {
        binding.btnBackground.setImageResource(background)
        binding.vectorDrawingView.setBackgroundResource(paper)
        if (popupWindow != null) {
            popupWindow!!.dismiss()
        }
    }

    fun selectSaveFile(view: View) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.setType("*/*")
        intent.putExtra(Intent.EXTRA_TITLE, "ink.uim");
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, CREATE_FILE_ACTION)
    }

    fun openBackground(message: String, blocking: Boolean){
        binding.backgroundWaiting.setOnTouchListener { _, event ->
            blocking
        }
        binding.backgroundWaitingMessage.setText(message)
        binding.backgroundWaiting.visibility = View.VISIBLE
    }

    fun closeBackground() {
        binding.backgroundWaiting.visibility = View.GONE
    }

    fun openDeletingMessage() {
        binding.deletingMsg.startAnimation(anim)
        binding.deleteBackgroundWaiting.visibility = View.VISIBLE
    }

    fun closeDeletingMessage() {
        anim.cancel()
        binding.deletingMsg.animation = null
        binding.deleteBackgroundWaiting.visibility = View.GONE
    }

}
