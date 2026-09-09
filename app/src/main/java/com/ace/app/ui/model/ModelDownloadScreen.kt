package com.ace.app.ui.model

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ModelDownloadScreen(
    onSignOutClick: () -> Unit,
    viewModel: ModelDownloadViewModel = viewModel()
) {
    ModelSetupScreen(
        onModelReady = {},
        onSignOutClick = onSignOutClick,
        viewModel = viewModel
    )
}