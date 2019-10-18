package com.trenser.mylibrary

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.trenser.mycamera.CameraView
import kotlinx.android.synthetic.main.activity_camera_view_eg.*

class CameraViewEgActivity : AppCompatActivity() {

    companion object {
        fun startIntent(context: Context):Intent{
            val intent = Intent(context,CameraViewEgActivity::class.java)
            return intent
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_view_eg)

        cameraView.cameraViewCallback = object : CameraView.ICameraViewCallback{
            override fun onCameraError(errorCode: Int) {
                if(errorCode==0){
                    ActivityCompat.requestPermissions(this@CameraViewEgActivity,
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        1001
                    )
                }
            }

            override fun onImageCaptured(uri: Uri) {

            }

        }
    }

    override fun onResume() {
        super.onResume()
        cameraView.onResume()
    }

    override fun onPause() {
        cameraView.onPause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(
                    this,
                    "Sorry!!!, you can't use this app without granting permission",
                    Toast.LENGTH_LONG
                ).show();
                finish();
            }
        }
    }
}
