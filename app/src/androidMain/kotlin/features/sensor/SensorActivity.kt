package com.juul.sensortag.features.sensor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.juul.exercise.annotations.Exercise
import com.juul.exercise.annotations.Extra

@Exercise(Extra("macAddress", String::class))
class SensorActivity : ComponentActivity() {

    private val viewModel by viewModels<AdafruitViewModel> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(
                modelClass: Class<T>
            ): T = AdafruitViewModel(application, this@SensorActivity.extras.macAddress) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewState = viewModel.viewState.collectAsState(ViewState.Disconnected).value
            val estimatedBpm = viewModel.estimatedBpm.collectAsState().value
            val ppgSignal = viewModel.ppgSignal.collectAsState().value

            val connectionStatus = viewState.label
            val batteryLevel = if (viewState is ViewState.Connected) viewState.batteryPercentage else 0
            val currentBpm = estimatedBpm?.toInt() ?: 0

            DashboardScreen(
                currentBpm = currentBpm,
                connectionStatus = connectionStatus,
                batteryLevel = batteryLevel,
                ppgSignalData = ppgSignal
            )
        }
    }
}
