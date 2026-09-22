package com.opencode.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opencode.mobile.data.local.DeviceEntity
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.repository.DeviceDraft
import com.opencode.mobile.data.repository.ModelOption
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion

@Composable
internal fun SheetHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OcColors.Ink3)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun SheetRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = OcColors.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = OcColors.Ink3,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = OcColors.Ink,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 工作区选择：列表来自电脑端 GET /project —— 手机端只选，不造。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSheet(
    workspaces: List<WorkspaceEntity>,
    currentPath: String?,
    deviceName: String?,
    onDismiss: () -> Unit,
    onSelect: (WorkspaceEntity) -> Unit,
    onRefresh: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OcColors.Surface,
        dragHandle = { DefaultHandle() },
    ) {
        SheetHeader(
            title = "工作区",
            subtitle = if (deviceName != null) "电脑端「$deviceName」已打开的项目" else "电脑端已打开的项目",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .padding(horizontal = 8.dp),
        ) {
            items(workspaces, key = { it.path }) { ws ->
                SheetRow(
                    title = ws.name,
                    subtitle = buildString {
                        append(ws.path)
                        ws.vcs?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    },
                    selected = ws.path == currentPath,
                    onClick = { onSelect(ws) },
                    trailing = {
                        if (ws.path == currentPath) StatusChip("当前", com.opencode.mobile.ui.components.ChipTone.Solid)
                    },
                )
            }
            if (workspaces.isEmpty()) {
                item {
                    Text(
                        "还没有同步到工作区。确认电脑端已经在某个项目目录下运行 opencode。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                    )
                }
            }
            item {
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("重新同步") }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 模型切换。只列服务端报告 connected 的 provider。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    options: List<ModelOption>,
    selected: ModelOption?,
    onDismiss: () -> Unit,
    onSelect: (ModelOption) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grouped = remember(options) { options.groupBy { it.providerName } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OcColors.Surface,
        dragHandle = { DefaultHandle() },
    ) {
        SheetHeader(
            title = "选择模型",
            // 这句必须说：用户很容易以为切换会重跑当前对话
            subtitle = "只影响后续发送的消息，不会重跑历史",
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .padding(horizontal = 8.dp),
        ) {
            grouped.forEach { (provider, list) ->
                item(key = "h_$provider") {
                    Text(
                        provider.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = OcColors.Ink3,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { it.key }) { option ->
                    SheetRow(
                        title = option.modelName,
                        subtitle = option.providerId,
                        selected = option.key == selected?.key,
                        onClick = { onSelect(option) },
                        trailing = {
                            if (option.isServerDefault) {
                                StatusChip("默认", com.opencode.mobile.ui.components.ChipTone.Neutral)
                            }
                        },
                    )
                }
            }
            if (options.isEmpty()) {
                item {
                    Text(
                        "没有取到可用模型。请确认电脑端已登录至少一个 provider。",
                        style = MaterialTheme.typography.bodySmall,
                        color = OcColors.Ink3,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 设备管理：多台电脑的切换与新增。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(
    devices: List<DeviceEntity>,
    activeDeviceId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onAdd: (DeviceDraft) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var adding by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OcColors.Surface,
        dragHandle = { DefaultHandle() },
    ) {
        if (adding) {
            AddDeviceForm(onCancel = { adding = false }, onSubmit = onAdd)
        } else {
            SheetHeader(title = "设备", subtitle = "通过 Tailscale / WireGuard 私网连接")
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .padding(horizontal = 8.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    SheetRow(
                        title = device.name,
                        subtitle = "${device.host}:${device.port}",
                        selected = device.id == activeDeviceId,
                        onClick = { onSelect(device.id) },
                        trailing = {
                            if (device.id == activeDeviceId) {
                                StatusChip("当前", com.opencode.mobile.ui.components.ChipTone.Solid)
                            }
                        },
                    )
                }
                item {
                    SheetRow(
                        title = "添加设备",
                        subtitle = "填写 Tailscale 私网 IP 与端口密码",
                        selected = false,
                        onClick = { adding = true },
                        trailing = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = OcColors.Ink2,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
internal fun DefaultHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 8.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(OcColors.Line)
    )
}

@Composable
private fun AddDeviceForm(onCancel: () -> Unit, onSubmit: (DeviceDraft) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("4096") }
    var username by remember { mutableStateOf("opencode") }
    var password by remember { mutableStateOf("") }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("添加设备", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "地址填 Tailscale / WireGuard 分配给你的私网 IP；密码对应电脑上的 OPENCODE_SERVER_PASSWORD。",
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink3,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("名称") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("私网 IP 或主机名") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("端口") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(104.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("用户名") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(
                onClick = {
                    onSubmit(
                        DeviceDraft(
                            name = name.ifBlank { host },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 4096,
                            username = username.ifBlank { "opencode" },
                            password = password,
                        )
                    )
                },
                enabled = host.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OcColors.Ink,
                    contentColor = OcColors.Surface,
                ),
                modifier = Modifier.weight(1f),
            ) { Text("保存并连接") }
        }
        Spacer(Modifier.height(20.dp))
    }
}
