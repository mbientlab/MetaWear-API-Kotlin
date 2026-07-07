package com.mbientlab.metawear.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mbientlab.metawear.app.AppContainer
import com.mbientlab.metawear.app.MetaWearApplication

/** Reach the process-wide [AppContainer] from any composable. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as MetaWearApplication).container

/**
 * ViewModel factory bridge: builds VMs that take the [AppContainer] as their
 * only constructor argument, keyed per navigation entry by type as usual.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(
        factory = viewModelFactory {
            initializer { create(container) }
        },
    )
}
