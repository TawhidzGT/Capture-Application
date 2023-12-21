package com.example.captureapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var btnCapture: Button
    private lateinit var btnSelectImage: Button
    private lateinit var btnSend: Button
    private lateinit var progressBar: ProgressBar
    private var sendImageUri: Uri? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val imageUri: Uri? = result.data?.data
                imageUri?.let {
                    sendImageUri = it
                    Log.d("captureTest", "Captured Image URI: $it")
                } ?: kotlin.run {
                    val data: Intent? = result.data
                    if (data != null) {
                        val imageBitmap: Bitmap? = data.extras?.get("data") as? Bitmap

                        imageBitmap?.let {
                            val width = it.width
                            val height = it.height
                            Log.d("captureTest", "Captured Image Dimensions: $width x $height")
                            val imageUriConverted = getImageUriFromBitmap(it)
                            sendImageUri = imageUriConverted
                            Log.d("captureTest", "Captured Image URI: $imageUriConverted")
                        }
                    } else {
                        sendImageUri = null
                        Log.d("captureTest", "No data in the result Intent.")
                    }
                }
            } else {
                sendImageUri = null
                Log.d("captureTest", "Capture operation canceled or failed.")
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                if (data != null) {
                    val selectedImageUri: Uri? = data.data
                    selectedImageUri?.let {
                        sendImageUri = it
                        Log.d("captureTest", "Selected Image URI: $it")
                    }
                } else {
                    sendImageUri = null
                    Log.d("captureTest", "No data in the result Intent.")
                }
            } else {
                sendImageUri = null
                Log.d("captureTest", "Image selection operation canceled or failed.")
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        btnCapture = findViewById(R.id.camera_capture)
        btnSend = findViewById(R.id.generator)
        btnSelectImage = findViewById(R.id.select_from_storage)
        progressBar = findViewById(R.id.progressBar)

        btnCapture.setOnClickListener {
            dispatchTakePictureIntent()
        }
        btnSelectImage.setOnClickListener {
            openGallery()
        }
        btnSend.setOnClickListener {
            sendImageToBackend(sendImageUri)
        }
    }

    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (takePictureIntent.resolveActivity(packageManager) != null) {
            takePictureLauncher.launch(takePictureIntent)
        }
    }

    private fun getImageUriFromBitmap(bitmap: Bitmap): Uri {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
        val path = MediaStore.Images.Media.insertImage(contentResolver, bitmap, "Title", null)
        return Uri.parse(path)
    }

    private fun openGallery() {
        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(galleryIntent)
    }

    private fun sendImageToBackend(imageUri: Uri?) {
        if (imageUri != null) {
            val file = File(imageUri.path!!)

            val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
            val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

            val description = "image-upload".toRequestBody("text/plain".toMediaTypeOrNull())

            val retrofit = Retrofit.Builder()
                .baseUrl("https://sampleapi.com/endWithSlash/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            val apiService = retrofit.create(ApiService::class.java)

            val call = apiService.uploadImage(description, body)

            call.enqueue(object : Callback<ResponseBody> {
                override fun onResponse(
                    call: Call<ResponseBody>,
                    response: Response<ResponseBody>
                ) {
                    if (response.isSuccessful) {
                        val responseBodyString = response.body()?.string()?.trimIndent()
                        val responseJsonElement = JsonParser.parseString(responseBodyString)
                        val resultJsonString = generateJsonString(responseJsonElement)
                        Log.d("captureTest", "Image upload successful $resultJsonString")
                    } else {
                        Log.d("captureTest", "Image upload failed")
                    }

                    progressBar.visibility = ProgressBar.GONE
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.d("captureTest", "API call failed: ${t.message}")
                    progressBar.visibility = ProgressBar.GONE
                }
            })

            progressBar.visibility = ProgressBar.VISIBLE
            sendImageUri = null
        }
    }

    fun generateJsonString(jsonElement: JsonElement, indent: String = ""): String {
        val result = StringBuilder()

        when {
            jsonElement.isJsonObject -> {
                result.append("$indent{\n")
                for ((key, value) in jsonElement.asJsonObject.entrySet()) {
                    result.append("$indent  \"$key\": ")
                    result.append(generateJsonString(value, "$indent  "))
                    result.append("\n")
                }
                result.append("$indent}")
            }

            jsonElement.isJsonArray -> {
                result.append("$indent[\n")
                for (item in jsonElement.asJsonArray) {
                    result.append(generateJsonString(item, "$indent  "))
                }
                result.append("$indent]")
            }

            jsonElement.isJsonPrimitive -> {
                val primitive = jsonElement.asJsonPrimitive
                if (primitive.isString) {
                    result.append("\"${primitive.asString}\"")
                } else {
                    result.append(primitive)
                }
            }

            jsonElement.isJsonNull -> {
                result.append("null")
            }
        }

        return result.toString()
    }

}