package roro.stellar.manager.ui.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import roro.stellar.manager.R
import roro.stellar.manager.ui.theme.AppShape
import roro.stellar.yuehong.shell.StellarStatus

@Composable
fun StartWirelessAdbCard(
    wirelessDebugEnabled: Boolean,
    stellarStatus: StellarStatus,
    onStartClick: () -> Unit,
) {
    val connected = wirelessDebugEnabled && (
        stellarStatus == StellarStatus.NeedsPermission || stellarStatus == StellarStatus.Granted
    )
    val label = when {
        connected -> "已连接"
        wirelessDebugEnabled && stellarStatus == StellarStatus.Checking -> "检查中"
        wirelessDebugEnabled -> "未就绪"
        else -> "未开启"
    }
    val detail = when {
        wirelessDebugEnabled && stellarStatus == StellarStatus.NeedsPermission -> "Stellar 服务已连接，请完成应用授权"
        connected -> "无线调试已开启，Stellar 服务已连接"
        wirelessDebugEnabled && stellarStatus == StellarStatus.Checking -> "无线调试已开启，正在检查 Stellar 服务"
        wirelessDebugEnabled -> "无线调试已开启，点击卡片可启动 Stellar 服务"
        else -> "点击卡片启动 Stellar 服务并激活无线调试"
    }
    val statusColor = when {
        connected -> MaterialTheme.colorScheme.primary
        wirelessDebugEnabled -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !connected, onClick = onStartClick),
        shape = AppShape.shapes.cardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = AppShape.shapes.iconSmall
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.wireless_debugging),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = AppShape.shapes.buttonMedium,
                color = statusColor.copy(alpha = 0.12f),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
    }
}
