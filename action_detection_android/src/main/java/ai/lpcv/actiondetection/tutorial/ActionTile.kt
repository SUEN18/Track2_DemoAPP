package ai.lpcv.actiondetection.tutorial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ActionTile(
    item: ActionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var frameIndex by remember { mutableStateOf(0) }
    
    LaunchedEffect(item.previewFilename) {
        while (true) {
            delay(250) // 4 FPS
            frameIndex = (frameIndex + 1) % 16
        }
    }

    Column(
        modifier = modifier
            .padding(4.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            SpriteImage(
                assetPath = "tutorial/previews/${item.previewFilename}",
                frameIndex = frameIndex,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.label,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
}

@Composable
fun SpriteImage(
    assetPath: String,
    frameIndex: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(assetPath) {
        try {
            context.assets.open(assetPath).use { 
                android.graphics.BitmapFactory.decodeStream(it) 
            }?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Canvas(modifier = modifier) {
            val frameWidth = bitmap.width / 4
            val frameHeight = bitmap.height / 4
            val col = frameIndex % 4
            val row = frameIndex / 4
            
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(col * frameWidth, row * frameHeight),
                srcSize = IntSize(frameWidth, frameHeight),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }
}
