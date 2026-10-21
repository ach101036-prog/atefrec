package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.RecordingSession
import com.example.data.model.TikTokLiveInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TokRecord", appName)
  }

  @Test
  fun `cleanUsername handles @ prefix and whitespace`() {
    val info = TikTokLiveInfo(username = "  @khaby.lame  ")
    assertEquals("khaby.lame", info.cleanUsername)
  }

  @Test
  fun `recording session formatted duration and size calculate correctly`() {
    val session = RecordingSession(
      id = 1,
      username = "khaby.lame",
      title = "Live Gaming",
      mediaStoreUriString = "content://media/external/video/media/123",
      fileName = "TikTok_khaby_lame_20261021.mp4",
      durationSeconds = 125, // 2m 5s
      fileSizeBytes = 50 * 1024 * 1024 // 50 MB
    )
    assertEquals("02:05", session.formattedDuration)
    assertTrue(session.formattedSize.contains("50.0 MB"))
  }
}
