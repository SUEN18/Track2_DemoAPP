package ai.lpcv.actiondetection.tutorial

import android.content.Context
import org.json.JSONObject

data class ActionItem(
    val label: String,
    val videoFilename: String,
    val previewFilename: String
)

object TutorialRepository {
    fun loadActionItems(context: Context): List<ActionItem> {
        val labelToVideoJson = try {
            context.assets.open("tutorial/label_videos.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val labelToVideo = JSONObject(labelToVideoJson)
        
        val items = mutableListOf<ActionItem>()
        val keys = labelToVideo.keys()
        while (keys.hasNext()) {
            val label = keys.next()
            val videoFile = labelToVideo.getString(label)
            val previewFile = videoFile.replace(".mp4", ".jpg")
            items.add(ActionItem(label, videoFile, previewFile))
        }
        return items.sortedBy { it.label }
    }
}
