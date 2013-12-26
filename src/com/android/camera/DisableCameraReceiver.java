/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.File;

// We want to disable camera-related activities if there is no camera. This
// receiver runs when BOOT_COMPLETED intent is received. After running once
// this receiver will be disabled, so it will not run again.
public class DisableCameraReceiver extends BroadcastReceiver {
    private static final String TAG = "DisableCameraReceiver";
    private static final boolean DEBUG = false;
    private static final boolean CHECK_BACK_CAMERA_ONLY = false;
    private static final String ACTIVITIES[] = {
        "com.android.camera.CameraLauncher",
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if(DEBUG)
            Log.i(TAG, "action:" + action);
        
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)){
            // Disable camera-related activities if there is no camera.
            boolean needCameraActivity = CHECK_BACK_CAMERA_ONLY
                ? hasBackCamera()
                : hasCamera();

            if (!needCameraActivity) {
                Log.i(TAG, "disable all camera activities");
                for (int i = 0; i < ACTIVITIES.length; i++) {
                    disableComponent(context, ACTIVITIES[i]);
                }
            }
            else{
                Log.i(TAG, "enable all camera activities");
                for (int i = 0; i < ACTIVITIES.length; i++) {
                    enableComponent(context, ACTIVITIES[i]);
                }
            }

            // Disable this receiver so it won't run again.
            //disableComponent(context, "com.android.camera.DisableCameraReceiver");
        }
        else if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)){
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if(isUsbCamera(device)){
                new VideoDevThread(context, true).start();
            }
        }
        else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
            UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if(isUsbCamera(device)){
                new VideoDevThread(context, false).start();
            }
        }
    }

    private boolean hasCamera() {
        int n = android.hardware.Camera.getNumberOfCameras();
        Log.i(TAG, "number of camera: " + n);
        return (n > 0);
    }

    private boolean hasBackCamera() {
        int n = android.hardware.Camera.getNumberOfCameras();
        CameraInfo info = new CameraInfo();
        for (int i = 0; i < n; i++) {
            android.hardware.Camera.getCameraInfo(i, info);
            if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
                Log.i(TAG, "back camera found: " + i);
                return true;
            }
        }
        Log.i(TAG, "no back camera");
        return false;
    }

    private void disableComponent(Context context, String klass) {
        ComponentName name = new ComponentName(context, klass);
        PackageManager pm = context.getPackageManager();

        // We need the DONT_KILL_APP flag, otherwise we will be killed
        // immediately because we are in the same app.
        pm.setComponentEnabledSetting(name,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }
    
    private void enableComponent(Context context, String klass) {
        ComponentName name = new ComponentName(context, klass);
        PackageManager pm = context.getPackageManager();

        pm.setComponentEnabledSetting(name,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP);
    }
    
    public boolean isUsbCamera(UsbDevice device) {
        int count = device.getInterfaceCount();

        if(DEBUG){
            for (int i = 0; i < count; i++) {
                UsbInterface intf = device.getInterface(i);
                Log.i(TAG, "isCamera UsbInterface:" + intf);
            }
        }
        
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            /*
            if (intf.getInterfaceClass() == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.getInterfaceSubclass() == 1 &&
                    intf.getInterfaceProtocol() == 1) {
                return true;
            }*/

            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }

    class VideoDevThread extends Thread {
        private static final String DEV_VIDEO_prefix = "/dev/video";
        private static final int DEV_NUM = 5;
        Context mContext;
        int mCamNum = 0;
        boolean mIsAttach;
        
        public VideoDevThread(Context context, boolean isAttach) {
            mContext = context;
            mIsAttach = isAttach;
            mCamNum = android.hardware.Camera.getNumberOfCameras();

            Log.i(TAG, "VideoDevThread isAttach:" + isAttach + ", cur camera num:" + mCamNum);
        }

        @Override
        public void run() {
            boolean end = false;
            int loopCount = 0;
            while( !end ){
                try{
                    Thread.sleep(500);//first delay 500ms, in order to wait kernel set up video device path
                }
                catch (InterruptedException e){
                    Thread.currentThread().interrupt();
                }
                
                int devNum = 0;
                for( int i = 0; i < DEV_NUM; i++ ){
                    String path = DEV_VIDEO_prefix + i;                    
                	if(new File(path).exists()){
                		devNum++;
                	}
                }

                Log.i(TAG, "video device num:" + devNum);
                if(mIsAttach && (devNum > mCamNum)){//device path has been set up by kernel
                    Camera.usbCameraAttach(mIsAttach);
                    for (int i = 0; i < ACTIVITIES.length; i++) {
                        enableComponent(mContext, ACTIVITIES[i]);
                    }
                    end = true;
                }
                else if(!mIsAttach && (devNum < mCamNum)){//device path has been deleted by kernel
                    Camera.usbCameraAttach(mIsAttach);
                    for (int i = 0; i < ACTIVITIES.length; i++) {
                        disableComponent(mContext, ACTIVITIES[i]);
                    }
                    end = true;
                }
                else if((mCamNum > 0) && (mCamNum == devNum)){//video device was plugged in when boot 
                    loopCount++;
                    if(loopCount > 2){//1s kernel has set up or delete the device path
                        end = true;
                    }
                }
            }
        }
    }
}
