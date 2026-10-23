package com.localshare.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localshare.tv.ui.FocusCard
import com.localshare.tv.ui.QrCode

@Composable
fun HomeScreen(st: UiState, actions: TvActions) {
    val first = remember { FocusRequester() }
    var manual by remember { mutableStateOf(false) }
    val hasList = st.devices.isNotEmpty() || st.phones.isNotEmpty() || st.nearby.isNotEmpty()
    LaunchedEffect(hasList) { if (hasList) runCatching { first.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("LocalShare TV", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("Watch videos, music and photos from any phone on this screen. The phone needs no app, and nothing is saved on the TV.", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (st.pairUrl.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(st.pairUrl, 200.dp, "QR code. Scan it with your phone camera to send files to this TV.")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Step("1", "Open your phone camera")
                        Step("2", "Scan this code and tap the link")
                        Step("3", "Pick files. They play here")
                    }
                }
            } else Text(st.message ?: "Getting ready…", fontSize = 20.sp, color = MaterialTheme.colorScheme.error)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.connecting) Text("Connecting…", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
            st.message?.takeIf { st.pairUrl.isNotEmpty() }?.let {
                FocusCard(actions::dismissMessage, Modifier.fillMaxWidth()) { Text("$it   (press OK to dismiss)", Modifier.padding(18.dp), fontSize = 18.sp, color = MaterialTheme.colorScheme.error) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(st.devices, key = { it.sid }) { d ->
                    val count = d.batches.sumOf { it.files.size }
                    val mod = if (d == st.devices.first()) Modifier.focusRequester(first) else Modifier
                    FocusCard(actions::openShared, mod.fillMaxWidth()) { PhoneRow(d.name, "$count ${if (count == 1) "file" else "files"} shared", "Open") }
                }
                item {
                    FocusCard({ manual = true }, Modifier.fillMaxWidth()) { PhoneRow("Using the LocalShare app?", "Connect by address instead", "Type") }
                }
                if (st.phones.isNotEmpty()) item { Header("Your LocalShare phones") }
                items(st.phones, key = { it.url }) { p ->
                    val mod = if (st.devices.isEmpty() && p == st.phones.first()) Modifier.focusRequester(first) else Modifier
                    FocusCard({ actions.connect(p) }, mod.fillMaxWidth()) { PhoneRow(p.name, p.url, "Open") }
                }
                val fresh = st.nearby.filter { n -> st.phones.none { it.url == n.url } }
                item { Header(if (fresh.isEmpty() && st.phones.isEmpty()) "Looking for phones on this Wi-Fi…" else if (fresh.isEmpty()) "" else "Nearby phones") }
                items(fresh, key = { it.url }) { n ->
                    val mod = if (st.phones.isEmpty() && n == fresh.first()) Modifier.focusRequester(first) else Modifier
                    FocusCard({ actions.connectNearby(n) }, mod.fillMaxWidth()) { PhoneRow(n.name, n.url, "Connect") }
                }
            }
        }
    }
    st.asking?.let { PasswordDialog(it, actions) }
    if (manual) AddressDialog({ manual = false }) { a, p -> manual = false; actions.connectAddress(a, p) }
}

@Composable
private fun Header(text: String) {
    if (text.isNotEmpty()) Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Step(n: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(n, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        }
        Text(text, fontSize = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhoneRow(name: String, url: String, action: String) {
    Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(url.removePrefix("http://"), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(action, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PasswordDialog(n: Nearby, actions: TvActions) {
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = actions::cancelPassword,
        title = { Text("Password for ${n.name}") },
        text = {
            OutlinedTextField(
                pw, { pw = it }, singleLine = true, label = { Text("Password") },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { Button({ actions.submitPassword(pw) }) { Text("Connect") } },
        dismissButton = { Row { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") }; TextButton(actions::cancelPassword) { Text("Cancel") } } },
    )
}

@Composable
private fun AddressDialog(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    var addr by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start sharing in LocalShare, then type the address it shows, for example 192.168.1.5:8080.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(addr, { addr = it }, singleLine = true, label = { Text("Address") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(pw, { pw = it }, singleLine = true, label = { Text("Password (if any)") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { Button({ onConnect(addr, pw) }) { Text("Connect") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
