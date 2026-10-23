package com.localshare.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localshare.tv.ui.TvTheme

class MainActivity : ComponentActivity() {
    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: TvViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    val api = vm.api
    BackHandler(enabled = st.video != null || st.photos != null || st.phone != null || st.relay || st.asking != null) { vm.back() }
    val v = st.video
    val ph = st.photos
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            st.video != null -> PlayerScreen(api, st.video!!, vm.store) { vm.back() }
            st.photos != null -> PhotoScreen(api, st.photos!!)
            st.relay || (api != null && st.phone != null) -> BrowseScreen(st, api, vm)
            else -> HomeScreen(st, vm)
        }
    }
}
