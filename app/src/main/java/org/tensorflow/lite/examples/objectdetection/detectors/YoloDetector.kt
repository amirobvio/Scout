package org.tensorflow.lite.examples.objectdetection.detectors

import android.content.Context
import android.graphics.RectF
import com.ultralytics.yolo.ImageProcessing
import com.ultralytics.yolo.models.LocalYoloModel
import com.ultralytics.yolo.predict.detect.DetectedObject
import com.ultralytics.yolo.predict.detect.TfliteDetector
import org.tensorflow.lite.support.image.TensorImage
import org.yaml.snakeyaml.Yaml
import java.io.InputStream


class YoloDetector(
    val context: Context
): ObjectDetector {
    
    // These will be loaded from YAML config
    private var confidenceThreshold: Float = 0.5f
    private var iouThreshold: Float = 0.3f
    private var numThreads: Int = 2
    private var maxResults: Int = 3
    private var currentDelegate: Int = 0
    private var currentModel: Int = 0

    private lateinit var yolo: TfliteDetector
    private var ip: ImageProcessing

    init {
        // Load configuration from YAML
        val metadataPath = "metadata.yaml"
        val configData = loadConfigFromYaml(metadataPath)
        
        // Override parameters with YAML config values
        configData?.let {
            val modelConfig = it["model"] as? Map<String, Any>
            val detectionConfig = it["detection"] as? Map<String, Any>
            val hardwareConfig = it["hardware"] as? Map<String, Any>
            
            // Get model path from config
            val modelPath = modelConfig?.get("path") as? String ?: "yolo11n_float32.tflite"
            
            // Update detection parameters from config
            detectionConfig?.let { detection ->
                confidenceThreshold = (detection["confidence_threshold"] as? Double)?.toFloat() ?: confidenceThreshold
                iouThreshold = (detection["iou_threshold"] as? Double)?.toFloat() ?: iouThreshold
                maxResults = (detection["max_results"] as? Int) ?: maxResults
            }
            
            // Update hardware parameters from config
            hardwareConfig?.let { hardware ->
                numThreads = (hardware["num_threads"] as? Int) ?: numThreads
                val delegateStr = hardware["delegate"] as? String
                currentDelegate = when(delegateStr) {
                    "gpu" -> 1
                    "nnapi" -> 2
                    else -> 0 // cpu
                }
            }
            
            yolo = TfliteDetector(context)
            yolo.setIouThreshold(iouThreshold)
            yolo.setConfidenceThreshold(confidenceThreshold)

            val config = LocalYoloModel(
                "detect",
                "tflite",
                modelPath,
                metadataPath,
            )

            val useGPU = currentDelegate == 1
            yolo.loadModel(
                config,
                useGPU
            )
        }

        ip = ImageProcessing()
    }
    
    private fun loadConfigFromYaml(metadataPath: String): Map<String, Any>? {
        return try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open(metadataPath)
            val yaml = Yaml()
            val data = yaml.load<Map<String, Any>>(inputStream)
            inputStream.close()
            data
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun detect(image: TensorImage, imageRotation: Int): DetectionResult  {

        val bitmap = image.bitmap

        val ppImage = yolo.preprocess(bitmap)
        val results = yolo.predict(ppImage)

        val detections = ArrayList<DetectionObject>()

        // ASPECT_RATIO = 4:3
        // => imgW = imgH * 3/4
        var imgH: Int
        var imgW: Int
        if (imageRotation == 90 || imageRotation == 270) {
            imgH = ppImage.height
            imgW = imgH * 3 / 4
        }
        else {
            imgW = ppImage.width
            imgH = imgW * 3 / 4

        }


        for (result: DetectedObject in results) {
            val category = Category(
                result.label,
                result.confidence,
            )
            val yoloBox = result.boundingBox

            val left = yoloBox.left * imgW
            val top = yoloBox.top * imgH
            val right = yoloBox.right * imgW
            val bottom = yoloBox.bottom * imgH

            val bbox = RectF(
                left,
                top,
                right,
                bottom
            )
            val detection = DetectionObject(
                bbox,
                category
            )
            detections.add(detection)
        }

        val ret = DetectionResult(ppImage, detections)
        ret.info = yolo.stats
        return ret

    }


}