package com.example.captureapplication

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var btnCapture: Button
    private lateinit var btnSelectImage: Button
    private lateinit var btnSend: Button
    private lateinit var progressBar: ProgressBar
    private var sendImageUri: Uri? = null
    private lateinit var captionResult: TextView

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
        captionResult = findViewById(R.id.caption_result)

        btnCapture.setOnClickListener {
            captionResult.text = ""
            dispatchTakePictureIntent()
        }
        btnSelectImage.setOnClickListener {
            captionResult.text = ""
            openGallery()
        }
        btnSend.setOnClickListener {
            captionResult.text = ""
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

    object FileUtil {
        fun uriToFile(uri: Uri, context: Context): File {
            val contentResolver: ContentResolver = context.contentResolver
            val file = createTempFile(context)
            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return file
        }

        private fun createTempFile(context: Context): File {
            val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            return File.createTempFile(
                "temp_image",
                ".jpg",
                storageDir
            )
        }
    }


    private fun sendImageToBackend(imageUri: Uri?) {
        if (imageUri != null) {
            val file = File(imageUri.path!!)

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(130, TimeUnit.SECONDS) // Set the connection timeout
                .readTimeout(130, TimeUnit.SECONDS)    // Set the read timeout
                .writeTimeout(130, TimeUnit.SECONDS)   // Set the write timeout
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl("http://209.97.173.247/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(okHttpClient)
                .build()

            val apiService = retrofit.create(ApiService::class.java)

            val fileUri: Uri = imageUri
            val fileNew = FileUtil.uriToFile(fileUri, applicationContext)

            val requestFileNew = fileNew.asRequestBody("multipart/form-data".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestFileNew)

            val call = apiService.uploadImage(filePart)

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
                        captionResult.text = resultJsonString
                    } else {
                        Log.d("captureTest", "Image upload failed")
                        captionResult.text = response.message().toString()
                    }

                    progressBar.visibility = ProgressBar.GONE
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Log.d("captureTest", "API call failed: ${t.message}")
                    progressBar.visibility = ProgressBar.GONE
                    captionResult.text = t.message
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