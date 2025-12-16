package com.example.eeglabeler

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class PermissionGate(private val activity: ComponentActivity) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* you can log results later */ }

    fun requestBlePermissions() {
		val perms = if (Build.VERSION.SDK_INT >= 31) {
			arrayOf(
				Manifest.permission.BLUETOOTH_SCAN,
				Manifest.permission.BLUETOOTH_CONNECT,
				Manifest.permission.ACCESS_FINE_LOCATION
			)
		} else {
			arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
		}
        launcher.launch(perms)
    }
}
