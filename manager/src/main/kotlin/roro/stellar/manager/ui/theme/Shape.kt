package roro.stellar.manager.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

@Immutable
data class AppShapes(
    val iconMedium18: RoundedCornerShape = RoundedCornerShape(18.dp),
    val iconSmall: RoundedCornerShape = RoundedCornerShape(12.dp),
    val cardLarge: RoundedCornerShape = RoundedCornerShape(24.dp),
    val cardMedium24: RoundedCornerShape = RoundedCornerShape(20.dp),
    val cardMedium: RoundedCornerShape = RoundedCornerShape(16.dp),
    val buttonMedium: RoundedCornerShape = RoundedCornerShape(16.dp),
    val buttonSmall14: RoundedCornerShape = RoundedCornerShape(12.dp),
    val inputField: RoundedCornerShape = RoundedCornerShape(12.dp),
    val dialog: RoundedCornerShape = RoundedCornerShape(28.dp),
    val dialogContent: RoundedCornerShape = RoundedCornerShape(16.dp),
    val tag: RoundedCornerShape = RoundedCornerShape(8.dp),
    val fab: RoundedCornerShape = RoundedCornerShape(16.dp),
)

object AppShape {
    val shapes = AppShapes()
}
