package com.trenser.mycamera

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_camera.*

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"


class CameraFragment : Fragment() {
    private var param1: String? = null
    private var param2: String? = null
    private var callback: CameraView.ICameraViewCallback? = null

    companion object {
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            CameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnCapture.setOnClickListener{
            cameraView.takePicture()
        }
        btnSwitchCamera.setOnClickListener {
            cameraView.switchCamera()
        }
        btnFlash.setOnClickListener {
            val mode = cameraView.flashMode
            val newMode = when(mode){
                CameraView.FlashMode.AUTO -> CameraView.FlashMode.ON
                CameraView.FlashMode.ON -> CameraView.FlashMode.OFF
                CameraView.FlashMode.OFF -> CameraView.FlashMode.AUTO
            }
            if(cameraView.setFlashMode(newMode)){
                when(newMode){
                    CameraView.FlashMode.AUTO -> btnFlash.setImageResource(R.drawable.ic_flash_auto)
                    CameraView.FlashMode.OFF -> btnFlash.setImageResource(R.drawable.ic_flash_off)
                    CameraView.FlashMode.ON -> btnFlash.setImageResource(R.drawable.ic_flash_on)
                }
            }
        }

    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is CameraView.ICameraViewCallback) {
            callback = context
        } else {
            throw RuntimeException(context.toString() + " must implement CameraView.ICameraViewCallback")
        }
    }

    override fun onDetach() {
        super.onDetach()
        callback = null
    }

    override fun onResume() {
        super.onResume()
        cameraView.cameraDir="Test1"
        cameraView.imageRatio = 16f/9f
        cameraView?.onResume()
    }

    override fun onPause() {
        cameraView?.onPause()
        super.onPause()
    }

}
